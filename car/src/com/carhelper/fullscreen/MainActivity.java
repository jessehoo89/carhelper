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
    private TextView upUrl;
    private TextView upStatus;
    private UploadServer uploadServer;
    private String uploadToken = "";
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

        // ---------------- 手机上传安装（实验） ----------------
        root.addView(gap(16));
        LinearLayout upCard = new LinearLayout(this);
        upCard.setOrientation(LinearLayout.VERTICAL);
        upCard.setBackground(rounded("#161C22", 12));
        int upPad = dp(14);
        upCard.setPadding(upPad, upPad, upPad, upPad);
        TextView upTitle = new TextView(this);
        upTitle.setText("手机上传安装（实验）");
        upTitle.setTextColor(Color.WHITE);
        upTitle.setTextSize(18);
        upCard.addView(upTitle);
        TextView upDesc = new TextView(this);
        upDesc.setText("手机连同一热点 → 浏览器打开下面网址 → 选 APK 上传 → 车机走\n**系统安装流程**（第三方应用会在车机屏弹确认框）。\n"
                + "这条路不做任何绕过：车机自带的包名校验/白名单照样生效，正好用来实测它拦什么、放什么。");
        upDesc.setTextColor(Color.parseColor("#8B949E"));
        upDesc.setTextSize(12);
        upDesc.setPadding(0, dp(6), 0, dp(8));
        upCard.addView(upDesc);
        upUrl = new TextView(this);
        upUrl.setText("（点「启动上传服务」生成网址）");
        upUrl.setTextColor(Color.parseColor("#9FE870"));
        upUrl.setTextSize(16);
        upUrl.setTextIsSelectable(true);
        upUrl.setPadding(dp(10), dp(10), dp(10), dp(10));
        upUrl.setBackground(rounded("#0F1418", 8));
        upCard.addView(upUrl);
        upStatus = new TextView(this);
        upStatus.setTextColor(Color.parseColor("#8B949E"));
        upStatus.setTextSize(12);
        upStatus.setPadding(0, dp(8), 0, 0);
        upStatus.setText("未启动。");
        upCard.addView(upStatus);
        upCard.addView(gap(10));
        upCard.addView(btn("启动上传服务", "在车机上开一个本地 HTTP 服务，供手机浏览器上传", "#1F6FEB", new Runnable() {
            public void run() {
                startUpload();
            }
        }));
        upCard.addView(gap(8));
        upCard.addView(btn("停止上传服务", null, "#30363D", new Runnable() {
            public void run() {
                stopUpload();
            }
        }));
        upCard.addView(gap(8));
        upCard.addView(btn("刷新本机地址", null, "#30363D", new Runnable() {
            public void run() {
                if (uploadServer != null && uploadServer.isRunning()) {
                    upUrl.setText(uploadUrlText());
                } else {
                    upUrl.setText("（未启动）本机地址：" + localIp());
                }
            }
        }));
        root.addView(upCard);

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

    // ---------------------------------------------------------------- 手机上传安装

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> es = java.net.NetworkInterface.getNetworkInterfaces();
            String best = null;
            while (es != null && es.hasMoreElements()) {
                java.net.NetworkInterface ni = es.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a instanceof java.net.Inet4Address) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith("172.") || ip.startsWith("192.168.")) return ip;
                        if (best == null && !ip.startsWith("127.")) best = ip;
                    }
                }
            }
            return best == null ? "127.0.0.1" : best;
        } catch (Throwable t) {
            return "127.0.0.1";
        }
    }

    private String uploadUrlText() {
        return "http://" + localIp() + ":" + (uploadServer == null ? 8766 : uploadServer.port())
                + "/?token=" + uploadToken;
    }

    private void startUpload() {
        if (uploadServer != null && uploadServer.isRunning()) {
            say("上传服务已在运行：\n" + uploadUrlText());
            return;
        }
        uploadToken = randomToken();
        for (int port : new int[]{8766, 8767, 8768, 8769}) {
            try {
                uploadServer = new UploadServer(port, uploadToken, "车机助手 · 上传安装", new UploadServer.Handler() {
                    public String onUpload(java.io.InputStream body, long len, String name, boolean install) throws Exception {
                        return handleUpload(body, len, name, install);
                    }
                });
                uploadServer.start();
                upUrl.setText(uploadUrlText());
                upStatus.setText("已启动，端口 " + port + "。手机连车机热点后用浏览器打开上面的网址。");
                say("上传服务已启动：" + uploadUrlText());
                return;
            } catch (Throwable t) {
                U.log("端口 " + port + " 启动失败: " + t);
            }
        }
        upStatus.setText("启动失败：8766~8769 都被占用");
        say("上传服务启动失败：端口被占用");
    }

    private void stopUpload() {
        if (uploadServer != null) {
            uploadServer.stop();
        }
        upStatus.setText("已停止。");
        say("上传服务已停止");
    }

    private static String randomToken() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 4; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** 收到手机上传：落盘 → 可选走 PackageInstaller 安装。 */
    private String handleUpload(java.io.InputStream body, long len, String name, boolean install) throws Exception {
        java.io.File dir = new java.io.File(getCacheDir(), "uploads");
        dir.mkdirs();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        final java.io.File f = new java.io.File(dir, System.currentTimeMillis() + "-" + safe);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        long total = 0;
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = body.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
                if (len > 0 && total >= len) break;
            }
        } finally {
            try {
                fos.close();
            } catch (Exception ignored) {
            }
        }
        U.log("收到上传 " + name + " " + total + " 字节 → " + f);
        if (total <= 0) {
            upStatus.setText("收到空文件");
            return "没收到数据（0 字节）";
        }
        final String head = name + "（" + (total / 1048576) + " MB）";
        ui.post(new Runnable() {
            public void run() {
                upStatus.setText("已收到 " + head + "，正在提交安装…");
            }
        });
        if (!install) {
            String msg = "已保存到车机：" + f.getAbsolutePath() + "\n大小 " + total + " 字节（未安装）";
            upStatus.setText("已保存 " + head);
            return msg;
        }
        String r = ApkInstaller.install(this, f);
        upStatus.setText(head + " → " + r);
        return r + "\n文件：" + f.getAbsolutePath() + "（" + total + " 字节）";
    }

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
