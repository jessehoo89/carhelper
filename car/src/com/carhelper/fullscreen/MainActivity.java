package com.carhelper.fullscreen;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 车机端「一键全屏」。
 *
 * 通过吉利/领克私有系统服务 geely_multi 的隐藏 AIDL 接口
 * android.view.IGeelyMultiManagerExt 做多屏区域搬移：
 *   AREA_CSD=1001 中控 / AREA_PSD=1002 副驾 / AREA_FULL=1003 全屏
 * 全屏 = moveScreen(区, 1003)；还原 = moveScreen(1003, 原区)。
 *
 * 全部隐藏 API 走反射（编译期 android.jar 不含这些类）。
 * 不修改任何系统组件、不关校验器、不申请特殊权限。
 */
public class MainActivity extends Activity {

    private static final int AREA_CSD = 1001;
    private static final int AREA_PSD = 1002;
    private static final int AREA_FULL = 1003;
    private static final String SVC = "geely_multi";
    private static final String DESC = "android.view.IGeelyMultiManagerExt";
    private static final int TXN_TOP_PKG = 4;
    private static final int TXN_MOVE = 6;

    private TextView status;
    private TextView log;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bypassHiddenApi();
        setContentView(buildUi());
        refresh();
    }

    // ---------- UI（全部代码创建，不依赖任何三方库） ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0E1216"));
        int p = dp(28);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("车机助手 · 一键全屏");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("通过 geely_multi 系统服务搬移显示区域，不修改系统组件");
        sub.setTextColor(Color.parseColor("#7A8894"));
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9FE870"));
        status.setTextSize(15);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackground(rounded("#161C22", 12));
        root.addView(status);

        root.addView(gap(18));
        root.addView(btn("一键全屏", "把中控画面铺满全屏", "#1F6FEB", new Runnable() {
            public void run() { doMove(AREA_CSD, AREA_FULL, "一键全屏"); }
        }));
        root.addView(gap(10));
        root.addView(btn("还原中控", "把全屏画面还原回中控区", "#30363D", new Runnable() {
            public void run() { doMove(AREA_FULL, AREA_CSD, "还原中控"); }
        }));
        root.addView(gap(10));
        root.addView(btn("副驾全屏", "把副驾画面铺满全屏", "#30363D", new Runnable() {
            public void run() { doMove(AREA_PSD, AREA_FULL, "副驾全屏"); }
        }));
        root.addView(gap(10));
        root.addView(btn("刷新状态", "重新检测服务与各区域顶层应用", "#30363D", new Runnable() {
            public void run() { refresh(); }
        }));

        root.addView(gap(18));
        log = new TextView(this);
        log.setTextColor(Color.parseColor("#8B949E"));
        log.setTextSize(12);
        log.setText("就绪。");
        log.setPadding(dp(14), dp(14), dp(14), dp(14));
        log.setBackground(rounded("#161C22", 12));
        root.addView(log);
        return root;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private GradientDrawable rounded(String color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Button btn(String text, String hint, String bg, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(bg, 12));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        b.setContentDescription(hint);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return b;
    }

    private void say(final String s) {
        ui.post(new Runnable() {
            public void run() { log.setText(s); }
        });
    }

    // ---------- 隐藏 API：反射桥 ----------

    /** 解除 ART 隐藏 API 黑名单（否则反射不到 IGeelyMultiManagerExt 等）。 */
    private static void bypassHiddenApi() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object rt = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(rt, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {
        }
    }

    private static IBinder svcBinder() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, SVC);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object iface() {
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

    // ---------- 业务：搬屏 / 查询 ----------

    /** moveScreen2Screen(from, to, flag)：先走裸 transact(6)，失败再走 AIDL 反射。 */
    private static boolean moveScreen(int from, int to, boolean flag) {
        IBinder b = svcBinder();
        if (b != null) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESC);
                data.writeInt(from);
                data.writeInt(to);
                data.writeInt(flag ? 1 : 0);
                b.transact(TXN_MOVE, data, reply, 0);
                reply.readException();
                return true;
            } catch (Throwable t) {
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
                    .invoke(i, Integer.valueOf(from), Integer.valueOf(to), Boolean.valueOf(flag));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** getTopPkgName(area)：该区域当前顶层应用包名。 */
    private static String topPkg(int area) {
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

    private void doMove(final int from, final int to, final String label) {
        say(label + " …");
        new Thread(new Runnable() {
            public void run() {
                boolean ok = moveScreen(from, to, false);
                final String msg = label + (ok ? "：已下发" : "：失败（服务不可用）")
                        + "\nfrom=" + from + " → to=" + to;
                ui.post(new Runnable() {
                    public void run() {
                        log.setText(msg);
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void refresh() {
        new Thread(new Runnable() {
            public void run() {
                final boolean svcOk = svcBinder() != null;
                final String csd = topPkg(AREA_CSD);
                final String psd = topPkg(AREA_PSD);
                final String full = topPkg(AREA_FULL);
                final StringBuilder sb = new StringBuilder();
                sb.append(svcOk ? "服务 geely_multi：可用" : "服务 geely_multi：不可用");
                sb.append("\n中控(1001) 顶层：" + nz(csd));
                sb.append("\n副驾(1002) 顶层：" + nz(psd));
                sb.append("\n全屏(1003) 顶层：" + nz(full));
                sb.append("\n当前状态：" + (full != null ? "全屏占用中" : "未全屏"));
                ui.post(new Runnable() {
                    public void run() { status.setText(sb.toString()); }
                });
            }
        }).start();
    }

    private static String nz(String s) {
        return (s == null || s.length() == 0) ? "—" : s;
    }
}
