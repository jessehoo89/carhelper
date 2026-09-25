# 车机助手 · CarHelper

> ⚠️ 本项目为**独立第三方开源工具**，与浙江吉利控股集团、领克汽车、极氪汽车、亿咖通科技（ECARX）、魅族科技均无任何关联，亦未获其授权或认可。仅供**自有车辆**的互操作与学习研究使用。

车机助手是一个面向 **吉利 / 领克（亿咖通 ECARX 平台）Android 车机** 的开源小工具：手机连上车机热点后，通过 ADB 把 APK 安装到指定屏幕空间、管理各屏幕空间的应用授权，并支持一键「万物全屏」。已在 **领克 900** 实机验证；其他领克 / 吉利 / 极氪车机可能适用。

| 组件 | 包名 | 说明 |
|---|---|---|
| 手机端 App | `com.carhelper.phone` | 连接车机、安装 APK、空间授权管理、运行日志 |
| 车机端 APK | `com.carhelper.fullscreen` | 一键全屏（常驻悬浮球） |

车机端 APK 已内置在手机端里，**手机只需安装一个 App**。

## 功能一览

### 手机端

- **一键连接车机**：自动检测热点 → 发现车机 → 连接 ADB → 识别可用屏幕空间；任一步失败都会给出具体原因
- **连接保持**：ADB 密钥本地保存，授权一次长期有效；连接中断后会自动重连，无需手动操作
- **安装应用到车机**：选择本地 APK，安装到指定屏幕空间（主驾/中控、副驾等），显示传输进度与安装结果
- **一键安装「全屏工具」**：把内置的车机端全屏 APK 装到车机，并自动完成悬浮窗授权与启动
- **应用授权空间管理**：按屏幕空间查看已装应用，支持批量授权、取消授权、彻底卸载；可诊断应用在各空间的安装状态，并刷新车机桌面让应用立即出现
- **运行日志**：一键复制日志，便于反馈问题
- **首次启动风险告知**：确认后方可使用

界面为主页 + 三个独立功能页（安装应用、应用授权空间管理、运行日志），各功能页内显示执行状态。

### 车机端

- **万物全屏**：常驻悬浮球，在任意应用界面点一下即把当前页面铺满全屏，再点一次还原；通知栏也可退出全屏
- 屏幕上没有可全屏的页面（例如停在桌面）时会给出提示，不受支持的应用会自动回滚并提示

## 编译指南

**不需要 Gradle**，纯命令行流水线：`aapt2 → javac → d8 → zipalign → apksigner`。

环境要求：JDK 17+、`zip`、`curl`。

```bash
# 1) 准备 Android SDK 组件（约 90MB，只需一次）
mkdir -p build/sdk && cd build/sdk
curl -LO https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-linux.zip
curl -LO https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r02.zip
unzip -q build-tools_r34-linux.zip && mv android-14 build-tools
unzip -q platform-34-ext7_r02.zip && mv android-34 platform-34
cd ../..

# 2) 编译（首次会自动生成调试签名）
./build/build.sh all      # 也可只编 car 或 phone
# 产物：build/out/carhelper-phone.apk、build/out/carhelper-fullscreen.apk
```

`build/sdk`、`build/out`、`build/keystore` 均不入库。签名用 `build/keystore/debug.keystore`（首次编译自动生成，**后续升级请沿用同一份**，建议自行备份）。

## 项目声明

- 本项目为独立第三方开源工具，与吉利、领克、极氪、亿咖通（ECARX）、魅族等厂商**均无关联**，未获其授权或认可
- 仅限**自有车辆**使用；请在车辆停稳、挂 P 挡时操作，行车中操作或观看第三方应用内容有安全风险
- 本工具**不修改车机系统组件、不关闭安装校验器、不破解任何校验**，也不收集或上传任何数据（仅连接车机 ADB 端口）
- 第三方应用的兼容性与装车后果由使用者自行评估承担；厂家可能以此为由拒绝相关保修
- 已在领克 900 实机验证；其他车型可能适用，但不作保证
- 许可：[MIT](LICENSE)。本项目**仅提供源代码**，不提供预编译安装包

详细的风险说明见 [DISCLAIMER.md](DISCLAIMER.md)。
