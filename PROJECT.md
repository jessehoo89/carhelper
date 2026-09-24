# 车机助手（CarHelper）· 开发笔记

> 面向吉利/领克（亿咖通 ECARX 平台）Android 车机的 ADB 装机 + 一键全屏工具。
> 用户文档见 [README.md](README.md)。

## 架构

```
carhelper/
├── build/
│   ├── build.sh          # 纯命令行构建（无 Gradle）
│   ├── sdk/              # Android SDK 组件（gitignore）
│   ├── keystore/         # 调试签名，首次构建自动生成（gitignore）
│   └── out/              # 构建产物（gitignore）
├── phone/                # 手机端 App（com.carhelper.phone）
│   ├── AndroidManifest.xml
│   ├── assets/           # 构建时自动放入车机端 APK
│   └── src/com/carhelper/phone/
│       ├── MainActivity.java   # UI + 流程编排（卡片①连接 ②安装 ③授权管理 ④日志）
│       ├── AdbClient.java      # 自实现最小 ADB 客户端
│       └── CarFinder.java      # 车机发现（网关优先 + 网段兜底）
└── car/                  # 车机端 APK（com.carhelper.fullscreen）
    └── src/com/carhelper/fullscreen/MainActivity.java
```

## 构建流水线

`aapt2 compile`（资源）→ `aapt2 link`（生成 R.java + 资源 APK）→ `javac -source 8 -target 8 -bootclasspath android.jar` →
`d8 --min-api 24`（转 dex）→ `zip` 注入 `classes.dex` 与 `assets/` → `zipalign -f 4` → `apksigner`（v1+v2）。

### 踩坑记录

1. **镜像文件名陷阱**：腾讯云 `AndroidSDK/android-14_r03.zip` 解出来实际是 **Android 4.0.2**（17MB），必须用 `platform-34-ext7_r02.zip`（解出 `android-34/android.jar`，26MB）。
2. **aapt2 link 的 SDK 版本**：manifest 里已带 `<uses-sdk>`，命令行不要再传 `--min-sdk-version`，否则可能冲突。
3. **中文源码**：`javac` 需显式 `-encoding UTF-8`，否则中文注释/字符串会编译报错。
4. **签名必须稳定**：换 keystore 会导致升级安装失败（签名冲突）。`build/keystore/` 不入库但请自行备份。
5. **assets 入包**：手机端 `assets/carhelper-fullscreen.apk` 由 `build.sh all` 自动从车机端产物复制，不要手工维护。

## 关键实现

### 1. ADB 客户端（`AdbClient.java`）

- 消息头 24 字节小端：`cmd / arg0 / arg1 / data_length / crc32 / magic`，`magic = cmd ^ 0xFFFFFFFF`
- 握手：`CNXN(version=0x01000000, maxdata=256KB, "host::features=shell_v2,cmd,stat_v2")`
- 认证：`AUTH(1=TOKEN)` → 回 `AUTH(2=SIGNATURE, SHA1withRSA(token))`；若服务端不认，回 `AUTH(3=RSAPUBLICKEY)`，此时**车机屏会弹授权框**
  - 公钥为 ADB 私有结构：`base64{ len(LE), n0inv(LE), nlen(LE), n(BE), rr(LE), e(LE) } + " <banner>\0"`
  - `n0inv = -n⁻¹ mod 2³²`；`rr = R² mod n, R = 2^(32·nlen)`
  - 密钥进程内生成、不落盘；adbd 记住该公钥后免重复授权
- 流：`OPEN(localId, 0, "shell:<cmd>\0" | "sync:\0")` → `OKAY(remoteId, localId)` → `WRTE/OKAY` 往返 → `CLSE`
- 装机：`sync` 推送 APK 到 `/data/local/tmp/` → `pm install -r --user N <path>` → 输出含 `Success` 即成功
- 已知可优化点：每条 `WRTE` 等 `OKAY`（同步流控），大包（60MB+）推送偏慢，可改为窗口化。

### 2. 车机发现（`CarFinder.java`）

- 只取 `hasTransport(TRANSPORT_WIFI)` 且有 IPv4 的网络
- **第一候选 = 默认网关**（手机连热点时网关即车机，通常一步命中）
- 未命中则按**本机 IP 前三段** + `1..254` 并发探测 `:5555`（48 线程 / 单次 450ms / 每个 Future 等 3s）
- `network.bindSocket(socket)` 保证探测走 WiFi 而非蜂窝
- ⚠️ 不要硬编码网段（不同车机热点网段不同，实测见过 `172.21.204.x`）

### 3. 设备类型与可用空间（`MainActivity.probeDevice()`）

一次性采集：

```
getprop ro.serialno / ro.product.model / ro.product.name / ro.product.device
am get-current-user
pm list users
pm path --user 0 com.desaysv.launcher     # 德赛西威 launcher = 后排屏桌面
```

判定：

- `com.desaysv.launcher` 存在 → **后排娱乐屏**（可安装空间 100，兜底 101 / 0 / 10 / 全部）
- 否则 → **前排**（中控 12 / 副驾 13）

空间命名统一走 `spaceName(uid)`：`12→中控 13→副驾 100→后排娱乐屏 101→中控和副驾 其他→user N`。

### 4. 一键全屏（车机端）

- `bypassHiddenApi()`：反射 `dalvik.system.VMRuntime.setHiddenApiExemptions(["L"])`
- `ServiceManager.getService("geely_multi")` → 裸 `Binder.transact(6)`，
  data = `writeInterfaceToken("android.view.IGeelyMultiManagerExt") + int from + int to + int flag`
- 区域：`1001` 中控 / `1002` 副驾 / `1003` 全屏
- 查询顶层应用：`transact(4)` → `getTopPkgName(area)`，回退 AIDL 反射

## 待办

- [ ] 大包推送性能：`WRTE` 流控改为窗口化
- [ ] 后排空间命名按实机校准（不同固件的空间编号可能不同）
- [ ] 装机后校验：`pm path --user N <pkg>` 确认落点
- [ ] 多屏批量安装（一次装到多个空间）
- [ ] 图标与深色/横屏适配打磨

## 许可

MIT
