package com.carhelper.fullscreen;

import android.os.IBinder;
import android.os.Parcel;

/**
 * 吉利/领克多屏管理服务 geely_multi 的调用桥（实现方式对照 ONE BOX `GeelyFs` / LIGHTBOX MAX `C2`）。
 *
 *  服务名  geely_multi
 *  接口    android.view.IGeelyMultiManagerExt（隐藏 API，需先解除 ART 黑名单）
 *  事务    4 = getTopPkgName(area)，6 = moveScreen2Screen(from, to, flag)
 *          moveScreen 的第三参固定写 0（= moveScreen2Screen(..., false)），与两个样本一致；
 *          ⚠️ 实测：写非 0 会改变语义，别动。
 *  区域    1001 中控 / 1002 副驾 / 1003 全屏
 *
 * 关键语义（这条决定了"为什么要有悬浮球"）：
 *  moveScreen2Screen 搬的是**该区域当前正在显示的那个页面/窗口**，
 *  所以必须在"目标 App 正处于前台"时发起调用；若在自家 App 前台时调用，搬走的只是自己。
 */
public final class Geely {

    public static final int AREA_CSD = 1001;   // 中控（主驾侧）
    public static final int AREA_PSD = 1002;   // 副驾
    public static final int AREA_FULL = 1003;  // 全屏

    static final String SVC = "geely_multi";
    static final String DESC = "android.view.IGeelyMultiManagerExt";
    static final int TXN_TOP_PKG = 4;
    static final int TXN_MOVE = 6;

    private Geely() {
    }

    /** 解除 ART 隐藏 API 黑名单，否则反射不到 IGeelyMultiManagerExt 等类。 */
    public static void bypassHiddenApi() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object rt = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(rt, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {
        }
    }

    public static IBinder svcBinder() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, SVC);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object iface() {
        try {
            IBinder b = svcBinder();
            if (b == null) return null;
            return Class.forName(DESC + "$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, b);
        } catch (Throwable t) {
            return null;
        }
    }

    /** moveScreen2Screen(from, to, false)：先裸 transact(6)，失败再走 AIDL 反射。 */
    public static boolean moveScreen(int from, int to) {
        IBinder b = svcBinder();
        if (b != null) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESC);
                data.writeInt(from);
                data.writeInt(to);
                data.writeInt(0);
                b.transact(TXN_MOVE, data, reply, 0);
                reply.readException();
                return true;
            } catch (Throwable ignored) {
                // 落到 AIDL 反射
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        Object i = iface();
        if (i == null) return false;
        try {
            i.getClass().getMethod("moveScreen2Screen", Integer.TYPE, Integer.TYPE, Boolean.TYPE)
                    .invoke(i, Integer.valueOf(from), Integer.valueOf(to), Boolean.FALSE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** getTopPkgName(area)：该区域当前顶层应用包名，无则 null。 */
    public static String topPkg(int area) {
        Object i = iface();
        if (i != null) {
            try {
                Object r = i.getClass().getMethod("getTopPkgName", Integer.TYPE)
                        .invoke(i, Integer.valueOf(area));
                String s = r == null ? null : r.toString();
                if (s != null && s.length() > 0) return s;
            } catch (Throwable ignored) {
            }
        }
        IBinder b = svcBinder();
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC);
            data.writeInt(area);
            b.transact(TXN_TOP_PKG, data, reply, 0);
            reply.readException();
            String s = reply.readString();
            return (s != null && s.length() > 0) ? s : null;
        } catch (Throwable t) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 该包是不是"可以搬的页面"：排除自己、桌面/系统 UI/安装器/权限控制器等（照 LIGHTBOX C2 的排除表）。 */
    public static boolean isTransferable(String pkg, String selfPkg) {
        if (pkg == null || pkg.length() == 0) return false;
        String p = pkg.toLowerCase();
        if (selfPkg != null && p.equals(selfPkg.toLowerCase())) return false;
        if (p.contains("launcher")) return false;
        if (p.contains("systemui")) return false;
        if (p.contains("packageinstaller")) return false;
        if (p.contains("permissioncontroller")) return false;
        return true;
    }
}
