package com.carhelper.fullscreen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 车机端「万物全屏」控制台。
 *
 * 真正的全屏动作由 {@link FullscreenService} 的**悬浮球**完成 ——
 * 因为 geely_multi.moveScreen2Screen 搬的是"该区域当前显示的页面"，
 * 只有非 Activity 的悬浮层才能在"别的 App 处于前台"时发起搬屏（否则只会搬走自己）。
 * 本页负责：显示状态、启动悬浮球、引导授予悬浮窗权限、手动还原。
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView log;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Geely.bypassHiddenApi();
        setContentView(buildUi());
        refresh();
        // 有悬浮窗权限就顺手把悬浮球拉起来（没有则等用户去授权）
        if (canOverlay()) {
            startService(new Intent(this, FullscreenService.class));
        }
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(String color, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(r));
        return d;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0E1216"));
        int p = dp(26);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("车机助手 · 万物全屏");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("悬浮球在全屏区上方常驻：在任何 App 里点它，就把那个 App 铺满全屏");
        sub.setTextColor(Color.parseColor("#7A8894"));
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9FE870"));
        status.setTextSize(14);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackground(rounded("#161C22", 12));
        root.addView(status);

        root.addView(gap(16));
        root.addView(btn("① 授予「显示在其他应用上层」", "不授权悬浮球就弹不出来", "#8957E5", new Runnable() {
            public void run() {
                say("正在打开悬浮窗权限设置…");
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Throwable t) {
                    say("无法打开设置页：" + t.getMessage()
                            + "\n可在手机端「车机助手」里用 adb 授权：\nappops set --user N " + getPackageName()
                            + " SYSTEM_ALERT_WINDOW allow");
                }
            }
        }));
        root.addView(gap(10));
        root.addView(btn("② 启动悬浮球", "启动后回到桌面/任意 App 都能点它", "#1F6FEB", new Runnable() {
            public void run() {
                if (!canOverlay()) {
                    say("还没授予悬浮窗权限，请先点 ①。");
                    return;
                }
                startService(new Intent(MainActivity.this, FullscreenService.class));
                say("悬浮球已启动：回桌面或切到任意 App，点屏幕边缘的圆形按钮即可全屏/还原。");
                refresh();
            }
        }));
        root.addView(gap(10));
        root.addView(btn("退出全屏 / 还原分屏", "等价于通知里的「退出全屏」", "#DA3633", new Runnable() {
            public void run() {
                startService(new Intent(MainActivity.this, FullscreenService.class)
                        .setAction(FullscreenService.ACTION_EXIT));
                say("已下发还原。");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        refresh();
                    }
                }, 900);
            }
        }));
        root.addView(gap(10));
        root.addView(btn("停止悬浮球", "停止后台服务（会先还原分屏）", "#30363D", new Runnable() {
            public void run() {
                startService(new Intent(MainActivity.this, FullscreenService.class)
                        .setAction(FullscreenService.ACTION_EXIT));
                stopService(new Intent(MainActivity.this, FullscreenService.class));
                say("悬浮球已停止。");
            }
        }));
        root.addView(gap(10));
        root.addView(btn("刷新状态", "查看服务与各区域顶层应用", "#30363D", new Runnable() {
            public void run() {
                refresh();
            }
        }));

        root.addView(gap(16));
        TextView adv = new TextView(this);
        adv.setText("说明：点「悬浮球」时若当前屏的顶层应用是桌面/系统界面，会提示先打开一个 App；"
                + "搬屏后系统会用 800ms 复核，不受支持的应用会自动回滚（这是系统限制，不是本工具的问题）。");
        adv.setTextColor(Color.parseColor("#586069"));
        adv.setTextSize(12);
        root.addView(adv);

        root.addView(gap(12));
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
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private Button btn(String text, String hint, String color, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(color, 12));
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        b.setContentDescription(hint);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private void say(final String s) {
        ui.post(new Runnable() {
            public void run() {
                log.setText(s);
            }
        });
    }

    // ------------------------------------------------------------------ 状态

    private void refresh() {
        new Thread(new Runnable() {
            public void run() {
                final boolean svcOk = Geely.svcBinder() != null;
                final String csd = Geely.topPkg(Geely.AREA_CSD);
                final String psd = Geely.topPkg(Geely.AREA_PSD);
                final String full = Geely.topPkg(Geely.AREA_FULL);
                final boolean ov = canOverlay();
                final StringBuilder sb = new StringBuilder();
                sb.append(svcOk ? "服务 geely_multi：可用" : "服务 geely_multi：不可用（本机可能不支持）");
                sb.append("\n悬浮窗权限：" + (ov ? "已授予" : "未授予（先点 ①）"));
                sb.append("\n全屏状态：" + (FullscreenService.active
                        ? "全屏中 · " + FullscreenService.lastLabel + "（原区 " + FullscreenService.curFrom + "）"
                        : "未全屏"));
                sb.append("\n中控(1001) 顶层：" + nz(csd));
                sb.append("\n副驾(1002) 顶层：" + nz(psd));
                sb.append("\n全屏(1003) 顶层：" + nz(full));
                ui.post(new Runnable() {
                    public void run() {
                        status.setText(sb.toString());
                    }
                });
            }
        }).start();
    }

    private static String nz(String s) {
        return (s == null || s.length() == 0) ? "—" : s;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
