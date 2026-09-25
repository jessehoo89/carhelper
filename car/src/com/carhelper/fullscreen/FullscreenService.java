package com.carhelper.fullscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「万物全屏」悬浮球服务（实现方式对照 LIGHTBOX MAX `FsFloatService` + `FsService` / ONE BOX `FsService`）。
 *
 * 为什么要悬浮窗（关键，别再省这一步）：
 *   `geely_multi.moveScreen2Screen(from, to, false)` 搬的是**该区域此刻正在显示的那个页面**。
 *   如果由我们自己的 Activity 触发，那一刻 1001 区顶层就是我们自己 → 只搬走自己，用户换个 App 就恢复。
 *   悬浮球（TYPE_APPLICATION_OVERLAY）是**非 Activity 图层**：它浮在目标 App 之上但不会抢走"顶层应用"，
 *   所以用户可以在任何 App 前台时点它，搬走的才是那个 App。
 *
 * 流程（照 LIGHTBOX `RunnableC0139c2`）：
 *   ① 点球时读 1002/1001 的顶层包 → 选一块有"可搬页面"的屏；
 *   ② **等页面稳定**：最多 ~2.6s、每 120ms 复核 topPkg 仍是那个包（防止切换过程中搬错）；
 *   ③ moveScreen(区, 1003)；
 *   ④ 等 800ms 复核 topPkg(1003) 是否等于目标包 → 不是就**回滚**并提示"该应用不支持全屏(系统限制)"；
 *   ⑤ 置 active + 常驻通知「全屏中 · <应用名>」+「退出全屏」按钮；退出时 moveScreen(1003, 原区)。
 */
public class FullscreenService extends Service {

    public static final String ACTION_TOGGLE = "com.carhelper.fullscreen.TOGGLE";
    public static final String ACTION_EXIT = "com.carhelper.fullscreen.EXIT";
    public static final String CH = "fs";
    private static final int NOTI_ID = 43;
    private static final long TAP_SLOP_MS = 220;      // 按下→抬起小于该时长视为点击
    private static final float TOUCH_SLOP_DP = 10f;

    /** 是否处于"全屏中"状态；curFrom 记录原区域，用于还原。 */
    public static volatile boolean active = false;
    public static volatile int curFrom = Geely.AREA_CSD;
    public static volatile String lastLabel = "";

    private WindowManager wm;
    private TextView ball;
    private WindowManager.LayoutParams ballLp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long downAt;
    private int downX, downY, ballX0, ballY0;
    private boolean moved;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Geely.bypassHiddenApi();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForeground(NOTI_ID, buildNote("车机助手 · 全屏悬浮球", "点悬浮球 = 全屏当前页面（再点一次还原）", false));
        addBall();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_EXIT.equals(a)) {
            exitFullscreen();
        } else if (ACTION_TOGGLE.equals(a)) {
            toggle();
        }
        if (ball == null) addBall();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (active) {
            // 服务被系统回收时不要把屏幕留在全屏状态
            new Thread(new Runnable() {
                public void run() {
                    Geely.moveScreen(Geely.AREA_FULL, curFrom);
                    active = false;
                }
            }).start();
        }
        if (ball != null) {
            try {
                wm.removeView(ball);
            } catch (Throwable ignored) {
            }
            ball = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 悬浮球

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addBall() {
        if (ball != null) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("没有悬浮窗权限：请在手机端「车机助手」重新安装/授权，或在开发者选项里给本应用开「显示在其他应用上层」");
            return;
        }
        ball = new TextView(this);
        ball.setText(active ? "退" : "全");
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(20);
        ball.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(active ? "#DA3633" : "#1F6FEB"));
        bg.setCornerRadius(dp(28));
        ball.setBackground(bg);
        ball.setAlpha(0.92f);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        ballLp = new WindowManager.LayoutParams(dp(56), dp(56), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballLp.x = dp(14);
        ballLp.y = dp(220);

        ball.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downAt = SystemClock.elapsedRealtime();
                        downX = (int) e.getRawX();
                        downY = (int) e.getRawY();
                        ballX0 = ballLp.x;
                        ballY0 = ballLp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) e.getRawX() - downX;
                        int dy = (int) e.getRawY() - downY;
                        if (Math.abs(dx) > dp(TOUCH_SLOP_DP) || Math.abs(dy) > dp(TOUCH_SLOP_DP)) moved = true;
                        if (moved) {
                            ballLp.x = ballX0 + dx;
                            ballLp.y = ballY0 + dy;
                            clampBall(clampY());
                            try {
                                wm.updateViewLayout(ball, ballLp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved && SystemClock.elapsedRealtime() - downAt < TAP_SLOP_MS) {
                            toggle();
                        } else {
                            clampBall(clampY());
                            try {
                                wm.updateViewLayout(ball, ballLp);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                }
                return false;
            }
        });
        try {
            wm.addView(ball, ballLp);
        } catch (Throwable t) {
            ball = null;
            toast("悬浮球添加失败：" + t.getMessage());
        }
    }

    private int clampY() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.max(0, Math.min(ballLp.y, dm.heightPixels - dp(56)));
    }

    private void clampBall(int y) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        ballLp.y = Math.max(0, Math.min(y, dm.heightPixels - dp(56)));
        ballLp.x = Math.max(0, Math.min(ballLp.x, dm.widthPixels - dp(56)));
    }

    private void setBallState() {
        ui.post(new Runnable() {
            public void run() {
                if (ball != null) {
                    ball.setText(active ? "退" : "全");
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(Color.parseColor(active ? "#DA3633" : "#1F6FEB"));
                    bg.setCornerRadius(dp(28));
                    ball.setBackground(bg);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 全屏 / 还原

    private void toggle() {
        if (active) {
            exitFullscreen();
        } else {
            applyFullscreen();
        }
    }

    private void applyFullscreen() {
        toast("正在全屏当前页面…");
        new Thread(new Runnable() {
            public void run() {
                // ① 选目标区：优先副驾(1002)，否则中控(1001)
                String p2 = Geely.topPkg(Geely.AREA_PSD);
                String p1 = Geely.topPkg(Geely.AREA_CSD);
                String self = getPackageName();
                int area;
                String pkg;
                if (Geely.isTransferable(p2, self)) {
                    area = Geely.AREA_PSD;
                    pkg = p2;
                } else if (Geely.isTransferable(p1, self)) {
                    area = Geely.AREA_CSD;
                    pkg = p1;
                } else {
                    toast("没找到可全屏的页面：先把要全屏的 App 打开，再点悬浮球");
                    return;
                }

                // ② 等页面稳定（最多 2.6s，每 120ms 复核），照 LIGHTBOX RunnableC0139c2
                long deadline = SystemClock.elapsedRealtime() + 2600;
                String now;
                do {
                    now = Geely.topPkg(area);
                    if (pkg.equals(now)) break;
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException ignored) {
                    }
                } while (SystemClock.elapsedRealtime() < deadline);
                if (!pkg.equals(now)) {
                    toast(area == Geely.AREA_PSD ? "副驾页面已变化，请重试" : "中控页面已变化，请重试");
                    return;
                }

                // ③ 搬屏
                if (!Geely.moveScreen(area, Geely.AREA_FULL)) {
                    toast("屏幕搬移失败（geely_multi 服务不可用）");
                    return;
                }

                // ④ 复核（800ms 后看全屏区顶层是否就是目标包），不符则回滚
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                }
                String top = Geely.topPkg(Geely.AREA_FULL);
                if (top == null || !top.equals(pkg)) {
                    Geely.moveScreen(Geely.AREA_FULL, area);
                    toast("该应用不支持全屏（系统限制）");
                    return;
                }

                String label = appLabel(pkg);
                active = true;
                curFrom = area;
                lastLabel = label;
                setBallState();
                updateNote(label);
                toast("已全屏：" + label + "（再点悬浮球还原）");
            }
        }).start();
    }

    private void exitFullscreen() {
        final int from = curFrom;
        new Thread(new Runnable() {
            public void run() {
                if (active) Geely.moveScreen(Geely.AREA_FULL, from);
                active = false;
                lastLabel = "";
                setBallState();
                updateNote(null);
                toast("已退出全屏");
            }
        }).start();
    }

    private String appLabel(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationInfo(pkg, 0)
                    .loadLabel(getPackageManager()));
        } catch (Throwable t) {
            return pkg;
        }
    }

    // ------------------------------------------------------------------ 常驻通知

    private Notification buildNote(String title, String text, boolean withExit) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "全屏", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_crop)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (withExit) {
            Intent exit = new Intent(this, FullscreenService.class).setAction(ACTION_EXIT);
            PendingIntent pi = PendingIntent.getService(this, 1, exit,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "退出全屏", pi).build());
        }
        return b.build();
    }

    private void updateNote(final String label) {
        ui.post(new Runnable() {
            public void run() {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                Notification n = label == null
                        ? buildNote("车机助手 · 全屏悬浮球", "点悬浮球 = 全屏当前页面（再点一次还原）", false)
                        : buildNote("全屏中 · " + label, "点「退出全屏」还原分屏显示", true);
                nm.notify(NOTI_ID, n);
            }
        });
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(FullscreenService.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
