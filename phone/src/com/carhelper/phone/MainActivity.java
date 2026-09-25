package com.carhelper.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车机助手（手机端）
 *
 * 卡片① 连接车机   —— 一键：检测热点 → 发现车机 → 连接 ADB → 识别设备与可用空间
 * 卡片② 安装应用   —— 自然语言选择目标屏幕（自动筛除不可用空间）
 * 卡片③ 授权管理   —— 给已装应用批量授权/取消授权到指定空间
 * 卡片④ 运行日志
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_APK = 1001;
    private static final String PREFS = "carhelper";
    private static final String K_AGREED = "risk_agreed_v1";

    // ADB RSA 密钥持久化：密钥不变，车机才不会每次连接都重弹授权框
    private static final String PREFS_KEYS = "carhelper_adbkey";
    private static final String K_PK = "pkcs8";
    private static final String K_PUB = "x509";

    // 领克/吉利多屏空间（见 car-hu-api-research/findings/07）
    private static final int U_CENTER = 12;   // 中控/主驾屏
    private static final int U_PASSENGER = 13; // 副驾屏（= 中控 id + 1）
    private static final int U_REAR_A = 0;    // 后排屏之一
    private static final int U_REAR_B = 10;   // 后排屏之二

    private TextView statusView;
    private TextView logView;
    private TextView spaceHint;
    private RadioGroup spaceGroup;
    private LinearLayout spaceBox;
    private LinearLayout appBox;
    private EditText appFilter;
    private EditText pkgInput;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** ADB 密钥存取（SharedPreferences）：一次授权，长期免弹框。 */
    private final AdbClient.KeyProvider keys = new AdbClient.KeyProvider() {
        public byte[] loadPrivate() { return prefsBytes(K_PK); }

        public byte[] loadPublic() { return prefsBytes(K_PUB); }

        public void save(byte[] priv, byte[] pub) {
            getSharedPreferences(PREFS_KEYS, MODE_PRIVATE).edit()
                    .putString(K_PK, Base64.encodeToString(priv, Base64.NO_WRAP))
                    .putString(K_PUB, Base64.encodeToString(pub, Base64.NO_WRAP))
                    .apply();
        }

        public void onAuthRequested() {
            log("⚠️ 车机屏幕上已弹出「允许调试」授权框 —— 请在车机屏上点「允许」。\n"
                    + "（本机密钥已保存，授权成功后以后连接不再弹框；若在车机里撤销过调试授权，则会再弹一次。）");
        }
    };

    private final AdbClient adb = new AdbClient(keys);
    private CarFinder.Wifi wifi;
    private String carIp;
    private boolean isRear;
    private boolean connected;
    private String selectedPkg;
    private int chosenUser = -1;
    /** 车机当前活跃（前台）用户空间号，-1 未知 */
    private int activeUser = -1;
    /** 车机原始用户列表（pm list users 原文），打印到日志便于核对标签 */
    private String rawUserList = "";
    /** 本次连接是否向车机提交了公钥（即是否触发了授权框） */
    private boolean authAsked = false;

    private final List<Integer> deviceUsers = new ArrayList<Integer>();
    private final List<String> deviceUserNames = new ArrayList<String>();
    private final List<String> allPkgs = new ArrayList<String>();
    /** 全部包（含系统/预置），仅用于关键字搜索 */
    private final List<String> allPkgsAny = new ArrayList<String>();
    /** 包名 → 它装在哪些用户空间（列表里显示，卸载时用来清残留） */
    private final java.util.Map<String, List<Integer>> pkgUsers = new java.util.LinkedHashMap<String, List<Integer>>();
    private final List<Integer> pickedSpaces = new ArrayList<Integer>();
    private final List<Integer> targets = new ArrayList<Integer>(); // 可选的安装目标用户空间

    // ================================================================== 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (agreed()) {
            boot();
        } else {
            showRiskDialog();
        }
    }

    private boolean agreed() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(K_AGREED, false);
    }

    private void boot() {
        setStatus("就绪");
        startKeepAlive();
        log("请确保：① 手机已连车机热点（Lynk&Co）② 车辆处于 P 挡、车机屏已唤醒。\n然后点「一键连接车机」。\n"
                + "首次连接：车机屏会弹「允许调试」授权框，点「允许」即可（本机会记住密钥，之后连接不再弹）。");
    }

    /** 首次启动的风险告知（同意后方可使用）。 */
    private void showRiskDialog() {
        String msg = "使用本工具前，请确认你已知悉以下风险：\n\n"
                + "【车机风险】\n本工具通过 ADB 向车机安装第三方应用。第三方应用可能存在兼容性问题，"
                + "影响车机系统稳定性。本工具本身不修改车机系统组件、不关闭任何校验器。\n\n"
                + "【驾驶风险】\n请在车辆停稳、挂 P 挡时操作。行车中操作或观看第三方应用内容会分散注意力，"
                + "可能造成交通事故，后果自负。\n\n"
                + "【保修风险】\n车辆厂家可能对“因第三方应用导致的故障”不予保修。本工具只做 ADB 装机与显示区域调整，"
                + "不越权、不破解，但装车行为本身仍可能引起保修争议，请自行评估。\n\n"
                + "【数据与隐私】\n本工具不联网、不上传任何数据，所有操作都在你手机与车机之间完成。\n\n"
                + "点击“我已阅读并同意”表示你已理解并自行承担上述风险。";
        new AlertDialog.Builder(this)
                .setTitle("使用前请阅读")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("我已阅读并同意", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(K_AGREED, true).apply();
                        boot();
                    }
                })
                .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        finish();
                    }
                })
                .show();
    }

    // ================================================================== UI 构建

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bg(String color, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(r));
        return d;
    }

    /** 页面：0=主页 1=安装 2=授权管理 3=日志 */
    private static final int PAGE_HOME = 0, PAGE_INSTALL = 1, PAGE_MANAGE = 2, PAGE_LOG = 3;
    private int currentPage = PAGE_HOME;
    private View[] pages = new View[4];
    private TextView installStatus, manageStatus, installSummary, manageSummary, homeLogLine;

    private View buildUi() {
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setBackgroundColor(Color.parseColor("#0B0E11"));
        for (int i = 0; i < 4; i++) {
            pages[i] = null;
        }
        pages[PAGE_HOME] = buildHomePage();
        pages[PAGE_INSTALL] = buildInstallPage();
        pages[PAGE_MANAGE] = buildManagePage();
        pages[PAGE_LOG] = buildLogPage();
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == PAGE_HOME ? View.VISIBLE : View.GONE);
            host.addView(pages[i], new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        }
        return host;
    }

    private void showPage(int page) {
        currentPage = page;
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
        }
        if (page == PAGE_HOME) refresh();
    }

    @Override
    public void onBackPressed() {
        if (currentPage != PAGE_HOME) {
            showPage(PAGE_HOME);
        } else {
            super.onBackPressed();
        }
    }

    private ScrollView page() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.parseColor("#0B0E11"));
        return sc;
    }

    private LinearLayout pageRoot(ScrollView sc) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sc.addView(root);
        return root;
    }

    /** 子页顶栏：返回 + 标题 */
    private void pageHeader(LinearLayout root, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button back = mkBtn("‹ 返回", "#30363D", new Runnable() {
            public void run() { showPage(PAGE_HOME); }
        });
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(back);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(21);
        t.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        row.addView(t, lp);
        root.addView(row);
        root.addView(gap(12));
    }

    /** 执行状态区（每个功能页自己一块，简要显示"正在做什么/上次结果"） */
    private TextView statusBox(String initial) {
        TextView tv = new TextView(this);
        tv.setText(initial);
        tv.setTextColor(Color.parseColor("#9FE870"));
        tv.setTextSize(13);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackground(bg("#151A20", 12));
        return tv;
    }

    // ---------------------------------------------------------------- 主页

    private View buildHomePage() {
        ScrollView sc = page();
        LinearLayout root = pageRoot(sc);

        TextView t = new TextView(this);
        t.setText("车机助手");
        t.setTextColor(Color.WHITE);
        t.setTextSize(25);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        TextView s = new TextView(this);
        s.setText("连车机热点 · ADB 装机 · 一键全屏");
        s.setTextColor(Color.parseColor("#7A8894"));
        s.setTextSize(12);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(4), 0, dp(12));
        root.addView(s);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9FE870"));
        statusView.setTextSize(14);
        statusView.setPadding(dp(14), dp(14), dp(14), dp(14));
        statusView.setBackground(bg("#151A20", 12));
        root.addView(statusView);

        // ---- 连接车机 ----
        LinearLayout c1 = card(root, "① 连接车机", "一键完成：检测热点 → 发现车机 → 连接 ADB（密钥已保存，授权一次长期免弹）");
        c1.addView(btn("一键连接车机", "#1F6FEB", new Runnable() {
            public void run() { oneClickConnect(); }
        }));
        c1.addView(gap(8));
        c1.addView(btn("重连车机（快速，跳过扫描）", "#30363D", new Runnable() {
            public void run() {
                setStatus("正在重连 …");
                new Thread(new Runnable() {
                    public void run() {
                        boolean ok = reconnect();
                        setStatus(ok ? "已重连 " + carIp + ":5555" : "重连失败，请点「一键连接车机」");
                    }
                }).start();
            }
        }));

        // ---- 功能入口 ----
        LinearLayout c2 = card(root, "② 功能", "各功能独立成页，进入后在页内看执行状态");
        c2.addView(entry("安装应用到车机", "选 APK → 装到指定屏幕空间", "#238636", new Runnable() {
            public void run() { showPage(PAGE_INSTALL); }
        }));
        installSummary = summaryLine("尚未安装过。");
        c2.addView(installSummary);
        c2.addView(gap(10));
        c2.addView(entry("应用授权空间管理", "给已装应用授权/取消授权到指定空间", "#8957E5", new Runnable() {
            public void run() { showPage(PAGE_MANAGE); }
        }));
        manageSummary = summaryLine("尚未加载列表。");
        c2.addView(manageSummary);
        c2.addView(gap(10));
        c2.addView(entry("运行日志", "复制日志发给作者排障", "#30363D", new Runnable() {
            public void run() { showPage(PAGE_LOG); }
        }));
        homeLogLine = summaryLine("（无日志）");
        c2.addView(homeLogLine);
        return sc;
    }

    private Button entry(String text, String desc, String color, final Runnable action) {
        Button b = mkBtn(text, color, action);
        b.setTextSize(17);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        b.setContentDescription(desc);
        return b;
    }

    private TextView summaryLine(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Color.parseColor("#8B949E"));
        tv.setTextSize(12);
        tv.setPadding(dp(4), dp(-4) + dp(6), dp(4), 0);
        return tv;
    }

    // ---------------------------------------------------------------- 安装页

    private View buildInstallPage() {
        ScrollView sc = page();
        LinearLayout root = pageRoot(sc);
        pageHeader(root, "安装应用到车机");

        installStatus = statusBox("就绪。");
        root.addView(installStatus);

        LinearLayout c = card(root, "目标屏幕空间", "按当前热点自动筛除不可用空间");
        spaceHint = new TextView(this);
        spaceHint.setTextColor(Color.parseColor("#7A8894"));
        spaceHint.setTextSize(12);
        spaceHint.setPadding(0, dp(6), 0, dp(6));
        spaceHint.setText("连接后自动识别可用屏幕。");
        c.addView(spaceHint);
        spaceGroup = new RadioGroup(this);
        spaceGroup.setOrientation(RadioGroup.VERTICAL);
        c.addView(spaceGroup);

        LinearLayout c2 = card(root, "安装", null);
        c2.addView(btn("选择本地 APK 安装", "#238636", new Runnable() {
            public void run() { pickApk(); }
        }));
        c2.addView(gap(8));
        c2.addView(btn("一键安装「全屏工具」到车机", "#238636", new Runnable() {
            public void run() { installBuiltin(); }
        }));
        c2.addView(gap(8));
        c2.addView(btn("授权（悬浮窗 + 安装未知应用）+ 启动悬浮球", "#30363D", new Runnable() {
            public void run() {
                if (!ensureConnected()) return;
                final int uid = chosenUser >= 0 ? chosenUser : 0;
                setStatus("正在配置万物全屏（" + spaceLabel(uid) + "）…");
                new Thread(new Runnable() {
                    public void run() {
                        postConfigFullscreen(uid);
                        setStatus("万物全屏已配置（" + spaceLabel(uid) + "）");
                    }
                }).start();
            }
        }));
        c2.addView(gap(8));
        c2.addView(btn("查看运行日志", "#30363D", new Runnable() {
            public void run() { showPage(PAGE_LOG); }
        }));
        return sc;
    }

    // ---------------------------------------------------------------- 授权管理页

    private View buildManagePage() {
        ScrollView sc = page();
        LinearLayout root = pageRoot(sc);
        pageHeader(root, "应用授权空间管理");

        manageStatus = statusBox("就绪。点「加载空间与应用列表」开始。");
        root.addView(manageStatus);

        LinearLayout c3 = card(root, "空间与应用", "空间可多选；应用点选一个（可搜索）");
        c3.addView(btn("加载空间与应用列表", "#8957E5", new Runnable() {
            public void run() { loadSpacesAndApps(); }
        }));
        c3.addView(gap(10));
        TextView st = new TextView(this);
        st.setText("空间（可多选）");
        st.setTextColor(Color.parseColor("#8B949E"));
        st.setTextSize(12);
        c3.addView(st);
        spaceBox = new LinearLayout(this);
        spaceBox.setOrientation(LinearLayout.VERTICAL);
        c3.addView(spaceBox);
        c3.addView(gap(10));
        TextView at = new TextView(this);
        at.setText("应用（点选一个，可搜索；输关键字时会连预置/残留包一起搜）");
        at.setTextColor(Color.parseColor("#8B949E"));
        at.setTextSize(12);
        c3.addView(at);
        appFilter = new EditText(this);
        appFilter.setHint("筛选包名关键字");
        appFilter.setInputType(InputType.TYPE_CLASS_TEXT);
        appFilter.setTextColor(Color.WHITE);
        appFilter.setHintTextColor(Color.parseColor("#586069"));
        appFilter.setBackground(bg("#0F1418", 10));
        appFilter.setPadding(dp(12), dp(10), dp(12), dp(10));
        appFilter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (appBox != null) renderApps(s.toString());
            }
            public void afterTextChanged(android.text.Editable s) { }
        });
        c3.addView(appFilter);
        appBox = new LinearLayout(this);
        appBox.setOrientation(LinearLayout.VERTICAL);
        c3.addView(appBox);

        LinearLayout c4 = card(root, "操作", null);
        c4.addView(btn("授权到选中空间", "#238636", new Runnable() {
            public void run() { applySpaces(true); }
        }));
        c4.addView(gap(8));
        c4.addView(btn("从选中空间取消授权", "#DA3633", new Runnable() {
            public void run() { applySpaces(false); }
        }));
        c4.addView(gap(8));
        c4.addView(btn("彻底卸载（所有空间，清残留）", "#8B1E1E", new Runnable() {
            public void run() { purgePackage(null); }
        }));
        c4.addView(gap(8));
        c4.addView(btn("诊断：该应用在各空间的安装状态", "#1F6FEB", new Runnable() {
            public void run() { dumpUsers(); }
        }));
        c4.addView(gap(8));
        c4.addView(btn("刷新主驾桌面（重启 launcher）", "#8957E5", new Runnable() {
            public void run() { refreshLauncher(); }
        }));
        c4.addView(gap(10));
        TextView pt = new TextView(this);
        pt.setText("列表里搜不到时，直接输包名（如 com.sumsg.musichub）");
        pt.setTextColor(Color.parseColor("#8B949E"));
        pt.setTextSize(12);
        c4.addView(pt);
        pkgInput = new EditText(this);
        pkgInput.setHint("包名，如 com.sumsg.musichub");
        pkgInput.setInputType(InputType.TYPE_CLASS_TEXT);
        pkgInput.setTextColor(Color.WHITE);
        pkgInput.setHintTextColor(Color.parseColor("#586069"));
        pkgInput.setBackground(bg("#0F1418", 10));
        pkgInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        c4.addView(pkgInput);
        c4.addView(gap(8));
        c4.addView(btn("诊断 + 彻底卸载这个包名", "#8B1E1E", new Runnable() {
            public void run() {
                String v = pkgInput.getText().toString().trim();
                if (v.length() == 0) {
                    log("请先输入包名。");
                    return;
                }
                purgePackage(v);
            }
        }));
        c4.addView(gap(8));
        c4.addView(btn("查看运行日志", "#30363D", new Runnable() {
            public void run() { showPage(PAGE_LOG); }
        }));
        return sc;
    }

    // ---------------------------------------------------------------- 日志页

    private View buildLogPage() {
        ScrollView sc = page();
        LinearLayout root = pageRoot(sc);
        pageHeader(root, "运行日志");

        LinearLayout c = card(root, "日志", "长日志请点「复制日志」粘给作者");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cp = mkBtn("复制日志", "#30363D", new Runnable() {
            public void run() { copyLog(); }
        });
        cp.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button cl = mkBtn("清空日志", "#30363D", new Runnable() {
            public void run() { clearLog(); }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(8);
        cl.setLayoutParams(lp2);
        row.addView(cp);
        row.addView(cl);
        c.addView(row);
        c.addView(gap(8));
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#8B949E"));
        logView.setTextSize(12);
        logView.setText("（空）");
        c.addView(logView);
        return sc;
    }

    private LinearLayout card(LinearLayout root, String title, String desc) {
        root.addView(gap(12));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(bg("#151A20", 14));
        int cp = dp(14);
        c.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        c.setLayoutParams(lp);

        TextView tt = new TextView(this);
        tt.setText(title);
        tt.setTextColor(Color.WHITE);
        tt.setTextSize(17);
        c.addView(tt);
        if (desc != null) {
            TextView dd = new TextView(this);
            dd.setText(desc);
            dd.setTextColor(Color.parseColor("#7A8894"));
            dd.setTextSize(12);
            dd.setPadding(0, dp(4), 0, dp(10));
            c.addView(dd);
        } else {
            c.addView(gap(8));
        }
        root.addView(c);
        return c;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private Button btn(String text, String color, final Runnable action) {
        Button b = mkBtn(text, color, action);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** 与 btn() 相同，但不预设布局参数（供横向排布用）。 */
    private Button mkBtn(String text, String color, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackground(bg(color, 12));
        b.setPadding(dp(12), dp(13), dp(12), dp(13));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return b;
    }

    /** 复制当前日志到剪贴板（方便把长日志发出来）。 */
    private void copyLog() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("carhelper-log", logBuf.toString()));
            log("日志已复制到剪贴板（共 " + logBuf.length() + " 字符），粘到聊天里发我即可。");
        } catch (Throwable t) {
            log("复制失败：" + t.getMessage());
        }
    }

    /** 主页状态刷新：只更新主页那块状态，不覆盖各功能页的执行状态。 */
    private void refresh() {
        final String st;
        if (connected && adb.isConnected()) {
            st = "已连接 " + nz(carIp) + ":5555\n"
                    + (isRear ? "设备类型：后排娱乐屏" : "设备类型：前排（中控/主驾）屏") + "\n"
                    + "当前活跃空间：" + (activeUser >= 0 ? activeUser + " · " + spaceLabel(activeUser) : "未知") + "\n"
                    + "可安装空间：" + spaceListText();
        } else {
            st = "未连接车机。\n请先把手机连上车机热点，再点「一键连接车机」。";
        }
        ui.post(new Runnable() {
            public void run() {
                if (statusView != null) statusView.setText(st);
            }
        });
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            public void run() {
                if (statusView != null) statusView.setText(s);
                if (installStatus != null) installStatus.setText(s);
                if (manageStatus != null) manageStatus.setText(s);
            }
        });
    }

    /** 主页入口下方的摘要行（保留上次结果，方便不进去也知道情况）。 */
    private void setSummary(final TextView tv, final String s) {
        if (tv == null) return;
        ui.post(new Runnable() {
            public void run() { tv.setText(s); }
        });
    }

    private final StringBuilder logBuf = new StringBuilder();
    private long logT0 = System.currentTimeMillis();

    /** 追加一条日志（保留最近若干行，便于回看整条链路）。 */
    private void log(final String s) {
        ui.post(new Runnable() {
            public void run() {
                if (logBuf.length() > 0) logBuf.append("\n");
                logBuf.append("[+").append((System.currentTimeMillis() - logT0) / 1000).append("s] ").append(s);
                if (logBuf.length() > 12000) logBuf.delete(0, logBuf.length() - 10000);
                if (logView != null) logView.setText(logBuf.toString());
                if (homeLogLine != null) {
                    String last = s.indexOf('\n') > 0 ? s.substring(0, s.indexOf('\n')) : s;
                    homeLogLine.setText("最近：" + last);
                }
            }
        });
    }

    private void clearLog() {
        ui.post(new Runnable() {
            public void run() {
                logBuf.setLength(0);
                logT0 = System.currentTimeMillis();
                logView.setText("（空）");
            }
        });
    }

    private byte[] prefsBytes(String k) {
        String v = getSharedPreferences(PREFS_KEYS, MODE_PRIVATE).getString(k, null);
        if (v == null) return null;
        try {
            return Base64.decode(v, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================== ① 一键连接

    private void oneClickConnect() {
        setStatus("正在连接车机 …");
        clearLog();
        log("步骤 1/4 检测 WiFi 网络 …");
        new Thread(new Runnable() {
            public void run() {
                // 1) WiFi
                wifi = CarFinder.currentWifi(MainActivity.this);
                if (wifi == null) {
                    fail("【未检测到 WiFi】\n请先把手机连上车机热点（SSID 通常为 Lynk&Co），再重试。");
                    return;
                }
                stepLog("步骤 1/4 ✅ WiFi：" + wifi.localIp + "，网关 " + nz(wifi.gateway));

                // 2) 发现
                stepLog("步骤 2/4 探测车机 ADB 端口（先探网关，未命中再扫网段）…");
                List<String> hits = CarFinder.discover(MainActivity.this, wifi, 450);
                if (hits.isEmpty()) {
                    fail("【未发现车机 ADB】\n在 " + netOf(wifi.localIp) + " 网段没找到开着 5555 端口的设备。\n\n"
                            + "可能原因：\n"
                            + "· 手机连的不是车机热点（当前 IP " + wifi.localIp + "）\n"
                            + "· 车机 ADB 调试未开启\n"
                            + "· 车机屏幕处于休眠，请唤醒后重试");
                    return;
                }
                stepLog("步骤 2/4 ✅ 发现 " + hits.size() + " 台设备：" + hits);

                // 3) 连接
                String err = null;
                for (String ip : hits) {
                    try {
                        stepLog("步骤 3/4 连接 " + ip + ":5555 …（首次连接车机屏会弹授权框，点「允许」即可）");
                        adb.close();
                        authAsked = adb.connect(ip, 5555, 6000, 60000);
                        carIp = ip;
                        connected = true;
                        err = null;
                        break;
                    } catch (Exception e) {
                        err = e.getMessage();
                        stepLog("   连接 " + ip + " 失败：" + err);
                    }
                }
                if (!connected) {
                    fail("【ADB 连接失败】\n" + nz(err) + "\n\n"
                            + "如果车机屏幕上弹出了“允许调试/允许连接”的授权框，请点“允许”"
                            + "（建议勾选“始终允许”），然后重新点「一键连接车机」。");
                    return;
                }
                stepLog("认证轨迹：" + adb.authTrace.toString());
                stepLog("步骤 3/4 ✅ 已连接（本机 ADB 密钥指纹 " + adb.keyFingerprint() + "）"
                        + (authAsked ? " — 本次已向车机提交公钥，若车机屏弹框请点「允许」" : " — 密钥已被车机认可，无需再授权"));

                // 4) 识别设备与可用空间
                stepLog("步骤 4/4 识别设备类型与可用屏幕空间 …");
                try {
                    probeDevice();
                } catch (Exception e) {
                    fail("【已连上 ADB，但设备信息读取失败】\n" + e.getMessage());
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        setStatus("已连接 " + carIp + ":5555\n"
                                + (isRear ? "设备类型：后排娱乐屏" : "设备类型：前排（中控/主驾）屏")
                                + "\n当前活跃空间：" + (chosenUser >= 0 ? chosenUser : "未知"));
                        renderSpaceChoices();
                    }
                });
            }
        }).start();
    }

    /** 有耗时操作（安装/大量命令）时置位，心跳线程不去打扰同一条 socket。 */
    private volatile boolean busy = false;
    private volatile boolean keepAliveRun = true;

    /** 所有 shell 命令统一入口：**断线时自动快速重连并重试一次**。 */
    private String sh(String cmd) throws IOException {
        return sh(cmd, -1);
    }

    /** 串行化所有 ADB 访问（心跳与用户操作不能同时读写同一条 socket）。 */
    private final Object adbLock = new Object();

    private String sh(String cmd, int timeoutMs) throws IOException {
        synchronized (adbLock) {
            try {
                return timeoutMs > 0 ? adb.shell(cmd, timeoutMs) : adb.shell(cmd);
            } catch (IOException e) {
                log("连接被中断（" + nz(e.getMessage()) + "）→ 自动重连并重试 …");
                if (!reconnect()) throw new IOException("重连失败：" + e.getMessage());
                return timeoutMs > 0 ? adb.shell(cmd, timeoutMs) : adb.shell(cmd);
            }
        }
    }

    /**
     * 快速重连：**优先直连上次成功的 IP**（车机热点 IP 基本不变），失败才走完整发现流程。
     * 密钥已持久化，重连不会再弹授权框。
     */
    private boolean reconnect() {
        synchronized (adbLock) {
            if (connected && adb.isConnected()) {
                return true;
            }
            connected = false;
            // 关键：不再信任 isConnected()，先无条件把旧连接彻底关掉（断链后它仍可能报 true，导致"假重连"）
            try {
                adb.close();
            } catch (Throwable ignored) {
            }
            try {
                if (carIp != null && alive(carIp)) {
                    return true;
                }
                wifi = CarFinder.currentWifi(this);
                if (wifi == null) {
                    log("重连失败：当前不是 WiFi（请确认仍连着车机热点）");
                    return false;
                }
                List<String> hits = CarFinder.discover(this, wifi, 450);
                for (String ip : hits) {
                    if (alive(ip)) return true;
                }
            } catch (Exception e) {
                log("重连失败：" + nz(e.getMessage()));
            }
            log("重连失败：车机 ADB 暂时不可达（可稍后点「重连车机」）");
            return false;
        }
    }

    /** 真连一次并**用 echo 实测验证**：只有命令跑通才算连上（避免再出现"重连了却 Broken pipe"）。 */
    private boolean alive(String ip) {
        try {
            authAsked = adb.connect(ip, 5555, 5000, 60000);
            adb.shell("echo k", 15000);
            carIp = ip;
            connected = true;
            log("已重连 " + ip + ":5555（实测通过；轨迹：" + adb.authTrace.toString() + "）");
            return true;
        } catch (Exception e) {
            log("连 " + ip + " 未通过实测：" + nz(e.getMessage()));
            try {
                adb.close();
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /** 心跳保活：空闲时每 45 秒轻量 ping 一次，断了就自动重连（车机休眠/热点抖动导致的断链常见）。 */
    private void startKeepAlive() {
        new Thread(new Runnable() {
            public void run() {
                while (keepAliveRun) {
                    try {
                        Thread.sleep(45000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (busy || !connected) continue;
                    try {
                        sh("echo k", 15000);   // 失败会走 sh() 内部的重连+重试
                    } catch (Exception e) {
                        log("[心跳] ping 失败（" + nz(e.getMessage()) + "）→ 自动重连 …");
                        connected = false;
                        reconnect();
                    }
                }
            }
        }, "adb-keepalive").start();
    }

    private void fail(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                setStatus("连接失败");
                log(msg);
            }
        });
    }

    private void stepLog(final String s) {
        log(s);   // 必须走 log()：原来这里直接 setText 会覆盖整块日志，导致中间步骤看不见
    }

    /** 采集设备特征 + 用户空间，判定前排/后排，筛出可用安装空间。 */
    private void probeDevice() throws Exception {
        String out = sh(
                "printf 'SERIAL=';getprop ro.serialno;"
                        + "printf ';MODEL=';getprop ro.product.model;"
                        + "printf ';PRODUCT=';getprop ro.product.name;"
                        + "printf ';DEVICE=';getprop ro.product.device;"
                        + "printf ';USER=';am get-current-user;"
                        + "printf ';USERS=';pm list users | tr '\\n' '|';"
                        + "printf ';REAR=';pm path --user 0 com.desaysv.launcher 2>/dev/null | head -1");

        String rearSeg = seg(out, "REAR=");
        isRear = rearSeg.contains("package:");

        rawUserList = seg(out, "USERS=").trim();
        parseUsers(rawUserList);
        activeUser = parseIntSafe(seg(out, "USER="));

        String cur = String.valueOf(activeUser >= 0 ? activeUser : 0);

        // 目标空间：前排 = 中控/主驾 + 副驾；后排 = 左(0) + 右(10)
        targets.clear();
        if (isRear) {
            // 后排娱乐屏：空间号因车型/固件而异（100 表示"后排"，部分固件下真实 user 需动态解析）
            // → 以实机检测为准：优先 100 / 101，其次 0 / 10，最后列出全部真实空间
            int[] pref = {100, 101, U_REAR_A, U_REAR_B};
            for (int pi = 0; pi < pref.length; pi++) addTargetIfExists(pref[pi]);
            if (targets.isEmpty()) {
                for (int i = 0; i < deviceUsers.size(); i++) targets.add(Integer.valueOf(deviceUsers.get(i)));
            }
        } else {
            addTargetIfExists(U_CENTER);
            addTargetIfExists(U_PASSENGER);
        }
        if (targets.isEmpty()) {
            // 兜底：用当前活跃空间
            targets.add(Integer.valueOf(activeUser >= 0 ? activeUser : 0));
        }
        chosenUser = targets.get(0).intValue();
        log("车机：" + nz(seg(out, "MODEL=")) + "（序列号 " + nz(seg(out, "SERIAL=")) + "，"
                + (isRear ? "后排娱乐屏" : "前排（中控/主驾）屏") + "）\n"
                + "用户空间原始信息：" + nz(rawUserList) + "\n"
                + "当前活跃空间：" + (activeUser >= 0 ? activeUser : "未知"));
    }

    /** 解析 `pm list users` 输出（UserInfo{12:Co客9ZJ57K:...}）。 */
    private void parseUsers(String usersSeg) {
        deviceUsers.clear();
        deviceUserNames.clear();
        Matcher m = Pattern.compile("UserInfo\\{(\\d+):([^:}]*)[:}]").matcher(usersSeg);
        while (m.find()) {
            deviceUsers.add(Integer.valueOf(Integer.parseInt(m.group(1))));
            deviceUserNames.add(m.group(1) + " · " + m.group(2));
        }
    }

    private void addTargetIfExists(int uid) {
        if (deviceUsers.contains(Integer.valueOf(uid))) {
            targets.add(Integer.valueOf(uid));
        }
    }

    private void renderSpaceChoices() {
        spaceGroup.removeAllViews();
        String head = isRear ? "当前连接：后排娱乐屏热点" : "当前连接：前排（中控/主驾）热点";
        if (carIp != null) head += "（" + carIp + "）";
        spaceHint.setText(head + "\n可安装到：" + spaceListText()
                + (activeUser >= 0 ? "\n车机当前活跃空间：" + activeUser + " · " + spaceLabel(activeUser) : ""));
        for (int i = 0; i < targets.size(); i++) {
            final int uid = targets.get(i).intValue();
            RadioButton rb = new RadioButton(this);
            rb.setText(spaceRowTitle(uid));
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(14);
            rb.setId(2000 + i);
            rb.setTag(Integer.valueOf(uid));
            if (uid == chosenUser) rb.setChecked(true);
            rb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    chosenUser = ((Integer) v.getTag()).intValue();
                    setStatus("已选择安装位置：" + spaceRowTitle(chosenUser));
                }
            });
            spaceGroup.addView(rb);
        }
        // 不可用空间说明
        TextView note = new TextView(this);
        note.setTextColor(Color.parseColor("#586069"));
        note.setTextSize(11);
        note.setPadding(0, dp(4), 0, 0);
        StringBuilder sb = new StringBuilder("已列出本设备上真实存在的空间（原始信息：" );
        sb.append(deviceUserNames.isEmpty() ? "未读到" : join(deviceUserNames));
        sb.append("）");
        note.setText(sb.toString());
        spaceGroup.addView(note);
    }

    /** 「12 · 主驾/中控屏」形式的空间标题。 */
    private String spaceRowTitle(int uid) {
        return uid + " · " + spaceLabel(uid) + (uid == activeUser ? "  ✱当前活跃" : "");
    }

    private String spaceListText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            int uid = targets.get(i).intValue();
            if (sb.length() > 0) sb.append("、");
            sb.append(uid).append(" · ").append(spaceLabel(uid));
        }
        return sb.length() == 0 ? "（未识别到可用空间）" : sb.toString();
    }

    /** 取车机上该空间的原始名（Co客9ZJ57K / 12_clone / GUEST …）。 */
    private String rawNameOf(int uid) {
        for (int i = 0; i < deviceUsers.size(); i++) {
            if (deviceUsers.get(i).intValue() == uid) {
                String s = deviceUserNames.get(i);
                int p = s.indexOf(" · ");
                return p < 0 ? s : s.substring(p + 3);
            }
        }
        return "";
    }

    /**
     * 用户空间号 → 屏幕名（界面提示用）。
     *
     * 依据：
     *  · 领克/吉利多屏约定（LIGHTBOX 先例）：12 主驾/中控、13 副驾、100 后排娱乐屏、101 中控+副驾
     *  · 0 = 司机基础空间、10 = 访客（GUEST）
     *  · 副驾/移动空间常表现为「主空间的克隆」，车机里名为 "<主空间>_clone"（如 12_clone = 13）
     *  · 当前活跃空间（am get-current-user）视为主驾/中控屏
     * 标签属推断，界面同时显示设备原始空间名，便于你自行判断。
     */
    private String spaceLabel(int uid) {
        String raw = rawNameOf(uid);
        boolean clone = raw.endsWith("_clone");
        int base = -1;
        if (clone) {
            try {
                base = Integer.parseInt(raw.substring(0, raw.indexOf('_')));
            } catch (Exception ignored) {
            }
        }
        if (uid == 100) return "后排娱乐屏";
        if (uid == 101) return "中控 + 副驾";
        if (uid == 0) return "主驾（司机基础空间）";
        if (uid == 10) return "访客空间（GUEST）";
        if (clone) {
            if (base == 12 || (base == activeUser && activeUser >= 10)) {
                return "副驾屏（" + base + " 主驾/中控空间的克隆）";
            }
            if (base == 10) return "访客的克隆空间（移动空间）";
            return base + " 的克隆空间";
        }
        if (uid == 12 || uid == activeUser) return "主驾 / 中控屏";
        return "用户空间 " + uid;
    }

    // ================================================================== ② 安装

    private void pickApk() {
        if (!ensureConnected()) return;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("application/vnd.android.package-archive");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择 APK"), REQ_PICK_APK);
        } catch (Exception e) {
            log("无法打开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_APK && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        install(new StreamFactory() {
                            public InputStream open() throws IOException {
                                return getContentResolver().openInputStream(uri);
                            }
                        }, displayName(uri), false, sizeOf(uri));
                    } catch (Exception e) {
                        log("读取所选文件失败：" + e.getMessage());
                    }
                }
            }).start();
        }
    }

    private void installBuiltin() {
        if (!ensureConnected()) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    install(new StreamFactory() {
                        public InputStream open() throws IOException {
                            return getAssets().open("carhelper-fullscreen.apk");
                        }
                    }, "全屏工具", true, assetSize("carhelper-fullscreen.apk"));
                } catch (Exception e) {
                    log("读取内置全屏工具失败：" + e.getMessage());
                }
            }
        }).start();
    }

    /** 重新打开待推送数据源（sync 失败回退 shell 时需要第二条独立流）。 */
    private interface StreamFactory {
        InputStream open() throws IOException;
    }

    /**
     * 安装编排：三种方式依次尝试，任一种成功即返回。
     *
     *  ⚠️ 血泪教训（v1.0.2）：
     *   · 设备的 sync 服务对 SEND 请求**不回任何东西**（AOSP daemon 只在 DONE 后写一次 OKAY），
     *     旧版每步都等应答 → 干等 60s 报「sync 推送失败: Read timed out」；
     *   · 服务流结束时设备也可能不发 CLSE → 旧版等到超时误报「安装超时」。
     *  所以现在**不以应答判定成败，一律以车机上文件大小 / pm install 输出为准**。
     */
    private void install(StreamFactory sf, String label, boolean launch, long knownSize) {
        busy = true;
        final long t0 = System.currentTimeMillis();
        String tmp = "/data/local/tmp/carhelper-" + System.currentTimeMillis() + ".apk";
        final int uid = chosenUser >= 0 ? chosenUser : 0;
        log("===== 安装「" + label + "」→ " + spaceRowTitle(uid)
                + (knownSize > 0 ? "（" + (knownSize / 1024) + " KB）" : "（大小未知）") + " =====");

        final long[] lastLog = new long[]{0};
        AdbClient.Progress prog = new AdbClient.Progress() {
            public void onBytes(long n) {
                if (n - lastLog[0] >= 10 * 1024 * 1024) {
                    lastLog[0] = n;
                    log("已传输 " + (n / 1048576) + " MB …");
                }
            }
        };

        String out = null;
        final StringBuilder allErr = new StringBuilder();
        java.io.File staged = null;
        try {
            // ---------- 方式 0：把精确字节数拿到手（流式安装必须知道 size；内置资产被 zip 压缩过，openFd 取不到长度）
            long size = knownSize;
            StreamFactory src = sf;
            if (size <= 0) {
                log("[0] 文件大小未知 → 先缓存到手机本地量准字节数");
                staged = new java.io.File(getCacheDir(), "carhelper-stage.apk");
                size = stageLocally(sf, staged);
                final java.io.File sf2 = staged;
                src = new StreamFactory() {
                    public InputStream open() throws IOException {
                        return new java.io.FileInputStream(sf2);
                    }
                };
                log("[0] 本地缓存完成：" + size + " 字节");
            }
            knownSize = size;

            // ---------- 装前自检：车机 /data 可用空间（大包要留出「临时文件 + 安装副本 + 解压 so」的余量）
            long freeKb = dataFreeKb();
            if (freeKb > 0) {
                long needKb = knownSize / 1024 * 5 / 2;
                log("[自检] 车机 /data 可用 " + (freeKb / 1024) + " MB；本次 APK " + (knownSize / 1048576)
                        + " MB，建议留 " + (needKb / 1024) + " MB 以上"
                        + (freeKb < needKb ? "  ⚠️ 空间可能不足（这是安装失败的常见原因）" : ""));
            }

            // ---------- 方式 A：流式安装（与 `adb install` 同一条路：stdin 直喂 pm/cmd，不落临时文件）
            if (knownSize > 0) {
                setStatus("正在流式安装 " + label + " …");
                log("[A] 流式安装：exec:cmd package install -S " + knownSize + " …");
                String a = adb.streamToService(
                        "exec:cmd package install -S " + knownSize + " -r --user " + uid,
                        src.open(), prog, 180000);
                if (isInstallOk(a)) {
                    done(label, uid, launch, a, t0);
                    return;
                }
                log("[A] 未成功：" + tailOf(a));
                allErr.append("[A] ").append(tailOf(a)).append("\n");
                log("[A2] 改用 pm install -S 再试 …");
                String a2 = adb.streamToService(
                        "exec:pm install -S " + knownSize + " -r --user " + uid,
                        src.open(), prog, 180000);
                if (isInstallOk(a2)) {
                    done(label, uid, launch, a2, t0);
                    return;
                }
                log("[A2] 未成功：" + tailOf(a2));
                allErr.append("[A2] ").append(tailOf(a2)).append("\n");
                log("[A3] 换 shell 通道再走一次流式安装 …");
                String a3 = adb.streamToService(
                        "shell:cmd package install -S " + knownSize + " -r --user " + uid,
                        src.open(), prog, 180000);
                if (isInstallOk(a3)) {
                    done(label, uid, launch, a3, t0);
                    return;
                }
                log("[A3] 未成功：" + tailOf(a3));
                allErr.append("[A3] ").append(tailOf(a3)).append("\n");
            } else {
                log("[A] 跳过流式安装（文件大小仍未知）");
            }

            // ---------- 方式 B：shell 流推到 /data/local/tmp 再装（本机已验证能传大文件的通道）
            setStatus("正在推送 " + label + "（方式 B）…");
            log("[B] 推送文件：cat > " + tmp + " …");
            long pushed = adb.pushViaShell(src.open(), tmp, prog);
            long onDevice = deviceFileSize(tmp);
            log("[B] 已推送 " + pushed + " 字节 / 车机侧 " + onDevice + " 字节");
            if (onDevice > 0 && onDevice == pushed) {
                out = pmInstall(uid, tmp);
                if (isInstallOk(out)) {
                    done(label, uid, launch, out, t0);
                    return;
                }
                log("[B] pm install 未成功：" + tailOf(out));
                allErr.append("[B] ").append(tailOf(out)).append("\n");
            } else {
                log("[B] 车机侧大小对不上（推送可能被截断）→ 转方式 C");
            }

            // ---------- 方式 C：sync 推送（容错版，最后兜底）
            setStatus("正在推送 " + label + "（方式 C）…");
            log("[C] sync 推送 " + tmp + " …");
            long pushed2 = adb.push(src.open(), tmp, 0644, prog);
            long onDevice2 = deviceFileSize(tmp);
            log("[C] 已推送 " + pushed2 + " 字节 / 车机侧 " + onDevice2 + " 字节");
            if (onDevice2 > 0 && onDevice2 == pushed2) {
                out = pmInstall(uid, tmp);
                if (isInstallOk(out)) {
                    done(label, uid, launch, out, t0);
                    return;
                }
            }

            setStatus(label + " 安装失败");
            setSummary(installSummary, "上次安装：" + label + " ❌ 失败（见日志）");
            String joined = allErr.length() > 0 ? allErr.toString() : String.valueOf(out);
            log("❌ 三种方式都没装上。车机最后返回：\n" + tailOf(out)
                    + "\n\n【各方式原因汇总】\n" + tailOf(joined)
                    + "\n" + installHint(joined, uid));
        } catch (Exception e) {
            setStatus("安装异常");
            String m = String.valueOf(e.getMessage());
            log("❌ 安装异常（第 " + ((System.currentTimeMillis() - t0) / 1000) + " 秒）：" + m
                    + "\n若为链路无应答：确认手机仍连着车机热点、离车近一点再试；"
                    + "若反复失败，请在 HiSH 里跑 `adb shell df /data` 和 `adb shell ls -l /data/local/tmp` 把结果发我。");
        } finally {
            busy = false;
            try {
                sh("rm -f " + tmp);
            } catch (Exception ignored) {
            }
            if (staged != null && staged.exists()) {
                try {
                    staged.delete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 把源数据落到手机本地文件，返回精确字节数（流式安装要求 size 精确）。 */
    private long stageLocally(StreamFactory sf, java.io.File dst) throws IOException {
        InputStream in = sf.open();
        java.io.FileOutputStream os = new java.io.FileOutputStream(dst);
        long n = 0;
        try {
            byte[] b = new byte[64 * 1024];
            int r;
            long next = 30L << 20;
            while ((r = in.read(b)) > 0) {
                os.write(b, 0, r);
                n += r;
                if (n >= next) {
                    next += 30L << 20;
                    log("[0] 已缓存 " + (n >> 20) + " MB …");
                }
            }
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return n;
    }

    private void done(String label, int uid, boolean launch, String out, long t0) throws IOException {
        if (launch) {
            postConfigFullscreen(uid);
        }
        setStatus(label + " 安装成功（" + spaceLabel(uid) + "）");
        log("✅ 安装成功：" + spaceRowTitle(uid)
                + "（用时 " + ((System.currentTimeMillis() - t0) / 1000) + " 秒）\n" + tailOf(out));
    }

    /**
     * 在车机上装一个已推送好的 APK。
     * 走 streamToService 而不是 shell()：服务流结尾设备可能不发 CLSE，
     * 这里靠输出里的 Success / Failure 判定结束，天然不依赖 CLSE。
     */
    /**
     * 「万物全屏」装后配置（照 LIGHTBOX：装完就给 appops 悬浮窗权限并拉起它的前台服务）。
     * 必须给 SYSTEM_ALERT_WINDOW：全屏靠悬浮球（非 Activity 图层）在"别的 App 前台"时发起搬屏，
     * 否则在自家窗口里点按钮只会把自家窗口搬走。
     */
    private void postConfigFullscreen(int uid) {
        final String pkg = "com.carhelper.fullscreen";
        try {
            String r1 = sh("appops set --user " + uid + " " + pkg
                    + " SYSTEM_ALERT_WINDOW allow 2>&1", 30000).trim();
            // 车机端"手机上传安装"走 PackageInstaller 会话 → 需要允许"安装未知应用"
            String r1b = sh("appops set --user " + uid + " " + pkg
                    + " REQUEST_INSTALL_PACKAGES allow 2>&1", 30000).trim();
            String r2 = sh("am start-foreground-service --user " + uid + " -n "
                    + pkg + "/" + pkg + ".FullscreenService 2>&1", 30000).trim();
            String r3 = sh("appops get --user " + uid + " " + pkg
                    + " SYSTEM_ALERT_WINDOW 2>&1", 30000).trim();
            log("装后配置（万物全屏）：\n"
                    + "· 授权悬浮窗 → " + nz(r1) + "\n"
                    + "· 授权安装未知应用 → " + nz(r1b) + "\n"
                    + "· 启动悬浮球服务 → " + nz(r2) + "\n"
                    + "· appops 复核 → " + nz(r3) + "\n"
                    + "用法：车机上回到桌面或切到任意 App，点屏幕边缘的圆形悬浮球即可全屏当前页面，再点一次还原；"
                    + "通知栏里也有「退出全屏」。");
        } catch (Exception e) {
            log("装后配置异常：" + e.getMessage()
                    + "\n可手动在车机端打开「车机助手·万物全屏」，点①授予悬浮窗、②启动悬浮球。");
        }
    }

    private String pmInstall(int uid, String path) throws IOException {
        String out = runOnDevice("shell:pm install -r --user " + uid + " " + path, 300000);
        if (out.contains("INSTALL_FAILED_TEST_ONLY")) {
            log("该 APK 带 testOnly 标记，改用 -t 重试 …");
            out = runOnDevice("shell:pm install -r -t --user " + uid + " " + path, 300000);
        }
        return out;
    }

    /** 跑一条不需要 stdin 的车机命令，收集输出直到结果行或超时。 */
    private String runOnDevice(String service, int tailWaitMs) throws IOException {
        try {
            return adb.streamToService(service, new java.io.ByteArrayInputStream(new byte[0]), null, tailWaitMs);
        } catch (IOException e) {
            log("通道中断（" + nz(e.getMessage()) + "）→ 自动重连并重试 …");
            if (!reconnect()) throw e;
            return adb.streamToService(service, new java.io.ByteArrayInputStream(new byte[0]), null, tailWaitMs);
        }
    }

    private static boolean isInstallOk(String out) {
        return out != null && out.contains("Success");
    }

    /** 车机上文件的大小（字节），读不到返回 -1。 */
    private long deviceFileSize(String path) {
        try {
            String s = sh("toybox stat -c %s " + path + " 2>/dev/null || ls -l " + path).trim();
            if (s.length() == 0) return -1;
            return firstNumber(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String tailOf(String s) {
        if (s == null) return "（无输出）";
        String t = s.trim();
        if (t.length() == 0) return "（无输出）";
        if (t.length() <= 1200) return t;
        return t.substring(0, 300) + "\n……（中间省略）……\n" + t.substring(t.length() - 900);
    }

    /** 车机 /data 可用空间（KB），读不到返回 -1。 */
    private long dataFreeKb() {
        try {
            String s = sh("df -k /data 2>/dev/null | tail -n 1 | awk '{print $4}'").trim();
            return Long.parseLong(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从车机返回里抠出报错涉及的包名。 */
    private static String pkgFromError(String out) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+)").matcher(out);
            while (m.find()) {
                String p = m.group(1);
                if (p.contains(".") && !p.startsWith("android.") && !p.startsWith("java.")
                        && !p.startsWith("com.android.")) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 常见 pm install 报错 → 人话建议。 */
    private static String installHint(String out, int uid) {
        if (out.contains("signatures do not match") || out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            String pkg = pkgFromError(out);
            return "建议：车机上还留着同名应用（官方版签名 ≠ 改造版签名）→ 必须**从所有空间**彻底卸载再装：\n"
                    + "① 本 App 卡片③ →「加载空间与应用列表」→ 应用列表里点选 " + (pkg == null ? "（该包名）" : pkg)
                    + "\n② 点新按钮「彻底卸载（所有空间，清残留）」——它会对每个空间 pm uninstall，再对所有用户卸一次，并逐空间复核残留\n"
                    + "③ 复核显示「各空间均已移除」后，回卡片②重新安装。\n"
                    + "（只摘一个空间是不够的：任一空间留着旧包，重装就会继续报签名冲突；HiSH 亦可：adb shell pm uninstall " + (pkg == null ? "<包名>" : pkg) + "）";
        }
        if (out.contains("INSTALL_FAILED_ALREADY_EXISTS"))
        if (out.contains("INSTALL_FAILED_ALREADY_EXISTS"))
            return "建议：车机已存在同名包 → 用卡片③「从选中空间取消授权」卸载后重装。";
        if (out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
            return "建议：签名冲突，需先卸载车机上的旧版再装。";
        if (out.contains("INSTALL_FAILED_VERSION_DOWNGRADE"))
            return "建议：车机上是更高版本，先卸载旧版或用 -d 降级安装。";
        if (out.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
            return "建议：车机存储空间不足（用 HiSH 跑 adb shell df /data 看），先清理。";
        if (out.contains("INSTALL_FAILED_VERIFICATION_FAILURE"))
            return "建议：被车机安装校验拦截，需要改包名重打包后再装。";
        if (out.contains("INSTALL_PARSE_FAILED"))
            return "建议：APK 解析失败（多半是传输被截断），重试一次；仍失败就把上面原文发我。";
        if (out.contains("INSTALL_FAILED_USER_RESTRICTED"))
            return "建议：车机策略限制了安装来源。";
        if (out.contains("setParamsSize") || out.contains("parseApkLite") || out.contains("nativeLoadFd"))
            return "建议：本车机在从 /data/local/tmp 读文件安装时会解析失败（与文件本身无关，字节数是核对过的）"
                    + "→ 优先用「流式安装」方式（本版会自动走流式）。";
        if (out.contains("No such file") || out.contains("not found"))
            return "建议：车机侧临时文件不见了（推送没落地），请重试。";
        return "建议：把上面这段原文发我，我按错误码定位。";
    }

    /** 取选中文件的显示名，用作日志标签。 */
    private String displayName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) return c.getString(i);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "所选 APK" : last;
    }

    private long sizeOf(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private long assetSize(String name) {
        try {
            return getAssets().openFd(name).getLength();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long firstNumber(String s) {
        Matcher m = Pattern.compile("(\\d{2,})").matcher(s);
        while (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    // ================================================================== ③ 授权管理

    private void loadSpacesAndApps() {
        if (!ensureConnected()) return;
        busy = true;
        setStatus("正在读取空间与应用 …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 1) 刷新用户空间与活跃空间（活跃空间可能被车主切换过）
                    String info = sh("printf 'USER=';am get-current-user;"
                            + "printf ';USERS=';pm list users | tr '\\n' '|'");
                    activeUser = parseIntSafe(seg(info, "USER="));
                    rawUserList = seg(info, "USERS=").trim();
                    parseUsers(rawUserList);

                    // 2) 第三方应用 —— 必须**按每个用户空间**查！
                    //     `pm list packages -3` 只看 shell 自己的 user（默认 0），
                    //     而我们的应用装在 12（活跃空间）→ 直接查会得到 0 个（v1.0.6 的 bug）。
                    allPkgs.clear();
                    allPkgsAny.clear();
                    pkgUsers.clear();
                    for (int i = 0; i < deviceUsers.size(); i++) {
                        int uid = deviceUsers.get(i).intValue();
                        // 全部包（含系统/预置）——用于关键字搜索，方便找"预置同名包"这类装不上的元凶
                        String anyOut = sh("pm list packages -u --user " + uid + " 2>/dev/null");
                        for (String line : anyOut.split("\n")) {
                            String t = line.trim();
                            if (t.startsWith("package:")) {
                                String pk = t.substring(8).trim();
                                if (!allPkgsAny.contains(pk)) allPkgsAny.add(pk);
                            }
                        }
                        String out = sh("pm list packages -3 --user " + uid + " 2>/dev/null");
                        for (String line : out.split("\n")) {
                            String t = line.trim();
                            if (!t.startsWith("package:")) continue;
                            String pk = t.substring(8).trim();
                            List<Integer> us = pkgUsers.get(pk);
                            if (us == null) {
                                us = new ArrayList<Integer>();
                                pkgUsers.put(pk, us);
                                allPkgs.add(pk);
                            }
                            if (!us.contains(Integer.valueOf(uid))) us.add(Integer.valueOf(uid));
                        }
                    }
                    Collections.sort(allPkgs);
                    Collections.sort(allPkgsAny);

                    // 3) 屏幕归属旁证（display ↔ user，仅打到日志里，用于核对/校正标签）
                    String evidence = "";
                    try {
                        evidence = sh("dumpsys activity activities 2>/dev/null "
                                + "| grep -E 'Display #|U=[0-9]+' | head -n 24 | tr '\\n' '|'", 25000).trim();
                        if (evidence.length() > 700) evidence = evidence.substring(0, 700) + " …";
                    } catch (Exception ignored) {
                    }

                    final String ev = evidence;
                    ui.post(new Runnable() {
                        public void run() {
                            renderSpaceChecks();
                            renderApps("");
                            setStatus("已读取 " + deviceUsers.size() + " 个空间、" + allPkgs.size() + " 个第三方应用");
                            setSummary(manageSummary, "空间 " + deviceUsers.size() + " 个 · 第三方应用 "
                                    + allPkgs.size() + " 个（可搜预置/残留包）" + (selectedPkg == null ? "" : " · 已选 " + selectedPkg));
                            log("勾选空间（可多选）+ 点选一个应用，然后点「授权」或「取消授权」。\n"
                                    + "空间原始信息：" + nz(rawUserList) + "\n"
                                    + "当前活跃空间：" + (activeUser >= 0 ? activeUser : "未知")
                                    + (ev.length() > 0 ? "\n屏幕归属旁证（display ↔ user）：" + ev : ""));
                        }
                    });
                } catch (Exception e) {
                    setStatus("读取失败");
                    log("读取失败：" + e.getMessage());
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    private void renderSpaceChecks() {
        spaceBox.removeAllViews();
        pickedSpaces.clear();

        TextView legend = new TextView(this);
        legend.setTextColor(Color.parseColor("#7A8894"));
        legend.setTextSize(11);
        legend.setText("空间对照（领克/吉利多屏约定）：0 主驾·司机 · 10 访客 · 12 主驾/中控 · 13 副驾 · "
                + "100 后排娱乐屏 · 101 中控+副驾；名字带 _clone 的表示克隆空间（副驾/移动空间常表现为主空间的克隆）；"
                + "✱ = 车机当前活跃空间。\n标签是按设备信息推断的，下面是每行的设备原始空间名，以实车为准。");
        legend.setPadding(0, 0, 0, dp(8));
        spaceBox.addView(legend);

        for (int i = 0; i < deviceUsers.size(); i++) {
            final int uid = deviceUsers.get(i).intValue();
            CheckBox cb = new CheckBox(this);
            cb.setText(spaceRowTitle(uid));
            cb.setTextColor(Color.WHITE);
            cb.setTextSize(14);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    if (checked) {
                        if (!pickedSpaces.contains(Integer.valueOf(uid))) pickedSpaces.add(Integer.valueOf(uid));
                    } else {
                        pickedSpaces.remove(Integer.valueOf(uid));
                    }
                }
            });
            spaceBox.addView(cb);

            String raw = rawNameOf(uid);
            TextView sub = new TextView(this);
            sub.setText("设备空间名：" + (raw.length() == 0 ? "（未读到）" : raw)
                    + (uid == activeUser ? "　（车机正在使用的空间）" : ""));
            sub.setTextColor(Color.parseColor("#586069"));
            sub.setTextSize(11);
            sub.setPadding(dp(34), 0, 0, dp(6));
            spaceBox.addView(sub);
        }
    }

    private void renderApps(String filter) {
        appBox.removeAllViews();
        String f = filter.toLowerCase();
        int shown = 0;
        // 没输关键字 → 只列第三方；输了关键字 → 连系统/预置一起搜（"预置同名包"是装不上的常见元凶）
        List<String> src = f.length() > 0 ? allPkgsAny : allPkgs;
        for (final String p : src) {
            if (f.length() > 0 && !p.toLowerCase().contains(f)) continue;
            if (shown++ >= 120) break;
            List<Integer> us = pkgUsers.get(p);
            TextView tv = new TextView(this);
            tv.setText(p + (allPkgs.contains(p) ? "" : "   [预置/系统或残留记录]")
                    + (us == null || us.isEmpty() ? "" : "   [空间 " + joinInts(us) + "]"));
            tv.setTextSize(13);
            tv.setPadding(dp(12), dp(10), dp(12), dp(10));
            boolean sel = p.equals(selectedPkg);
            tv.setTextColor(sel ? Color.WHITE : Color.parseColor("#C9D1D9"));
            tv.setBackground(bg(sel ? "#1F6FEB" : "#0F1418", 8));
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectedPkg = p;
                    renderApps(appFilter.getText().toString());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            tv.setLayoutParams(lp);
            appBox.addView(tv);
        }
    }

    /**
     * 彻底卸载：把选中的包从**所有用户空间**移除，并逐空间复核残留。
     *
     * 为什么需要它：`pm uninstall --user N` 只摘掉某一个空间；
     * 只要**任何一个**空间还留着这个包，重装改造版就会报
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package xxx signatures do not match previously installed version。
     * 第 1 步 `pm uninstall <pkg>`（不带 --user）才是"对所有用户卸载"。
     */
    /**
     * 诊断：把某个包在**每个用户空间**里的状态打出来（installed/enabled/hidden/stopped/安装原因）。
     * 用来区分"包真的没装"和"装了但桌面不显示"。
     */
    private void dumpUsers() {
        if (!ensureConnected()) return;
        final String pkg = currentPkg();
        if (pkg.length() == 0) {
            log("请先在应用列表里点选一个包，或在上面的输入框里填包名。");
            return;
        }
        setStatus("正在诊断 " + pkg + " 的各空间状态 …");
        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder("【" + pkg + " 各空间安装状态】\n");
                try {
                    String all = sh("pm list packages -u " + pkg + " 2>/dev/null", 30000).trim();
                    sb.append("· pm list packages -u：").append(all.length() == 0 ? "无记录" : all).append("\n");
                    String full = sh("dumpsys package " + pkg + " 2>/dev/null", 60000);
                    // 按行扫描 "User N:" 段，取紧随其后的 installed=/enabled=/hidden=/stopped= 字段（同一空间只打一次）
                    java.util.LinkedHashMap<String, String> perUser = new java.util.LinkedHashMap<String, String>();
                    String cur = null;
                    StringBuilder flags = null;
                    for (String raw : full.split("\\n")) {
                        String line = raw.trim();
                        if (line.startsWith("User ") && line.contains(":")) {
                            String uid = line.substring(5, line.indexOf(':')).trim();
                            if (!perUser.containsKey(uid)) {
                                perUser.put(uid, "");
                                cur = uid;
                                flags = new StringBuilder();
                                String tail = line.substring(line.indexOf(':') + 1).trim();
                                if (tail.length() > 0) flags.append(tail).append("  ");
                                perUser.put(uid, flags.toString());
                            } else {
                                cur = null;   // 同一空间重复段，跳过
                            }
                            continue;
                        }
                        if (cur != null && flags != null && line.contains("=")
                                && (line.startsWith("installed=") || line.startsWith("enabled=")
                                || line.startsWith("hidden=") || line.startsWith("stopped=")
                                || line.startsWith("suspended=") || line.startsWith("installReason=")
                                || line.startsWith("ceDataInode=") || line.startsWith("notLaunched="))) {
                            flags.append(line).append("  ");
                            perUser.put(cur, flags.toString());
                        }
                    }
                    if (perUser.isEmpty()) {
                        sb.append("（dumpsys 里没有 User 段 —— 该包在车机上没有安装记录）\n");
                    } else {
                        for (java.util.Map.Entry<String, String> e2 : perUser.entrySet()) {
                            boolean pathOk = sh("pm path --user " + e2.getKey() + " " + pkg + " 2>/dev/null", 30000)
                                    .contains("package:");
                            sb.append("· user ").append(e2.getKey()).append("：")
                                    .append(pathOk ? "pm path 有 ✅" : "pm path 无 ❌").append("\n    ")
                                    .append(e2.getValue().trim().length() == 0 ? "（无字段）" : e2.getValue().trim())
                                    .append("\n");
                        }
                    }
                    sb.append("提示：installed=true 而桌面不显示 → 是桌面（launcher）显示/缓存问题，点「刷新主驾桌面」或重启车机；")
                            .append("installed=false → 对该空间再点一次「授权到选中空间」。");
                    log(sb.toString());
                    setStatus("诊断完成，见日志");
                } catch (Exception e) {
                    log("诊断失败：" + nz(e.getMessage()));
                }
            }
        }).start();
    }

    private String currentPkg() {
        if (selectedPkg != null && selectedPkg.length() > 0) return selectedPkg;
        if (pkgInput != null) return pkgInput.getText().toString().trim();
        return "";
    }

    /** 静默刷新某空间的桌面（授权/卸载后自动调用）。 */
    private void refreshLauncherQuiet(int uid) {
        try {
            sh("am force-stop --user " + uid + " com.flyme.auto.launcher 2>&1", 20000);
            Thread.sleep(800);
            sh("am start --user " + uid
                    + " -n com.flyme.auto.launcher/com.flyme.auto.launcher.ui.main.LauncherActivity 2>&1", 20000);
            log("已刷新空间 " + uid + " 的桌面");
        } catch (Exception e) {
            log("刷新空间 " + uid + " 的桌面失败：" + nz(e.getMessage()));
        }
    }

    /**
     * 刷新桌面：重启车机 launcher（这类车机"应用装上了但桌面不显示"最常见的原因就是 launcher 缓存）。
     * 车机桌面是系统应用，force-stop 后系统会自动拉起；这里再主动 start 一次确保回来。
     */
    private void refreshLauncher() {
        if (!ensureConnected()) return;
        final int uid = chosenUser >= 0 ? chosenUser : 12;
        setStatus("正在刷新主驾桌面 …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    String pkgName = "com.flyme.auto.launcher";
                    sh("am force-stop --user " + uid + " " + pkgName + " 2>&1", 30000);
                    Thread.sleep(1500);
                    String start = sh("am start --user " + uid + " -n " + pkgName
                            + "/com.flyme.auto.launcher.ui.main.LauncherActivity 2>&1", 30000).trim();
                    log("已重启桌面（user " + uid + "）：\n" + tailOf(start)
                            + "\n如果桌面仍未出现应用，请**重启车机**一次（桌面列表会重建）；"
                            + "同时用「诊断：该应用在各空间的安装状态」确认 installed=true。");
                    setStatus("桌面已重启");
                } catch (Exception e) {
                    log("刷新桌面失败：" + nz(e.getMessage())
                            + "\n可手动在车机上重启或重启车机。");
                }
            }
        }).start();
    }

    private void purgePackage(String explicit) {
        if (!ensureConnected()) return;
        final String pkg = (explicit != null && explicit.length() > 0) ? explicit
                : (selectedPkg != null ? selectedPkg : (pkgInput != null ? pkgInput.getText().toString().trim() : ""));
        if (pkg.length() == 0) {
            log("请先在应用列表里点选一个包，或在上面的输入框里填包名。");
            return;
        }
        setStatus("正在彻底卸载 " + pkg + " …");
        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder("彻底卸载 " + pkg + "：\n");
                try {
                    // 1) 先按空间逐个摘（system app 也只能这样摘）
                    for (int i = 0; i < deviceUsers.size(); i++) {
                        int uid = deviceUsers.get(i).intValue();
                        String r = sh("pm uninstall --user " + uid + " " + pkg + " 2>&1", 60000).trim();
                        sb.append("· user ").append(uid).append("：").append(nz(r)).append("\n");
                    }
                    // 2) 再"对所有用户"卸载一次，清掉残留记录
                    String all = sh("pm uninstall " + pkg + " 2>&1", 60000).trim();
                    sb.append("· 所有用户：").append(nz(all)).append("\n");
                    sh("pm clear " + pkg + " 2>&1", 30000);

                    // 3) 逐空间复核：还有哪个空间留着它
                    StringBuilder left = new StringBuilder();
                    for (int i = 0; i < deviceUsers.size(); i++) {
                        int uid = deviceUsers.get(i).intValue();
                        String p = sh("pm path --user " + uid + " " + pkg + " 2>/dev/null", 30000);
                        if (p.contains("package:")) {
                            left.append(uid).append(" ");
                        }
                    }
                    String listU = sh("pm list packages -u " + pkg + " 2>/dev/null", 30000).trim();
                    sb.append("· 复核 pm path：").append(left.length() == 0 ? "各空间均已移除 ✅" : "仍存在于空间 " + left).append("\n");
                    sb.append("· pm list packages -u（含已卸载记录）：").append(listU.length() == 0 ? "无记录 ✅" : listU).append("\n");

                    // ★ 关键诊断：同名"系统/预置"版本？有它的话重签名包永远装不上（那条签名记录卸载不掉）
                    String sys = sh("pm list packages -s " + pkg + " 2>/dev/null", 30000).trim();
                    String allUsers = sh("pm list packages --user all " + pkg + " 2>/dev/null", 30000).trim();
                    String dump = sh("dumpsys package " + pkg + " 2>/dev/null | head -n 40", 30000).trim();
                    sb.append("· 预置包 pm list packages -s：").append(sys.length() == 0 ? "无（不是预置应用）" : sys).append("\n");
                    sb.append("· 所有用户：").append(allUsers.length() == 0 ? "无" : allUsers).append("\n");
                    sb.append("· dumpsys package 摘要：\n").append(tailOf(dump)).append("\n");
                    boolean systemCopy = sys.length() > 0 || dump.contains("system/") || dump.contains("/product/");
                    if (systemCopy) {
                        sb.append("❗诊断：车机里存在**预置/系统**的同名应用（或 codePath 指向 /system）——"
                                + "这条签名记录卸载不掉，凡签名与它不同的包都装不上。\n"
                                + "→ 解法：给改造版**改包名**（我可出 com.sumsg.musichub.mod 版，与原版共存）。\n");
                    } else if (listU.length() > 0) {
                        sb.append("❗诊断：这个包名在车机上**还留着记录**（"
                                + "已卸载但仍显示在 pm list packages -u 里 = KEEP_DATA 残留记录）。\n"
                                + "PM 会拿这条记录里的签名来比对 → 所以卸载了也照样报签名冲突。\n"
                                + "→ 两个解法，任选：\n"
                                + "   A) 用**官方原版 APK** 先装一次（签名能对上）→ 再 `pm uninstall " + pkg + "` 干净卸载（**不要**带 -k）→ 残留记录即清；\n"
                                + "   B) **改包名**（推荐，我可出 " + pkg + ".mod 版，与原版共存、彻底绕开）。\n"
                                + "   C) 车机上执行：pm uninstall --user all " + pkg + "  （有时能连记录一起清）\n");
                    } else {
                        sb.append("诊断：无预置同名包、也无残留记录；各空间已清理并复核，可直接回卡片②重装。\n");
                    }
                    setStatus(left.length() == 0 ? "已彻底卸载：" + pkg : "仍有残留：" + pkg);
                    setSummary(manageSummary, "上次操作：彻底卸载 " + pkg
                            + (left.length() == 0 ? " ✅ 各空间已清" : " ⚠️ 仍有残留：" + left));
                    for (int i = 0; i < deviceUsers.size(); i++) {
                        refreshLauncherQuiet(deviceUsers.get(i).intValue());
                    }
                    log(sb.toString() + (left.length() == 0
                            ? "\n现在可以回卡片②重新安装改造版了。"
                            : "\n若仍失败，请把这段日志发我。"));
                } catch (Exception e) {
                    log("卸载异常：" + e.getMessage());
                }
            }
        }).start();
    }

    private void applySpaces(final boolean grant) {
        if (!ensureConnected()) return;
        if (selectedPkg == null) {
            log("请先点选一个应用。");
            return;
        }
        if (pickedSpaces.isEmpty()) {
            log("请先勾选至少一个空间。");
            return;
        }
        final String pkg = selectedPkg;
        final List<Integer> uids = new ArrayList<Integer>(pickedSpaces);
        setStatus((grant ? "正在授权 " : "正在取消授权 ") + pkg);
        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                for (Integer uid : uids) {
                    String cmd = grant
                            ? ("pm install-existing --user " + uid + " " + pkg)
                            : ("pm uninstall --user " + uid + " " + pkg);
                    try {
                        sb.append(uid).append(" · ").append(spaceLabel(uid)).append(" → ")
                                .append(sh(cmd).trim()).append("\n");
                    } catch (Exception e) {
                        sb.append(uid).append(" · ").append(spaceLabel(uid))
                                .append(" → 异常: ").append(e.getMessage()).append("\n");
                    }
                }
                setStatus((grant ? "授权" : "取消授权") + "完成：" + pkg);
                setSummary(manageSummary, "上次操作：" + (grant ? "授权" : "取消授权") + " " + pkg
                        + " → 空间 " + uids);
                // 这类车机的桌面列表有缓存：授权/卸载后不刷新，桌面可能看不到变化（实测需重启 launcher）
                for (Integer u : uids) {
                    refreshLauncherQuiet(u.intValue());
                }
                sb.append("\n已自动刷新桌面：").append(uids)
                        .append("（若桌面仍无变化，可点「刷新主驾桌面」或重启车机）");
                log(sb.toString());
            }
        }).start();
    }

    // ================================================================== 工具

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean ensureConnected() {
        if (connected && adb.isConnected()) return true;
        log("连接已断开 → 自动尝试重连 …");
        if (reconnect()) return true;
        log("还没连上车机，请先在卡片①点「一键连接车机」。");
        return false;
    }

    private static String seg(String src, String key) {
        int i = src.indexOf(key);
        if (i < 0) return "";
        int j = src.indexOf(';', i);
        return j < 0 ? src.substring(i + key.length()) : src.substring(i + key.length(), j);
    }

    private static String netOf(String ip) {
        String[] p = ip.split("\\.");
        return p.length == 4 ? p[0] + "." + p[1] + "." + p[2] + ".0/24" : ip;
    }

    private static String joinInts(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return (s == null || s.length() == 0) ? "—" : s;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adb.close();
    }
}
