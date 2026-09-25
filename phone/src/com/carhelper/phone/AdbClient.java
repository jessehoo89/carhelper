package com.carhelper.phone;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.zip.CRC32;

/**
 * 极简 ADB 客户端（只实现装机需要的子集）。
 *
 * 协议要点：
 *  - 24 字节消息头（小端）：cmd / arg0 / arg1 / data_length / data_crc32 / magic(=cmd^0xFFFFFFFF)
 *  - 握手：CNXN(0x01000000, maxdata, "host::features=...") → 若 adbd 为 secure 模式会回 AUTH
 *  - 认证：AUTH(1=TOKEN) → 回 AUTH(2=SIGNATURE, SHA1withRSA(token))；失败再回 AUTH(3=RSAPUBLICKEY) 触发车机屏授权
 *  - 流：OPEN(localId,0,"shell:cmd\0" 或 "sync:\0") → OKAY(remoteId,localId) → WRTE/OKAY 往返 → CLSE
 *
 * 两条关键经验（v1.0.1 修正，勿再改回去）：
 *  1) **RSA 密钥必须持久化**。密钥一变，adbd 就不认识我们，车机屏会重新弹授权框。
 *     密钥由外部 {@link KeyProvider} 存取（手机端存在 SharedPreferences 里），
 *     一次授权后永久免弹（除非用户在车机里撤销了调试授权）。
 *  2) **SEND_V1 的载荷格式是 "<路径>,<八进制权限字符串>"**，整串作为「带长度前缀的 path 字段」
 *     发过去，后面**不再**跟一个 4 字节二进制 mode。
 *     见 AOSP packages/modules/adb/daemon/file_sync_service.cpp: do_send_v1()
 *     `spec.find_last_of(',')`，找不到逗号就回 "missing , in ID_SEND_V1"（v1.0.0 的 bug：
 *     误用了 SyncRequest 结构 + 二进制 mode，导致安装直接失败 / 连接被中断）。
 *
 * 不依赖任何三方库。
 */
public class AdbClient {

    /** 密钥持久化 + 授权提示回调。手机上由 SharedPreferences 实现。 */
    public interface KeyProvider {
        byte[] loadPrivate();   // PKCS#8，没有则返回 null

        byte[] loadPublic();    // X.509，没有则返回 null

        void save(byte[] priv, byte[] pub);

        /** 即将把公钥发给车机（车机屏会弹授权框）时回调，用于提示用户去点「允许」。 */
        void onAuthRequested();
    }

    /** 推送进度回调（字节数）。 */
    public interface Progress {
        void onBytes(long done);
    }

    private static final int A_CNXN = 0x4e584e43; // "CNXN"
    private static final int A_AUTH = 0x48545541; // "AUTH"
    private static final int A_OPEN = 0x4e45504f; // "OPEN"
    private static final int A_OKAY = 0x59414b4f; // "OKAY"
    private static final int A_CLSE = 0x45534c43; // "CLSE"
    private static final int A_WRTE = 0x45545257; // "WRTE"

    private static final int MAX_DATA = 256 * 1024;      // 本端可接收的最大包
    private static final int SYNC_DATA_MAX = 64 * 1024;  // adbd sync 服务的单块上限（勿超）
    private static final String BANNER = "carhelper";
    private static final String FEATURES = "host::features=shell_v2,cmd,stat_v2,ls_v2,apex";

    /** 打开后把收发报文打到 stdout（桌面联测/排障用），线上保持 false。 */
    public static boolean DEBUG = false;

    private final KeyProvider keys;

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private PrivateKey priv;
    private PublicKey pub;
    private int devMaxData = MAX_DATA;
    /** 等待 A_OKAY 期间先收到的 sync 应答（WRTE 载荷），供 syncExpectOkay 按序消费。 */
    private final ArrayDeque<byte[]> pendingSync = new ArrayDeque<byte[]>();

    public AdbClient(KeyProvider keys) {
        this.keys = keys;
    }

    public boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
    }

    // ------------------------------------------------------------------ 连接

    /** 连接并完成握手；返回 true 表示本次连接向车机发了公钥（即车机屏弹了授权框）。 */
    public boolean connect(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        sock.setTcpNoDelay(true);
        // 握手期给用户留出点车机授权框的时间
        sock.setSoTimeout(Math.max(readTimeoutMs, 120000));
        in = sock.getInputStream();
        out = sock.getOutputStream();

        pendingSync.clear();
        send(A_CNXN, 0x01000000, MAX_DATA, FEATURES.getBytes("UTF-8"));

        boolean pubkeySent = false;
        boolean sigTried = false;
        int pubkeySends = 0;
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                if (m.arg1 > 4096) devMaxData = m.arg1;
                sock.setSoTimeout(readTimeoutMs);
                return pubkeySent;
            } else if (m.cmd == A_AUTH) {
                if (m.arg0 == 1 && !sigTried) {
                    // TOKEN：先用持久化的私钥签名（密钥已授权时，一步通过、不弹框）
                    sigTried = true;
                    ensureKey();
                    byte[] sig = signToken(m.data);
                    if (sig != null) {
                        send(A_AUTH, 2, 0, sig);
                        continue;
                    }
                }
                // 签名未被认可（或车机要求公钥）：发公钥，车机屏弹授权框
                if (pubkeySends >= 3) {
                    throw new IOException("车机未接受本机公钥（授权框可能被点了“拒绝”）。"
                            + "请在车机屏上重新点“允许/始终允许”，再重试。");
                }
                pubkeySends++;
                pubkeySent = true;
                sigTried = false; // 授权通过后车机会再发 TOKEN，我们要能再签一次
                if (keys != null) keys.onAuthRequested();
                ensureKey();
                send(A_AUTH, 3, 0, adbPublicKeyBytes());
            } else {
                throw new IOException("ADB 握手异常，收到 " + cmdName(m.cmd));
            }
        }
    }

    // ------------------------------------------------------------------ 密钥（持久化）

    private void ensureKey() {
        if (priv != null) return;
        // 1) 先尝试加载已保存的密钥
        if (keys != null) {
            byte[] pk = keys.loadPrivate();
            byte[] pb = keys.loadPublic();
            if (pk != null && pb != null) {
                try {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pk));
                    pub = kf.generatePublic(new X509EncodedKeySpec(pb));
                    return;
                } catch (Exception ignored) {
                    // 损坏则重新生成
                }
            }
        }
        // 2) 生成并保存
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            priv = kp.getPrivate();
            pub = kp.getPublic();
            if (keys != null) {
                keys.save(priv.getEncoded(), pub.getEncoded());
            }
        } catch (Exception e) {
            throw new RuntimeException("RSA 密钥生成失败: " + e);
        }
    }

    /** 本机 ADB 公钥指纹（SHA256 前 8 字节的十六进制），用于日志核对密钥是否变了。 */
    public String keyFingerprint() {
        try {
            ensureKey();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(pub.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private byte[] signToken(byte[] token) {
        try {
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign(priv);
            s.update(token);
            return s.sign();
        } catch (Exception e) {
            return null;
        }
    }

    /** ADB 私有公钥格式：base64(结构体) + " " + user@host + "\0"。 */
    private byte[] adbPublicKeyBytes() throws IOException {
        try {
            RSAPublicKey rk = (RSAPublicKey) pub;
            byte[] nRaw = rk.getModulus().toByteArray(); // 大端，可能带前导 0
            int off = (nRaw.length > 1 && nRaw[0] == 0) ? 1 : 0;
            int nlen = nRaw.length - off;
            byte[] n = new byte[nlen];
            System.arraycopy(nRaw, off, n, 0, nlen);
            int e = rk.getPublicExponent().intValue();

            BigInteger N = new BigInteger(1, n);
            BigInteger M32 = BigInteger.ONE.shiftLeft(32);
            BigInteger n0 = N.mod(M32);
            int n0inv = n0.modInverse(M32).negate().mod(M32).intValue();
            BigInteger R = BigInteger.ONE.shiftLeft(32 * nlen);
            int rr = R.modPow(BigInteger.valueOf(2), N).intValue();

            int total = 4 + 4 + 4 + nlen + 4 + 4;
            ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(total);
            bb.putInt(n0inv);
            bb.putInt(nlen);
            bb.put(n);        // modulus（大端）
            bb.putInt(rr);
            bb.putInt(e);     // exponent（小端）
            String s = Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " " + BANNER + "\0";
            return s.getBytes("UTF-8");
        } catch (Exception ex) {
            throw new IOException("公钥编码失败: " + ex);
        }
    }

    // ------------------------------------------------------------------ shell

    public String shell(String command) throws IOException {
        return shell(command, -1);
    }

    /** 执行 shell 命令，返回 stdout+stderr 合并文本。timeoutMs <= 0 用 socket 默认超时。 */
    public String shell(String command, int timeoutMs) throws IOException {
        int local = nextLocalId++;
        int oldTimeout = -1;
        if (timeoutMs > 0) {
            oldTimeout = sock.getSoTimeout();
            sock.setSoTimeout(timeoutMs);
        }
        try {
            send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int remote = -1;
            boolean stdinClosed = false;
            while (true) {
                Msg m = read();
                if (m.cmd == A_OKAY) {
                    // 只认属于本流的 OKAY（arg1 == 本端 local id）。
                    // 上一条流（如刚结束的 sync）可能残留 OKAY/CLSE，若不加这个判定，
                    // 会把它的 id 当成本流的 remote id，随后把它的收尾 CLSE 当成本流关闭 → 返回空结果。
                    if (remote < 0 && m.arg1 == local) {
                        remote = m.arg0;
                    }
                    if (remote >= 0 && !stdinClosed) {
                        // 命令不读 stdin → 立刻关掉，避免服务端等待
                        stdinClosed = true;
                        send(A_WRTE, local, remote, new byte[0]);
                    }
                } else if (m.cmd == A_WRTE) {
                    if (m.arg1 == local) {
                        buf.write(m.data);
                        send(A_OKAY, local, m.arg0, null);
                    }
                } else if (m.cmd == A_CLSE) {
                    if (m.arg1 == local) {
                        send(A_CLSE, local, m.arg0, null);
                        break;
                    }
                }
            }
            return buf.toString("UTF-8");
        } finally {
            if (oldTimeout > 0) {
                try {
                    sock.setSoTimeout(oldTimeout);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 推送（sync）

    /**
     * 用 sync 协议把数据流推到车机路径。
     *
     * @return 实际推送字节数
     */
    public long push(InputStream src, String remotePath, int mode, Progress p) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, "sync:\0".getBytes("UTF-8"));
        int remote = -1;
        while (remote < 0) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == local) {
                remote = m.arg0;
            } else if (m.cmd == A_CLSE) {
                send(A_CLSE, local, m.arg0, null);
                throw new IOException("车机拒绝了 sync 通道");
            }
        }

        // SEND_V1：path 字段 = "<路径>,<八进制权限>"（例 /data/local/tmp/a.apk,0644）
        // 注意：权限串必须带前导 0，adbd 用 strtoul(..., base 0) 解析，没有前导 0 会当成十进制。
        String spec = remotePath + ",0" + Integer.toOctalString(mode & 0777);
        byte[] specBytes = spec.getBytes("UTF-8");
        ByteBuffer head = ByteBuffer.allocate(8 + specBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        head.put("SEND".getBytes("US-ASCII"));
        head.putInt(specBytes.length);
        head.put(specBytes);
        syncWrite(local, remote, head.array());
        syncExpectOkay(local, remote);

        int chunkMax = Math.min(SYNC_DATA_MAX, Math.max(4096, devMaxData - 8));
        byte[] buf = new byte[chunkMax];
        long total = 0;
        int n;
        while ((n = src.read(buf)) > 0) {
            ByteBuffer h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            h.put("DATA".getBytes("US-ASCII"));
            h.putInt(n);
            syncWrite(local, remote, h.array());
            syncWrite(local, remote, buf, n);
            syncExpectOkay(local, remote);
            total += n;
            if (p != null) p.onBytes(total);
        }

        ByteBuffer done = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        done.put("DONE".getBytes("US-ASCII"));
        done.putInt((int) (System.currentTimeMillis() / 1000));
        syncWrite(local, remote, done.array());
        syncExpectOkay(local, remote);

        ByteBuffer quit = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        quit.put("QUIT".getBytes("US-ASCII"));
        quit.putInt(0);
        try {
            send(A_WRTE, local, remote, quit.array());
        } catch (IOException ignored) {
        }
        send(A_CLSE, local, remote, null);
        drainTrailing(local);
        return total;
    }

    /**
     * 兜底推送：走 shell 的 stdin（`cat > 路径`）把数据流写进车机。
     * 只在 sync 通道不可用时使用；通道本身与「一键连接」用的 shell 完全一样。
     */
    public long pushViaShell(InputStream src, String remotePath, Progress p) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, ("shell:cat > " + remotePath + "\0").getBytes("UTF-8"));
        int remote = -1;
        while (remote < 0) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == local) {
                remote = m.arg0;
            } else if (m.cmd == A_CLSE) {
                send(A_CLSE, local, m.arg0, null);
                throw new IOException("车机拒绝了 shell 通道");
            }
        }
        int chunkMax = Math.max(4096, Math.min(SYNC_DATA_MAX, devMaxData - 24));
        byte[] buf = new byte[chunkMax];
        long total = 0;
        int n;
        while ((n = src.read(buf)) > 0) {
            streamWrite(local, remote, buf, n);
            total += n;
            if (p != null) p.onBytes(total);
        }
        streamWrite(local, remote, null, 0); // 关闭 stdin（cat 收到 EOF）
        // 读完 shell 的输出直到本流关闭（其它流的残留报文一律忽略）
        while (true) {
            Msg m = read();
            if (m.cmd == A_WRTE && m.arg1 == local) {
                send(A_OKAY, local, m.arg0, null);
            } else if (m.cmd == A_CLSE && m.arg1 == local) {
                send(A_CLSE, local, m.arg0, null);
                break;
            }
        }
        drainTrailing(local);
        return total;
    }

    /** 发一条 WRTE 并等对应的 A_OKAY（同步式流控）。 */
    private void syncWrite(int local, int remote, byte[] data) throws IOException {
        syncWrite(local, remote, data, data.length);
    }

    private void syncWrite(int local, int remote, byte[] data, int len) throws IOException {
        send(A_WRTE, local, remote, data, len);
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == local) return;
            if (m.cmd == A_WRTE && m.arg1 == local) {
                // 等流控确认时可能先收到 sync 层应答 → 缓存，交给 syncExpectOkay 按序消费
                pendingSync.addLast(m.data);
                send(A_OKAY, local, m.arg0, null);
                continue;
            }
            if (m.cmd == A_CLSE) throw new IOException("sync 传输中断（车机关闭了通道）");
        }
    }

    /** 读一条 sync 层应答（OKAY / FAIL+原因）。 */
    private void syncExpectOkay(int local, int remote) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        byte[] first = pendingSync.pollFirst();
        if (first != null) acc.write(first);
        while (true) {
            byte[] b = acc.toByteArray();
            if (b.length >= 4) {
                String id = new String(b, 0, 4, "US-ASCII");
                if ("OKAY".equals(id)) return;
                if ("FAIL".equals(id) && b.length >= 8) {
                    int msglen = leInt(b, 4);
                    if (msglen >= 0 && b.length >= 8 + msglen) {
                        throw new IOException("sync 失败: " + new String(b, 8, msglen, "UTF-8"));
                    }
                }
            }
            Msg m = read();
            if (m.cmd == A_WRTE && m.arg1 == local) {
                acc.write(m.data);
                send(A_OKAY, local, m.arg0, null);
            } else if (m.cmd == A_CLSE && m.arg1 == local) {
                throw new IOException("sync 通道被车机关闭（推送中断）");
            }
        }
    }

    /**
     * 排空上一条流结束后的收尾报文（服务端的 CLSE、可能滞后的 OKAY）。
     * 不排空也不会错（各流都按自己的 local id 判归属），但排掉更干净、少一次误判机会。
     */
    private void drainTrailing(int local) {
        int old;
        try {
            old = sock.getSoTimeout();
            sock.setSoTimeout(300);
        } catch (Exception e) {
            return;
        }
        try {
            for (int i = 0; i < 2; i++) {
                Msg m = read();
                if (DEBUG) System.out.println("  [adb tx-drain] " + cmdName(m.cmd) + " arg0=" + m.arg0 + " arg1=" + m.arg1);
                if (m.cmd == A_WRTE) {
                    // 意外数据：不属于任何已知请求，直接丢弃（本流已结束）
                    continue;
                }
            }
        } catch (IOException ignored) {
        } finally {
            try {
                sock.setSoTimeout(old);
            } catch (Exception ignored) {
            }
        }
    }

    private void streamWrite(int local, int remote, byte[] data, int len) throws IOException {
        send(A_WRTE, local, remote, data, len);
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == local) return;
            if (m.cmd == A_CLSE) throw new IOException("shell 传输中断（车机关闭了通道）");
        }
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    public void close() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
        sock = null;
        pendingSync.clear();
    }

    // ------------------------------------------------------------------ 底层读写

    private static class Msg {
        int cmd, arg0, arg1, len, crc, magic;
        byte[] data = new byte[0];
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        send(cmd, arg0, arg1, data, data == null ? 0 : data.length);
    }

    private void send(int cmd, int arg0, int arg1, byte[] data, int len) throws IOException {
        byte[] d = data == null ? new byte[0] : data;
        CRC32 crc = new CRC32();
        crc.update(d, 0, len);
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd);
        b.putInt(arg0);
        b.putInt(arg1);
        b.putInt(len);
        b.putInt((int) crc.getValue());
        b.putInt(cmd ^ 0xFFFFFFFF);
        b.put(d, 0, len);
        out.write(b.array());
        out.flush();
        if (DEBUG) {
            System.out.println("  [adb tx] " + cmdName(cmd) + " arg0=" + arg0 + " arg1=" + arg1
                    + " len=" + len + (len > 0 && len < 64 ? " data=" + new String(d, 0, len).replace("\n", "\\n") : ""));
        }
    }

    private Msg read() throws IOException {
        byte[] head = readFully(24);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        m.len = b.getInt();
        m.crc = b.getInt();
        m.magic = b.getInt();
        if (m.len < 0 || m.len > 4 * 1024 * 1024) throw new IOException("非法数据长度 " + m.len);
        m.data = m.len > 0 ? readFully(m.len) : new byte[0];
        if (DEBUG) {
            System.out.println("  [adb rx] " + cmdName(m.cmd) + " arg0=" + m.arg0 + " arg1=" + m.arg1
                    + " len=" + m.len + (m.len > 0 && m.len < 64 ? " data=" + new String(m.data).replace("\n", "\\n") : ""));
        }
        return m;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("连接被关闭");
            off += r;
        }
        return buf;
    }

    private static String cmdName(int c) {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c).array();
        return new String(b);
    }
}
