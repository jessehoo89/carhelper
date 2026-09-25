package android.util;

/** 仅用于在桌面 JDK 上跑 AdbClient 单元测试的桩实现（不进 APK）。 */
public class Base64 {
    public static final int NO_WRAP = 2;

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }
}
