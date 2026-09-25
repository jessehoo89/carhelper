package com.carhelper.fullscreen;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * 车机端安装：走 PackageInstaller 会话（与 LIGHTBOX MAX 的 InstallerActivity 同一套机制）。
 *
 *   createSession(MODE_FULL_INSTALL) → openWrite 写入 APK → fsync → commit(广播 PendingIntent)
 *   → 系统安装侧接手（第三方应用必然弹「确认安装」界面）→ 结果回广播
 *
 * 注意：**这条路不绕过车机自带的安装校验**（包名白名单/校验器就在系统安装那一步生效）；
 * 它的价值是"不用连 ADB 也能把 APK 送进车机的系统安装流程"。
 */
public final class ApkInstaller {

    public static final String ACTION_RESULT = "com.carhelper.fullscreen.INSTALL_RESULT";
    private static BroadcastReceiver receiver;
    private static volatile String lastResult = "";

    private ApkInstaller() {
    }

    public static String lastResult() {
        return lastResult;
    }

    public static String install(final Context ctx, File apk) {
        try {
            PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sid = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(sid);
            OutputStream out = session.openWrite("apk", 0, apk.length());
            FileInputStream fis = new FileInputStream(apk);
            try {
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = fis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                U.log("写入安装会话 " + total + " 字节");
            } finally {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
                try {
                    session.fsync(out);
                } catch (Exception ignored) {
                }
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }

            ensureReceiver(ctx);
            Intent r = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent cb = PendingIntent.getBroadcast(ctx, sid, r, flags);
            session.commit(cb.getIntentSender());
            session.close();
            lastResult = "已提交安装（会话 " + sid + "），等系统确认";
            U.log(lastResult);
            return lastResult + "。\n若车机屏幕上出现安装确认框，请点「安装/继续」；" +
                    "若被拦（校验/白名单），车机会直接返回失败原因。";
        } catch (Throwable t) {
            lastResult = "提交安装失败：" + t;
            U.log(lastResult);
            return lastResult;
        }
    }

    /** 接收安装结果：STATUS_PENDING_USER_ACTION 时必须把系统给的 Intent 拉起来（确认界面）。 */
    private static void ensureReceiver(final Context ctx) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
                    String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Intent userAction = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    U.log("安装结果 status=" + status + " pkg=" + pkg + " msg=" + msg
                            + (userAction != null ? " （需要用户操作，拉起确认界面）" : ""));
                    lastResult = "status=" + status + (pkg == null ? "" : " pkg=" + pkg)
                            + (msg == null ? "" : " msg=" + msg);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && userAction != null) {
                        userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(userAction);
                    }
                } catch (Throwable t) {
                    U.log("安装结果处理异常: " + t);
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(ACTION_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, f);
            }
        } catch (Throwable t) {
            receiver = null;
            U.log("注册安装结果接收器失败: " + t);
        }
    }
}
