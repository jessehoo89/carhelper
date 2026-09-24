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
 * 不依赖任何三方库；密钥为进程内生成（首次连接需在车机屏点"允许"，之后 adbd 记住该公钥）。
 */
public class AdbClient {

    private static final int A_CNXN = 0x4e584e43; // "CNXN"
    private static final int A_AUTH = 0x48545541; // "AUTH"
    private static final int A_OPEN = 0x4e45504f; // "OPEN"
    private static final int A_OKAY = 0x59414b4f; // "OKAY"
    private static final int A_CLSE = 0x45534c43; // "CLSE"
    private static final int A_WRTE = 0x45545257; // "WRTE"

    private static final String BANNER = "carhelper";

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private PrivateKey priv;
    private PublicKey pub;
    private boolean authSent = false;

    public boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
    }

    // ------------------------------------------------------------------ 连接

    public void connect(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(readTimeoutMs);
        in = sock.getInputStream();
        out = sock.getOutputStream();

        send(A_CNXN, 0x01000000, 256 * 1024,
                ("host::features=shell_v2,cmd,stat_v2,ls_v2,apex").getBytes("UTF-8"));

        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                return; // 握手完成
            } else if (m.cmd == A_AUTH) {
                handleAuth(m);
            } else {
                throw new IOException("ADB 握手异常，收到 " + cmdName(m.cmd));
            }
        }
    }

    private void handleAuth(Msg m) throws IOException {
        if (m.arg0 == 1 && !authSent) {
            // TOKEN：用私钥签名
            ensureKey();
            byte[] sig = signToken(m.data);
            if (sig != null) {
                authSent = true;
                send(A_AUTH, 2, 0, sig);
                return;
            }
        }
        // 签名未被认可（或服务端要求公钥）：发公钥，车机屏将弹授权确认
        ensureKey();
        send(A_AUTH, 3, 0, adbPublicKeyBytes());
    }

    // ------------------------------------------------------------------ 密钥

    private void ensureKey() {
        if (priv != null) return;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            priv = kp.getPrivate();
            pub = kp.getPublic();
        } catch (Exception e) {
            throw new RuntimeException("RSA 密钥生成失败: " + e);
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

    // ------------------------------------------------------------------ 流操作

    /** 执行 shell 命令，返回 stdout+stderr 合并文本。 */
    public String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = -1;
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY) {
                if (remote < 0) {
                    remote = m.arg0;
                    // 关闭 stdin
                    send(A_WRTE, local, remote, new byte[0]);
                } else {
                    // 对服务端数据的确认
                }
            } else if (m.cmd == A_WRTE) {
                if (m.arg1 == local) {
                    buf.write(m.data);
                    send(A_OKAY, local, m.arg0, null);
                }
            } else if (m.cmd == A_CLSE) {
                if (m.arg1 == local || m.arg0 == remote) {
                    send(A_CLSE, local, m.arg0, null);
                    break;
                }
            }
        }
        return buf.toString("UTF-8");
    }

    /** 用 sync 协议把数据流推到车机路径。 */
    public void push(InputStream src, String remotePath, int mode) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, "sync:\0".getBytes("UTF-8"));
        int remote = -1;
        // 等 OKAY
        while (remote < 0) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == local) {
                remote = m.arg0;
            } else if (m.cmd == A_CLSE) {
                throw new IOException("sync 流被拒绝");
            }
        }
        byte[] pathBytes = remotePath.getBytes("UTF-8");
        // SEND
        ByteBuffer send = ByteBuffer.allocate(8 + pathBytes.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        send.put("SEND".getBytes("US-ASCII"));
        send.putInt(pathBytes.length);
        send.put(pathBytes);
        send.putInt(mode);
        syncWrite(local, remote, send.array());
        syncExpectOkay(local, remote);
        // DATA
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = src.read(chunk)) > 0) {
            ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            head.put("DATA".getBytes("US-ASCII"));
            head.putInt(n);
            syncWrite(local, remote, head.array());
            syncWrite(local, remote, chunk, n);
            syncExpectOkay(local, remote);
        }
        // DONE + QUIT
        ByteBuffer done = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        done.put("DONE".getBytes("US-ASCII"));
        done.putInt((int) (System.currentTimeMillis() / 1000));
        syncWrite(local, remote, done.array());
        syncExpectOkay(local, remote);
        ByteBuffer quit = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        quit.put("QUIT".getBytes("US-ASCII"));
        quit.putInt(0);
        syncWrite(local, remote, quit.array());
        send(A_CLSE, local, remote, null);
    }

    private void syncWrite(int local, int remote, byte[] data) throws IOException {
        syncWrite(local, remote, data, data.length);
    }

    private void syncWrite(int local, int remote, byte[] data, int len) throws IOException {
        send(A_WRTE, local, remote, data, len);
        // 等 OKAY（ADB 流控：每条 WRTE 需被确认）
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == local) return;
            if (m.cmd == A_CLSE) throw new IOException("sync 传输中断");
        }
    }

    private void syncExpectOkay(int local, int remote) throws IOException {
        // sync 层的 "OKAY"/"FAIL" 响应由服务端通过 WRTE 回来
        while (true) {
            Msg m = read();
            if (m.cmd == A_WRTE && m.arg1 == local) {
                String r = new String(m.data, "US-ASCII");
                send(A_OKAY, local, m.arg0, null);
                if (r.startsWith("FAIL")) {
                    throw new IOException("sync 失败: " + r);
                }
                return;
            } else if (m.cmd == A_CLSE) {
                return;
            } else if (m.cmd == A_OKAY) {
                continue;
            }
        }
    }

    public void close() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
        sock = null;
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
