# 车机助手 · CarHelper

> ⚠️ **本项目为独立第三方开源工具，与浙江吉利控股集团、领克汽车、极氪汽车、亿咖通科技（ECARX）、魅族科技均无任何关联，亦未获其授权或认可。**
> 仅供**自有车辆**的互操作与学习研究使用。使用者须自行确认并遵守当地法律法规与车辆保修条款。详见 [DISCLAIMER.md](DISCLAIMER.md)。

一个面向 **吉利 / 领克（亿咖通 ECARX 平台）Android 车机** 的开源小工具：

> **手机连车机热点 → 通过 ADB 安装 APK 到指定屏幕空间 → 一键全屏**

由两个部分组成，手机端已内置车机端 APK，**只需安装一个 App**：

| 组件 | 包名 | 说明 |
|---|---|---|
| 手机端 App | `com.carhelper.phone` | 发现车机、连接 ADB、安装 APK、管理应用授权空间 |
| 车机端 APK | `com.carhelper.fullscreen` | 一键全屏 / 还原（通过车机自带的多屏管理服务调整显示区域） |

## 兼容性

| 平台 | 状态 |
|---|---|
| **领克 900** | ✅ **已实机测试可用** |
| 其他领克 / 吉利 / 极氪车机（**高通 8295 芯片 + Flyme Auto 2.0 及以上系统**） | ⚠️ 可能适用，**待验证** |
| 其他平台 | ❌ 未验证 —— 屏幕空间编号与系统服务名可能不同 |

> 车机端「一键全屏」依赖系统服务 `geely_multi`。若你的车型上该服务不存在或接口不同，全屏功能会失效（**装机功能不受影响**）。

## 功能

### 手机端

1. **一键连接车机** — 自动检测 WiFi → 发现车机 ADB 端口 → 连接 → 识别设备类型与可用屏幕空间；任一步失败都会给出**具体原因**（不是笼统的"连接失败"）
2. **安装应用到车机** — 选择本地 APK 安装到指定屏幕，安装位置用**自然语言**呈现（如「中控（用户空间 12）」「副驾（用户空间 13）」），并按当前热点自动筛除不可用空间
3. **应用授权空间管理** — 对已安装应用批量授权 / 取消授权到指定用户空间（等价于 `pm install-existing --user N <pkg>` / `pm uninstall --user N <pkg>`）
4. **一键安装「全屏工具」** — 把内置的车机端全屏 APK 装到车机并拉起
5. **首次启动风险告知** — 车机风险 / 驾驶风险 / 保修风险 / 数据隐私，同意后方可使用

### 车机端

- **一键全屏** / **还原** / **副驾全屏**
- 实时显示各显示区域的顶层应用与当前是否全屏

## 设计原则

| 项 | 本工具 |
|---|---|
| 修改车机系统组件 | ❌ 不做 |
| 关闭安装校验器（如 `xsfinstallverifier`） | ❌ 不做 |
| 外联服务器 / 上报数据 | ❌ 零外联，只连车机 `:5555` |
| 手机端权限 | 仅 `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` |
| 车机端常驻 | 只有用户主动安装的全屏工具，无常驻后台服务 |

## 构建

**不需要 Gradle**，纯命令行流水线：`aapt2 → javac → d8 → zipalign → apksigner`。

```bash
# 1) 准备工具链（约 90MB，一次即可）
mkdir -p build/sdk && cd build/sdk
curl -LO https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-linux.zip
curl -LO https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r02.zip
unzip -q build-tools_r34-linux.zip && mv android-14 build-tools
unzip -q platform-34-ext7_r02.zip && mv android-34 platform-34
cd ../..

# 2) 构建（自动生成调试签名）
./build/build.sh all      # 或 car / phone
# 产物：build/out/carhelper-fullscreen.apk、build/out/carhelper-phone.apk
```

> 注：`build/sdk`、`build/out`、`build/keystore` 已在 `.gitignore` 中；首次构建会自动生成 `build/keystore/debug.keystore`。
> 若镜像里的文件名有变化，也可从任意 Android SDK 发行版取 `build-tools` 与 `platforms/android-34/android.jar`，放到对应目录即可。

环境要求：JDK 17+、`zip`、`curl`。

## 使用

1. 把 `carhelper-phone.apk` 装到手机（Android 或鸿蒙 + 兼容层均可）
2. 手机 WiFi 连上车机热点（领克车机 SSID 通常为 `Lynk&Co`）
3. 车辆挂 **P 挡**、车机屏幕保持唤醒
4. 打开 App → 阅读并同意风险提示 → 点「一键连接车机」
5. 车机屏幕若弹出「允许调试」授权框，点**允许**（建议勾选"始终允许"）
6. 连接成功后，在卡片②选择安装位置（屏幕）→ 选择 APK 安装，或一键安装全屏工具

## 技术说明

### 车机发现

只认 WiFi 网络且带 IPv4 的连接；**第一候选是默认网关**（手机连车机热点时，网关即车机），未命中再按本机 IP 前三段并发扫描 `1..254` 的 `:5555` 端口（48 并发 / 单次 450ms 超时）。

### ADB 客户端

`phone/src/.../AdbClient.java` 为自实现的最小 ADB 客户端（无三方库）：

- 24 字节消息头（小端）：`cmd / arg0 / arg1 / data_length / crc32 / magic(=cmd ^ 0xFFFFFFFF)`
- 握手 `CNXN`；认证 `AUTH`（`TOKEN` → `SIGNATURE`，必要时发 `RSAPUBLICKEY` 触发车机屏授权）
- 流操作：`OPEN` / `WRTE` / `OKAY` / `CLSE`，支持 `shell:` 执行命令与 `sync:` 推送文件
- **密钥持久化**：RSA 密钥由 `KeyProvider` 落盘（手机端存 SharedPreferences）。密钥一变，车机就会重新弹授权框，所以**必须**持久化——一次授权长期免弹。
- **推送用 `SEND_V1` 协议，载荷格式为 `<路径>,<八进制权限>`**（例 `/data/local/tmp/a.apk,0644`），不是"长度前缀路径 + 二进制 mode"。权限串**必须带前导 `0`**：adbd 用 `strtoul(s, NULL, 0)` 解析，`644` 会被当成十进制。格式写错时 adbd 会回 `FAIL missing , in ID_SEND_V1` 或直接断连接。
- 单块上限取 adbd 通告的 `maxdata` 与 `SYNC_DATA_MAX`(64KB) 的较小值
- 所有流的 `OKAY`/`CLSE` 都按 `arg1 == 本端 local id` 判归属——上一条流（如同步推送）滞后的报文不能被下一条流误认，否则 `shell()` 会静默返回空
- **不要等车机的应答来判定推送成败**：AOSP 的 host 客户端（`client/file_sync_client.cpp`）推文件时全程 `WriteOrDie` 不等回复，现代 adbd 对 `SEND` 请求**根本不回**（只在 `DONE` 之后写一次 `OKAY`），服务流结束也**可能不发 `CLSE`**。本工具只"软等"传输层流控应答，**以车机上文件字节数 / `pm install` 输出为准**。
- 安装走三种方式阶梯：**A 流式安装** `exec:cmd package install -S <size> -r --user N`（与 `adb install` 同一条路，不落临时文件）→ **B** `cat > /data/local/tmp/xxx.apk` 推送 + `pm install` → **C** `sync` 推送 + `pm install`

### 测试

```bash
./tests/run.sh      # 桌面联测：用 mock_adbd.py 验证客户端协议实现
```

`tests/mock_adbd.py` 按 AOSP `packages/modules/adb` 的语义复刻了一个 adbd（CNXN/AUTH 验签/`SEND_V1` 逗号解析/shell 流/流控），`tests/TestMain.java` 跑 13 项断言：首次公钥授权、shell 通道、200KB 同步推送逐字节一致、150KB shell 兜底推送一致、**设备完全静默时推送仍完成**、**服务不发 CLSE 时立即返回**、流式安装可用、`FAIL` 原文透传、跨流隔离、复用密钥不再弹框。`AdbClient.DEBUG = true` 可打印收发报文。

### 屏幕空间编号

车机多屏系统的用户空间编号约定：

| 空间号 | 屏幕 |
|---|---|
| 12 | 中控 |
| 13 | 副驾 |
| 100 | 后排娱乐屏 |
| 101 | 中控和副驾 |
| 其他 | `user N` |

前排热点下可安装到 12 / 13；后排娱乐屏热点下为 100。App 会按当前连接的设备类型与实机存在的空间自动筛除不可用项。

### 全屏实现

车机端通过系统服务 `geely_multi` 的隐藏 AIDL 接口 `android.view.IGeelyMultiManagerExt` 调整显示区域：

- 区域常量：`1001` 中控 / `1002` 副驾 / `1003` 全屏
- 全屏 = `moveScreen2Screen(区, 1003, false)`（`transact(6)`，第三参固定 `0`）；还原 = `moveScreen2Screen(1003, 原区, false)`
- 查询顶层应用 = `getTopPkgName(区)`（`transact(4)`）；以 `Binder.transact` 直调，必要时回退反射 AIDL 接口

**⚠️ 它搬的是「该区域当前显示的那个页面」，所以触发时机决定成败：**

- 在本工具自己的 Activity 前台时调用 → 只会搬走本工具自己的窗口，用户一换 App 全屏就消失；
- 正确做法是**悬浮球**：`TYPE_APPLICATION_OVERLAY`(=2038) 浮层不是 Activity，不抢「顶层应用」，
  用户可在任意 App 前台时点它，搬走的才是那个 App（LIGHTBOX / ONE BOX 即此做法）；
- 完整流程：点球 → 读 `getTopPkgName(1001/1002)` 选目标区（排除自己/launcher/systemui）→
  **轮询等页面稳定（≤2.6s，120ms）** → 搬屏 → **800ms 后复核 `getTopPkgName(1003)`**，
  不符则回滚并提示「该应用不支持全屏（系统限制）」→ 常驻通知「全屏中 · 应用」+「退出全屏」。

悬浮球需要 `SYSTEM_ALERT_WINDOW`：手机端装完全屏工具会自动执行
`appops set --user N com.carhelper.fullscreen SYSTEM_ALERT_WINDOW allow` 并
`am start-foreground-service …/FullscreenService`；也可在车机端打开「车机助手·万物全屏」点①②手动授权。

## ⚠️ 免责声明

- **驾驶风险**：请在车辆停稳、挂 P 挡时操作。行车中操作或观看第三方应用内容会分散注意力，可能导致交通事故。
- **车机风险**：第三方应用可能存在兼容性问题，影响车机系统稳定性。请自行评估并承担风险。
- **保修风险**：车辆厂家可能对"因第三方应用导致的故障"不予保修。本工具不越权、不破解、不修改系统组件，但装车行为本身仍可能引起保修争议。
- **适用范围**：已在**领克 900** 实机验证可用；其他领克 / 吉利 / 极氪车机（**高通 8295 芯片 + Flyme Auto 2.0 及以上系统**）可能适用，但**待验证**。其他平台不保证可用（屏幕空间编号、系统服务名可能不同）。
- 本项目仅供**自有车辆**的互操作与学习研究使用，请在遵守当地法律法规与车辆保修条款的前提下使用。

## 许可

[MIT](LICENSE)

本项目**仅提供源代码**，不提供预编译安装包 —— 请按上方构建说明自行编译。
