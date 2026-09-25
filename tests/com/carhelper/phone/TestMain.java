package com.carhelper.phone;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Random;

/** 桌面端联测：把 AdbClient 打到 mock_adbd.py 上，验证握手/密钥持久化/推送/shell。 */
public class TestMain {

    static byte[] pk, pb;
    static boolean authAsked = false;
    static int failures = 0;

    static class MemKeys implements AdbClient.KeyProvider {
        private final String file;

        MemKeys(String file) {
            this.file = file;
            if (file != null) {
                try {
                    String[] p = new String(Files.readAllBytes(Paths.get(file))).split("\n");
                    pk = java.util.Base64.getDecoder().decode(p[0]);
                    pb = java.util.Base64.getDecoder().decode(p[1]);
                    System.out.println("  [client] 已加载持久化密钥");
                } catch (Exception ignored) {
                }
            }
        }

        public byte[] loadPrivate() { return pk; }

        public byte[] loadPublic() { return pb; }

        public void save(byte[] a, byte[] b) {
            pk = a;
            pb = b;
            if (file != null) {
                try (FileOutputStream o = new FileOutputStream(file)) {
                    o.write((java.util.Base64.getEncoder().encodeToString(a) + "\n"
                            + java.util.Base64.getEncoder().encodeToString(b)).getBytes());
                } catch (Exception ignored) {
                }
            }
        }

        public void onAuthRequested() {
            authAsked = true;
            System.out.println("  [client] onAuthRequested → 提示用户在车机屏点“允许”");
        }
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  ✅ " : "  ❌ ") + name);
        if (!ok) failures++;
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String keyFile = args.length > 1 ? args[1] : null;
        AdbClient.DEBUG = true;
        MemKeys keys = new MemKeys(keyFile);
        AdbClient adb = new AdbClient(keys);

        System.out.println("===== 连接 #1（密钥未授权）=====");
        boolean asked = adb.connect("127.0.0.1", port, 3000, 15000);
        System.out.println("  connect() 返回 公钥已提交=" + asked + "，onAuthRequested=" + authAsked);
        check("首次连接确实走了公钥授权", asked && authAsked);
        System.out.println("  密钥指纹 " + adb.keyFingerprint());

        String r = adb.shell("echo hello").trim();
        System.out.println("  shell echo → " + r);
        check("shell 通道可用", "hello".equals(r));

        // ---- 推送（sync 通道）：200KB 随机数据，跨多个 DATA 块
        byte[] big = new byte[200000];
        new Random(7).nextBytes(big);
        long n = adb.push(new ByteArrayInputStream(big), "/data/local/tmp/t1.bin", 0644, null);
        check("sync 推送字节数一致 (" + n + ")", n == big.length);
        String raw1 = adb.shell("mockinfo /data/local/tmp/t1.bin");
        System.out.println("  mockinfo #1 → [" + raw1.replace("\n", "\\n") + "] len=" + raw1.length());
        String info1 = raw1.trim();
        check("车机侧收到的内容与源文件逐字节一致（sync 通道）", info1.contains("size=" + big.length)
                && info1.contains(sha256(big)));

        // ---- 推送（shell 兜底通道）：150KB
        byte[] big2 = new byte[150000];
        new Random(11).nextBytes(big2);
        long n2 = adb.pushViaShell(new ByteArrayInputStream(big2), "/data/local/tmp/t2.bin", null);
        check("shell 通道推送字节数一致 (" + n2 + ")", n2 == big2.length);
        String info2 = adb.shell("mockinfo /data/local/tmp/t2.bin").trim();
        System.out.println("  mockinfo #2 → " + info2);
        check("车机侧收到的内容与源文件逐字节一致（shell 兜底通道）", info2.contains("size=" + big2.length)
                && info2.contains(sha256(big2)));

        // ---- 失败路径：车机回 FAIL 时应抛出带原因的异常
        String err = "";
        try {
            adb.push(new ByteArrayInputStream(new byte[]{1, 2, 3}), "/data/local/tmp/FAILTEST.bin", 0644, null);
        } catch (Exception e) {
            err = String.valueOf(e.getMessage());
        }
        System.out.println("  FAIL 路径异常 → " + err);
        check("sync FAIL 原因被正确抛出并带原文", err.contains("missing") && err.contains("ID_SEND_V1"));

        // ---- shell 通道在 sync 之后仍然可用（收尾 CLSE 不串流）
        String r2 = adb.shell("echo again").trim();
        check("sync 之后 shell 通道仍正常", "again".equals(r2));

        System.out.println("===== 连接 #2（复用同一密钥，应不再弹授权框）=====");
        adb.close();
        authAsked = false;
        AdbClient adb2 = new AdbClient(keys);
        boolean asked2 = adb2.connect("127.0.0.1", port, 3000, 15000);
        System.out.println("  connect() 返回 公钥已提交=" + asked2 + "，onAuthRequested=" + authAsked);
        check("复用持久化密钥后不再触发授权框", !asked2 && !authAsked);

        byte[] small = new byte[70000];
        new Random(3).nextBytes(small);
        long n3 = adb2.push(new ByteArrayInputStream(small), "/data/local/tmp/t3.bin", 0644, null);
        String info3 = adb2.shell("mockinfo /data/local/tmp/t3.bin").trim();
        check("复连后推送内容一致 (" + n3 + " 字节)", info3.contains("size=" + small.length)
                && info3.contains(sha256(small)));
        adb2.close();

        System.out.println(failures == 0 ? "\n全部通过 ✅" : "\n失败 " + failures + " 项 ❌");
        System.exit(failures == 0 ? 0 : 1);
    }
}
