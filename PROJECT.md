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

## v1.0.1（2026-09-25）：实机反馈三连修 + 桌面联测

实机反馈三个问题，两个是协议层真 bug：

### ① 每次连接都重弹车机授权框
`ensureKey()` 原来**每次进程内现生成 RSA 密钥、不落盘** → 每次启动/连接都是新公钥，adbd 自然不认识 → 必然弹框（车机里记住的是旧公钥）。
修：新增 `AdbClient.KeyProvider` 接口（`loadPrivate/loadPublic/save/onAuthRequested`），手机端用 SharedPreferences 存 PKCS#8/X.509；连接时先私钥签名（已授权就直接过），不被认可才发公钥。`onAuthRequested()` 在发公钥那一刻提示用户去车机屏点"允许"。日志额外打印密钥指纹（SHA256 前 8 字节）便于核对"密钥有没有变"。

### ② 安装失败 `sync 失败: FAIL missing, in ID_SEND_V1` / `Software caused connection abort`
根因（有 AOSP 源码为据，`packages/modules/adb/daemon/file_sync_service.cpp`）：

```cpp
static bool do_send_v1(int s, const std::string& spec, ...) {
    // 'spec' is of the form "/some/path,0755". Break it up.
    size_t comma = spec.find_last_of(',');
    if (comma == std::string::npos) { SendSyncFail(s, "missing , in ID_SEND_V1"); return false; }
```
`file_sync_protocol.h` 亦注明：*"send_v1 sent the path in a buffer, followed by a comma and the mode as a string."*

即 **SEND_V1 的 path 字段整串 = `"<路径>,<八进制权限>"`**（`handle_sync_command` 先读 `SyncRequest{id, path_length}`，再读 `path_length` 字节的 name，这个 name 就是 spec）。旧实现按 `path_length+path+uint32 mode` 发，没有逗号 → 直接 FAIL；另一路直接掐连接（就是那个 "connection abort"）。
修：`spec = path + ",0" + Integer.toOctalString(mode & 0777)`（**前导 0 必须有**：adbd 用 `strtoul(s, NULL, 0)`，`644` 会被当十进制 644）。

同时加固（都是这次联测逼出来的）：
- **流 id 归属判定**（真 bug，已修）：`shell()` 里 `if (remote < 0) remote = m.arg0;` 没校验 `m.arg1 == local`，于是**上一条流（sync）滞后的 OKAY 会被当成本流的 OPEN-OKAY**，remote 拿到错 id，紧接着的收尾 CLSE 又被当成"本流关闭" → `shell()` 静默返回空串。所有流的 OKAY/CLSE 现在都按 `arg1 == local` 判归属；`push()`/`pushViaShell()` 结束加 `drainTrailing()` 排空收尾报文。
- 分块上限取 adbd 通告的 maxdata 与 `SYNC_DATA_MAX(64KB)` 的较小值；等流控 ACK 期间先到的 sync 应答**入队**（原来会被静默丢，可能挂死）。
- 推送失败自动回退 **shell 流通道**（`shell:cat > /tmp/xxx.apk`，与一键连接同一条已验证通道）。
- 推送后核对车机侧文件大小（`stat -c %s` 兜底 `ls -l`），`pm install` 报错翻译成人话（签名冲突/存储不足/`-t` 自动重试 INSTALL_FAILED_TEST_ONLY 等），大包每 10MB 报进度。

### ③ 授权空间管理不显示"空间号 ↔ 主驾/副驾/后排娱乐屏"
新增 `spaceLabel(uid)`：`0 主驾(司机) / 10 访客 / 12 主驾·中控 / 13 副驾（识别 "<主空间>_clone" 克隆关系，按 LIGHTBOX 的"副驾=中控+1"先例）/ 100 后排娱乐屏 / 101 中控+副驾`。行标题形如 `13 · 副驾屏（12 主驾/中控空间的克隆） ✱当前活跃`，下方小字给**设备原始空间名**，上方加图例并声明"标签是推断、以实车为准"；每次加载刷新 `am get-current-user` / `pm list users`，并把原始信息 + `dumpsys activity` 的 display↔user 旁证打进日志（便于远程校准标签）。

### 桌面联测工具（不随 APK 发布）
`/tmp/adbtest/`：`mock_adbd.py` 按 AOSP 语义复刻 adbd（CNXN/AUTH 验签/sync SEND_V1 逗号解析/shell 流），`android/util/Base64.java` 是给桌面 JDK 的桩，`TestMain.java` 跑 7 项断言。用法见 `PROJECT.md` 下表；`AdbClient.DEBUG=true` 可打收发报文。

踩过的 mock 坑（写同类测试时注意）：真 adbd 的 sync/shell **读的是被 adb 拆包后的 fd**，mock 必须自己解 WRTE 并逐包回 OKAY（`Stream` 类）；接受 OPEN 后必须先回 OKAY，否则客户端不会开始写 stdin；Python `int(s, 0)` **不接受** `"0644"` 前导 0（C 的 `strtoul(base 0)` 接受），要自己实现 `c_strtoul`；服务结束后客户端会回一个 CLSE，mock 不能当异常断连接（真 adbd 忽略）。

联测结果：首次连接走公钥授权 ✅ / shell 通道 ✅ / sync 推送 200KB 逐字节一致 ✅ / shell 兜底 150KB 逐字节一致 ✅ / FAIL 原文透传 ✅ / sync 之后 shell 仍正常 ✅ / 复用持久化密钥不再弹框 ✅。

## 待办

- [ ] 实机复验 v1.0.1：二次连接不再弹授权框、装机成功、空间标签正确
- [ ] 大包推送性能：`WRTE` 流控改为窗口化
- [ ] 后排空间命名按实机校准（不同固件的空间编号可能不同）
- [ ] 装机后校验：`pm path --user N <pkg>` 确认落点
- [ ] 多屏批量安装（一次装到多个空间）
- [ ] 图标与深色/横屏适配打磨

## 许可

MIT
