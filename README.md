# 车机助手 · CarHelper

一个面向 **吉利 / 领克（亿咖通 ECARX 平台）Android 车机** 的开源小工具：

> **手机连车机热点 → 通过 ADB 安装 APK 到指定屏幕空间 → 一键全屏**

由两个部分组成，手机端已内置车机端 APK，**只需安装一个 App**：

| 组件 | 包名 | 说明 |
|---|---|---|
| 手机端 App | `com.carhelper.phone` | 发现车机、连接 ADB、安装 APK、管理应用授权空间 |
| 车机端 APK | `com.carhelper.fullscreen` | 一键全屏 / 还原（通过车机自带的多屏管理服务调整显示区域） |

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
- 全屏 = `moveScreen2Screen(区, 1003)`；还原 = `moveScreen2Screen(1003, 区)`
- 以 `Binder.transact` 直调，必要时回退到反射 AIDL 接口

## ⚠️ 免责声明

- **驾驶风险**：请在车辆停稳、挂 P 挡时操作。行车中操作或观看第三方应用内容会分散注意力，可能导致交通事故。
- **车机风险**：第三方应用可能存在兼容性问题，影响车机系统稳定性。请自行评估并承担风险。
- **保修风险**：车辆厂家可能对"因第三方应用导致的故障"不予保修。本工具不越权、不破解、不修改系统组件，但装车行为本身仍可能引起保修争议。
- **适用范围**：针对吉利/领克亿咖通平台车机开发，其他车型/固件上不保证可用（空间编号、系统服务名可能不同）。
- 本项目仅供**自有车辆**的互操作与学习研究使用，请在遵守当地法律法规与车辆保修条款的前提下使用。

## 许可

[MIT](LICENSE)
