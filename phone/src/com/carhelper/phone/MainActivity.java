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
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final AdbClient adb = new AdbClient();
    private CarFinder.Wifi wifi;
    private String carIp;
    private boolean isRear;
    private boolean connected;
    private String selectedPkg;
    private int chosenUser = -1;

    private final List<Integer> deviceUsers = new ArrayList<Integer>();
    private final List<String> deviceUserNames = new ArrayList<String>();
    private final List<String> allPkgs = new ArrayList<String>();
    private final List<Integer> pickedSpaces = new ArrayList<Integer>();
    private final List<int[]> targets = new ArrayList<int[]>(); // {userId, 名称资源索引}

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
        log("请确保：① 手机已连车机热点（Lynk&Co）② 车辆处于 P 挡、车机屏已唤醒。\n然后点「一键连接车机」。");
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

    private View buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.parseColor("#0B0E11"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sc.addView(root);

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

        // ---------------- 卡片 ① 连接车机 ----------------
        LinearLayout c1 = card(root, "① 连接车机", "一键完成：检测热点 → 发现车机 → 连接 ADB");
        c1.addView(btn("一键连接车机", "#1F6FEB", new Runnable() {
            public void run() { oneClickConnect(); }
        }));

        // ---------------- 卡片 ② 安装应用 ----------------
        LinearLayout c2 = card(root, "② 安装应用到车机", "选择要安装到哪块屏幕（按当前热点自动筛除不可用空间）");
        spaceHint = new TextView(this);
        spaceHint.setTextColor(Color.parseColor("#7A8894"));
        spaceHint.setTextSize(12);
        spaceHint.setPadding(0, dp(6), 0, dp(6));
        spaceHint.setText("连接后自动识别可用屏幕。");
        c2.addView(spaceHint);
        spaceGroup = new RadioGroup(this);
        spaceGroup.setOrientation(RadioGroup.VERTICAL);
        c2.addView(spaceGroup);
        c2.addView(gap(6));
        c2.addView(btn("选择本地 APK 安装", "#238636", new Runnable() {
            public void run() { pickApk(); }
        }));
        c2.addView(gap(8));
        c2.addView(btn("一键安装「全屏工具」到车机", "#238636", new Runnable() {
            public void run() { installBuiltin(); }
        }));

        // ---------------- 卡片 ③ 授权空间管理 ----------------
        LinearLayout c3 = card(root, "③ 应用授权空间管理", "给已装应用批量授权 / 取消授权到指定空间");
        c3.addView(btn("加载空间与应用列表", "#8957E5", new Runnable() {
            public void run() { loadSpacesAndApps(); }
        }));
        c3.addView(gap(8));
        TextView st = new TextView(this);
        st.setText("空间（可多选）");
        st.setTextColor(Color.parseColor("#8B949E"));
        st.setTextSize(12);
        c3.addView(st);
        spaceBox = new LinearLayout(this);
        spaceBox.setOrientation(LinearLayout.VERTICAL);
        c3.addView(spaceBox);
        c3.addView(gap(8));
        TextView at = new TextView(this);
        at.setText("应用（点选一个，可搜索）");
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
        c3.addView(gap(8));
        c3.addView(btn("授权到选中空间", "#238636", new Runnable() {
            public void run() { applySpaces(true); }
        }));
        c3.addView(gap(8));
        c3.addView(btn("从选中空间取消授权", "#DA3633", new Runnable() {
            public void run() { applySpaces(false); }
        }));

        // ---------------- 卡片 ④ 日志 ----------------
        LinearLayout c4 = card(root, "④ 运行日志", null);
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#8B949E"));
        logView.setTextSize(12);
        logView.setText("（空）");
        c4.addView(logView);
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

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            public void run() { statusView.setText(s); }
        });
    }

    private void log(final String s) {
        ui.post(new Runnable() {
            public void run() { logView.setText(s); }
        });
    }

    // ================================================================== ① 一键连接

    private void oneClickConnect() {
        setStatus("正在连接车机 …");
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
                        stepLog("步骤 3/4 连接 " + ip + ":5555 …");
                        adb.close();
                        adb.connect(ip, 5555, 6000, 60000);
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

    private void fail(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                setStatus("连接失败");
                log(msg);
            }
        });
    }

    private void stepLog(final String s) {
        ui.post(new Runnable() {
            public void run() { logView.setText(s); }
        });
    }

    /** 采集设备特征 + 用户空间，判定前排/后排，筛出可用安装空间。 */
    private void probeDevice() throws Exception {
        String out = adb.shell(
                "printf 'SERIAL=';getprop ro.serialno;"
                        + "printf ';MODEL=';getprop ro.product.model;"
                        + "printf ';PRODUCT=';getprop ro.product.name;"
                        + "printf ';DEVICE=';getprop ro.product.device;"
                        + "printf ';USER=';am get-current-user;"
                        + "printf ';USERS=';pm list users | tr '\\n' ' ';"
                        + "printf ';REAR=';pm path --user 0 com.desaysv.launcher 2>/dev/null | head -1");

        String rearSeg = seg(out, "REAR=");
        isRear = rearSeg.contains("package:");

        String usersSeg = seg(out, "USERS=");
        deviceUsers.clear();
        deviceUserNames.clear();
        Matcher m = Pattern.compile("UserInfo\\{(\\d+):([^:}]*)[:}]").matcher(usersSeg);
        while (m.find()) {
            deviceUsers.add(Integer.valueOf(Integer.parseInt(m.group(1))));
            deviceUserNames.add(m.group(1) + " · " + m.group(2));
        }

        String curSeg = seg(out, "USER=").trim();
        int cur = -1;
        try {
            cur = Integer.parseInt(curSeg.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
        }

        // 目标空间：前排 = 中控(12) + 副驾(13)；后排 = 左(0) + 右(10)
        targets.clear();
        if (isRear) {
            // 后排娱乐屏：空间号因车型/固件而异（100 表示"后排"，部分固件下真实 user 需动态解析）
            // → 以实机检测为准：优先 100 / 101，其次 0 / 10，最后列出全部真实空间
            int[] pref = {100, 101, U_REAR_A, U_REAR_B};
            for (int pi = 0; pi < pref.length; pi++) addTargetIfExists(pref[pi], null);
            if (targets.isEmpty()) {
                for (int i = 0; i < deviceUsers.size(); i++) targets.add(new int[]{deviceUsers.get(i), 0});
            }
        } else {
            addTargetIfExists(U_CENTER, "主驾屏（中控）");
            addTargetIfExists(U_PASSENGER, "副驾屏");
        }
        if (targets.isEmpty()) {
            // 兜底：用当前活跃空间
            targets.add(new int[]{cur >= 0 ? cur : 0, 0});
        }
        chosenUser = targets.get(0)[0];
    }

    private void addTargetIfExists(int uid, String name) {
        if (deviceUsers.contains(Integer.valueOf(uid))) {
            targets.add(new int[]{uid, 0});
        }
    }

    private void renderSpaceChoices() {
        spaceGroup.removeAllViews();
        // 重新按顺序生成名称（probeDevice 里只存了 uid）
        List<String> names = new ArrayList<String>();
        for (int[] t : targets) names.add(spaceName(t[0]));
        if (isRear) {
            spaceHint.setText("当前连接：后排娱乐屏热点\n可安装到：后排娱乐屏（用户空间 100）");
        } else {
            spaceHint.setText("当前连接：前排热点\n可安装到：中控（用户空间 12）、副驾（用户空间 13）");
        }
        for (int i = 0; i < targets.size(); i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names.get(i) + "（用户空间 " + targets.get(i)[0] + "）");
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(14);
            rb.setId(2000 + i);
            rb.setTag(Integer.valueOf(targets.get(i)[0]));
            if (i == 0) rb.setChecked(true);
            rb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    chosenUser = ((Integer) v.getTag()).intValue();
                    setStatus("已选择安装位置：用户空间 " + chosenUser);
                }
            });
            spaceGroup.addView(rb);
        }
        // 不可用空间说明
        TextView note = new TextView(this);
        note.setTextColor(Color.parseColor("#586069"));
        note.setTextSize(11);
        note.setPadding(0, dp(4), 0, 0);
        StringBuilder sb = new StringBuilder("已自动筛除本设备上不可用的空间（本机用户空间：");
        sb.append(deviceUserNames.isEmpty() ? "未读到" : join(deviceUserNames));
        sb.append("）");
        note.setText(sb.toString());
        spaceGroup.addView(note);
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
                        InputStream is = getContentResolver().openInputStream(uri);
                        install(is, "所选 APK", false);
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
                    InputStream is = getAssets().open("carhelper-fullscreen.apk");
                    install(is, "全屏工具", true);
                } catch (Exception e) {
                    log("读取内置全屏工具失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void install(InputStream src, String label, boolean launch) {
        try {
            int uid = chosenUser >= 0 ? chosenUser : 0;
            setStatus("正在推送 " + label + " …");
            log("目标用户空间：" + uid + "\n开始推送（大包需 30~90 秒）…");
            String tmp = "/data/local/tmp/carhelper-" + System.currentTimeMillis() + ".apk";
            adb.push(src, tmp, 0644);
            setStatus("正在安装 " + label + " …");
            log("推送完成，执行 pm install --user " + uid + " …");
            String out = adb.shell("pm install -r --user " + uid + " " + tmp);
            adb.shell("rm -f " + tmp);
            if (out.contains("Success")) {
                setStatus(label + " 安装成功（用户空间 " + uid + "）");
                String extra = "";
                if (launch) {
                    String r = adb.shell("am start --user " + uid
                            + " -n com.carhelper.fullscreen/com.carhelper.fullscreen.MainActivity");
                    extra = "\n已尝试在车机上拉起全屏工具。";
                }
                log("✅ 安装成功（用户空间 " + uid + "）" + extra);
            } else {
                setStatus(label + " 安装失败");
                log("安装失败，车机返回：\n" + out.trim());
            }
        } catch (Exception e) {
            setStatus("安装异常");
            log("安装异常：" + e.getMessage());
        }
    }

    // ================================================================== ③ 授权管理

    private void loadSpacesAndApps() {
        if (!ensureConnected()) return;
        setStatus("正在读取空间与应用 …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    String pkgs = adb.shell("pm list packages -3");
                    allPkgs.clear();
                    for (String line : pkgs.split("\n")) {
                        String s = line.trim();
                        if (s.startsWith("package:")) allPkgs.add(s.substring(8).trim());
                    }
                    Collections.sort(allPkgs);
                    ui.post(new Runnable() {
                        public void run() {
                            renderSpaceChecks();
                            renderApps("");
                            setStatus("已读取 " + deviceUsers.size() + " 个空间、" + allPkgs.size() + " 个第三方应用");
                            log("勾选空间（可多选）+ 点选一个应用，然后点「授权」或「取消授权」。");
                        }
                    });
                } catch (Exception e) {
                    setStatus("读取失败");
                    log("读取失败：" + e.getMessage());
                }
            }
        }).start();
    }

    private void renderSpaceChecks() {
        spaceBox.removeAllViews();
        pickedSpaces.clear();
        for (int i = 0; i < deviceUsers.size(); i++) {
            final int uid = deviceUsers.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setText(deviceUserNames.get(i));
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
        }
    }

    private void renderApps(String filter) {
        appBox.removeAllViews();
        String f = filter.toLowerCase();
        int shown = 0;
        for (final String p : allPkgs) {
            if (f.length() > 0 && !p.toLowerCase().contains(f)) continue;
            if (shown++ >= 120) break;
            TextView tv = new TextView(this);
            tv.setText(p);
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
                        sb.append("user ").append(uid).append(" → ")
                                .append(adb.shell(cmd).trim()).append("\n");
                    } catch (Exception e) {
                        sb.append("user ").append(uid).append(" → 异常: ").append(e.getMessage()).append("\n");
                    }
                }
                setStatus((grant ? "授权" : "取消授权") + "完成：" + pkg);
                log(sb.toString());
            }
        }).start();
    }

    // ================================================================== 工具

    /**
     * 用户空间号 → 实际屏幕名。
     * 车机多屏系统的用户空间编号约定：
     *   12 → 中控   13 → 副驾   100 → 后排娱乐屏   101 → 中控和副驾   其他 → user N
     */
    static String spaceName(int uid) {
        if (uid == 12) return "中控";
        if (uid == 13) return "副驾";
        if (uid == 100) return "后排娱乐屏";
        if (uid == 101) return "中控和副驾";
        return "user " + uid;
    }

    private boolean ensureConnected() {
        if (!connected || !adb.isConnected()) {
            log("还没连上车机，请先在卡片①点「一键连接车机」。");
            return false;
        }
        return true;
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
