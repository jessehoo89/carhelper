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


## v1.0.2（2026-09-25 晚）：实机第二次反馈 —— 别再等车机应答

实机（领克900）反馈：v1.0.1 授权只弹一次 ✅、空间标签正确 ✅，但**装不上**：
日志里 `sync 推送失败: Read timed out` → 回退 shell 流 → `已推送 50 MB…` → `安装超时`。同一台车用 HiSH 的 `adb install --user 12`（streamed install）却能成功。

### 根因（这次是"等错了东西"，AOSP host 端源码为准）

拉 `client/file_sync_client.cpp` 对照，**AOSP 自己的 host 客户端推文件时从不等待任何应答**：

- `SendSmallFile`：把 `SEND_V1{id,path_length}` + `"<路径>,<权限>"` + `DATA` + `DONE` 一次性拼进 buffer，`WriteOrDie(...)` 发完就返回；
- `SendLargeFileLegacy`：`SendRequest(ID_SEND_V1,…)` → 循环 `WriteOrDie(DATA)` → `WriteOrDie(DONE)`，**全程不读**；
- 应答是异步的（`deferred_acknowledgements_`），而现代 adbd 的 `daemon/file_sync_service.cpp` 里 `ID_OKAY` **只在 DONE 之后写一次**（`do_send_v1`→`send_impl`→`handle_send_file` 末尾），**对 SEND 请求本身完全不给回复**；
- 服务流结束时设备也**可能不发 CLSE**。

而 v1.0.0/v1.0.1 的客户端"每个请求都等应答" → 车机不回就干等到 60 秒超时：`sync 推送失败: Read timed out`（SEND 之后等不到 OKAY）、`安装超时`（cat 推送完等不到 CLSE）。数据其实**早就传过去了**，是我们自己在等一个永远不会来的报文。

### 修法：一律以"车机上的事实"判定成败，不以应答为准

1. `push()`（sync）：OPEN 后拿到 remote id 即发 SEND/DATA/DONE，每步只**软等**传输层 OKAY（流控，超时不算错）；结尾给 2.5s 收 FAIL（真被拒才抛错）；`QUIT`+`CLSE` fire-and-forget。
2. `streamToService(service, src, progress, tailWaitMs)`：通用二进制流（用于 `cat > 文件`、`pm install -S`、`pm install 文件`）。写完 stdin 后**只等一小会儿且不把超时当失败**，收尾判据 = 拿到 `Success`/`Failure`，或"已有输出且静默 >10s"，或到点。
3. 安装改三方式阶梯，任一种成功即止，**每种的成败都用车机上文件大小 / pm install 输出核对**：
   - **A 流式安装**（就是 HiSH 里成功的那条路）：`exec:cmd package install -S <size> -r --user N`，stdin 直喂 APK，不落临时文件；失败再试 `pm install -S`。
   - **B 推送再装**：`shell:cat > /data/local/tmp/xxx.apk` → `stat` 核对字节数 → `pm install -r --user N <path>`。
   - **C sync 推送再装**：容错版 sync → 核对字节数 → `pm install`。
4. `pmInstall()` 也走 `streamToService`（不依赖 CLSE）。
5. 日志加 `[+Ns]` 时间戳、安装时**打印文件名与大小**（上一版没写装的是哪个包，用户看不出在装谁）；`sizeOf` 读不到就不走 A 方式。

### 新增回归测试（`tests/`，13 项全绿）

`mock_adbd.py` 现在按**真机行为**复刻：SEND 不回任何东西；`SILENT` 路径完全静默（模拟本车机）；`NOCLSE` 服务结束不发 CLSE；支持 `cmd package install -S <n>`（读满 size 才回 Success）。新增断言：
- 设备对 sync **完全静默**时，180KB 推送不阻塞且内容逐字节一致 ← 直接复现本次实机故障；
- 服务不发 CLSE 时，shell 推送 1.5s 内返回且内容一致；
- 流式安装路径（`exec:cmd package install -S`）可用。


## v1.0.3（2026-09-25 深夜）：v1.0.2 实机日志定案 —— 本车机不能用"文件"装，只能用"流"

v1.0.2 实机日志给出了完整链条（这次信息量极大）：

```
[+11s] [B] 已推送 16811 字节 / 车机侧 16811 字节          ← 传输完全正确（字节数一致）
[+11s] [B] pm install 未成功：…om fd 1005
       at android.content.res.ApkAssets.nativeLoadFd(Native Method)
       at android.content.res.ApkAssets.<init>(ApkAssets.java:306)
       at android.content.pm.parsing.ApkLiteParseUtils.parseApkLiteInner(...)
       at com.android.server.pm.PackageManagerShellCommand.setParamsSize(...)
```

1. **传输没问题**：16811 == 16811，说明 `shell:cat >` 通道字节完全透明（顺带证明这台车的 `shell:` 不是 PTY，否则 CR/LF 转换会改变字节数）。
2. **车机侧 `pm install <文件路径>` 会炸**：不带 `-S` 时，`PackageManagerShellCommand.setParamsSize()` 要先 `parseApkLite` 解析那个文件来推断大小，本固件上这一步 `nativeLoadFd` 直接失败（与 APK 内容无关，字节数核对过）。
   ⇒ **这正好解释了为什么 HiSH 的 `adb install`（`Performing Streamed Install`，带 `-S`，不解析文件）能成功**：带 `-S` 时 sizeBytes 已知，`setParamsSize` 不走解析那条路。
3. **而 v1.0.2 把流式安装（方式 A）跳过了**：内置全屏工具 APK 被 zip 压缩存进 assets，`getAssets().openFd()` 取不到长度 → `knownSize = -1` → `[A] 跳过流式安装（未知文件大小）` → 只能落到注定失败的文件方式。

### 修法（v1.0.3）

- **方式 0：拿准字节数**。size 未知时先把数据缓存到手机本地（`getCacheDir()`）量准长度，再做流式安装 —— 内置资产、任何来源的 URI 都适用，A 方式从此永远可用。
- 流式安装三种通道依次尝试：**A1** `exec:cmd package install -S <size> -r --user N` → **A2** `exec:pm install -S …` → **A3** `shell:cmd package install -S …`（`shell:` 已证明字节透明，是稳妥后备）。
- 文件方式（B `cat >` + `pm install 文件`、C sync + `pm install 文件`）降级为最后兜底，并在报错里显式提示"本车机从文件装会解析失败，优先流式"。
- 报错原文改为「首 300 + 末 900 字符」，避免真正的原因被截掉（v1.0.2 那次就是被截成 `…om fd 1005`）。

### 结论沉淀（写代码/做同类工具时能直接复用）

- **能用流式（`-S`）就别用"先推文件再 `pm install 文件路径`"**：前者不解析文件、不占临时空间，且在部分车机固件上是唯一可行的路。`adb install` 打印的 `Performing Streamed Install` 就是在告诉你走的是这条。
- 判定"传输是否成功"用**车机侧文件字节数**，不要用车机回不回应答（见 v1.0.2 那节）。


## v1.0.3 实机验证通过（2026-09-25 深夜）

用户回执：**安装成功** ✅（此前 v1.0.1 的授权只弹一次、空间标签也已确认）。至此三个反馈问题全部闭环。

### 与 LIGHTBOX 的对照（回答"为什么不直接照抄"）

**结构层面本来就是照 LIGHTBOX 的**：网关优先发现 + 48 并发/450ms 探测、RSA 密钥 + `AUTH(2)/(3)` 握手、
`pm list users`/`am get-current-user` 多空间编排、`pm install-existing --user N`、空间映射 12/13/100/101，
都来自 `car-hu-api-research/findings/02`。真正踩坑的是**装机机制的分支选择**：

LIGHTBOX 有两条路（`s1/t.java:84-117`），**按 `cmd` 是否支持二选一**：

| 路径 | LIGHTBOX 实现 | 领克900 实测 |
|---|---|---|
| **首选：流式安装** | `exec:cmd package install -S <本地文件 length> [-r --user N]`，APK 从 stdin 流进去，判 `Success` | ✅ 可行（= HiSH `adb install` 那条路） |
| 兜底：文件方式 | `sync:` 推到 `/data/local/tmp/x.apk` → `pm install "<路径>"`（`i2/l.java:401-434`） | ❌ 车机崩在 `setParamsSize→parseApkLite→ApkAssets.nativeLoadFd` |

**我一开始把"兜底路"当主干做了**（classic `adb push` 风格），于是连续两轮实机都卡在这台车不支持的
文件安装上；最终收敛到 LIGHTBOX 的**首选路**，连两个细节都撞上了同一个结论：
① 服务名 `exec:cmd`；② `-S` 必须给**本地文件的精确长度**（我们也因此加了"size 未知就先缓存到本地量准"）。

教训：**装机优先用平台自家工具（`adb install`）走的那条路**——带 `-S` 的流式安装，不解析文件、不占临时空间；
"先推文件再 `pm install 文件路径"` 只能当兜底，别当主干。


## v1.0.4 + 车机端 v1.0.1（2026-09-25 深夜）：「万物全屏」为什么必须用悬浮窗

### 现象与根因

车机端老版本（点自己 App 里的按钮）实测：**只把当前窗口铺满，切到别的 App 全屏就没了**。
根因在 `geely_multi.moveScreen2Screen(from, to, false)` 的语义：
> 它搬的是**该区域此刻正在显示的那个页面/窗口**。

老版本是「打开我们自己的全屏工具 → 点按钮」，那一刻 1001 区的顶层应用**就是我们自己**，于是系统搬走的只是我们自己的窗口；换 App 后顶层窗口变了，全屏状态自然消失。**跟参数、跟服务都无关，是「什么时候发起调用」的问题。**

### LIGHTBOX / ONE BOX 的做法（对照结论）

| 环节 | 实现 | 出处 |
|---|---|---|
| 触发入口 | **悬浮球（TYPE_APPLICATION_OVERLAY=2038）+ 前台服务**——浮层不是 Activity，不抢「顶层应用」，用户可在任意 App 前台时点它 | MAX `FsFloatService.java:416/432` |
| 搬屏调用 | `moveScreen2Screen(from, to, false)`（transact 6，第三参写 0） | MAX `C2.java:51-60`、ONE BOX `GeelyFs.java:44-52` |
| 搬前等稳定 | 轮询 `topPkg(from)` 最多 2.6s、120ms 一次，直到等于预期包名 | MAX `RunnableC0139c2.java:29-41` |
| 搬后复核 | 等 800ms 看 `topPkg(to)` 是否为目标包；不符则提示「该应用不支持屏幕搬移」（ONE BOX 版还会回滚） | `RunnableC0139c2.java:52-58`、ONE BOX `MainActivity.java:1541-1546` |
| 状态与退出 | 前台服务 + 常驻通知「全屏中 · 应用名」+「退出全屏」→ `moveScreen(1003, 原区)` | MAX/ONE BOX `FsService.java` |
| 可搬判据 | 排除自己 / launcher / systemui / packageinstaller / permissioncontroller | MAX `C2.java:35` |
| 装后配置 | `appops set --user N <pkg> SYSTEM_ALERT_WINDOW allow` + `am start-foreground-service …` | findings/02 第 7 步 |

### 本项目改动

**车机端（com.carhelper.fullscreen v1.0.1，20,907 字节）**
- 新增 `Geely.java`：geely_multi 调用桥（moveScreen 第三参固定 0、topPkg、isTransferable 排除表）。
- 新增 `FullscreenService.java`：悬浮球（可拖动、点击=全屏/还原）+ 前台服务 + 常驻通知，内含「等稳定 → 搬屏 → 800ms 复核 → 不符回滚」完整流程；服务被回收时自动还原，避免屏幕卡在全屏。
- `MainActivity` 改为控制台：状态（服务/权限/全屏状态/三区顶层应用）+ ①授予悬浮窗 ②启动悬浮球 + 还原 / 停止。
- Manifest 加 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`，服务 `exported=true`（便于 adb 直接拉起）。

**手机端（com.carhelper.phone v1.0.4，57,872 字节）**
- 装完全屏工具后自动做装后配置：appops 授悬浮窗 + am start-foreground-service 拉起悬浮球，并 appops get 复核、结果打进日志。
- 卡片②新增「授予悬浮窗权限 + 启动悬浮球」按钮（不重装也能补配置）。

### 待实机验证

1. 用手机端重装全屏工具（会自动授权+启动悬浮球）或点新按钮；
2. 车机上切到任意 App（如网易爆米花）→ 点悬浮球 → 应铺满全屏，**且切到别的 App 后仍是全屏**（搬的是「区域」而不是窗口）；
3. 再点悬浮球（或通知里的「退出全屏」）还原；
4. 若搬屏后换 App 仍掉全屏 → 说明本车机 `moveScreen2Screen` 是「一次性窗口搬移」语义，那就给悬浮球加「监听顶层应用变化自动重放」的守护（LIGHTBOX 里没有这一步，故先不加，避免多余系统调用）。


## v1.0.6（2026-09-25 晚）：**ADB 公钥格式写错了 —— 这才是"每次连接都重弹授权框"的真因**

用户回执：改造版 App（箭头音乐）装不上 + **每次点「一键连接车机」都要重新认证指纹**。
逐条查证：

### ① 装不上 = 车机还留着官方同名应用（**不是我们的包有问题**）
实机日志（v1.0.4/1.0.5）：
```
[A] 流式安装：exec:cmd package install -S 56792449 …（传输 10/20/30/40/50 MB 正常）
[A] 未成功：Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.sumsg.musichub
             signatures do not match previously installed version; ignoring!]
[A2]/[A3] 同因；[B]/[C] 则是本车机"从 /data/local/tmp 读文件装"的老毛病（parseApkLite 失败，与文件无关）
```
⇒ A 方式**已经解析成功才报签名冲突**，说明包本身没问题；根因是 **`pm uninstall --user N` 只摘一个空间**，
任一空间还留着旧包就会一直报签名冲突。

**修法**：卡片③新增 **「彻底卸载（所有空间，清残留）」** —— 对每个空间 `pm uninstall --user N`，再 `pm uninstall <pkg>`（对所有用户）一次，
然后逐空间 `pm path --user N` + `pm list packages -u` 复核残留，日志明确告诉用户"各空间均已移除"还是"仍残留"。
安装失败时的提示也改为**优先报最有价值的原因**（把 A/A2/A3/B/C 五次尝试的输出汇总后再判断，避免被 B/C 的文件解析错误带偏）。

### ② 每次重弹授权 = **ADB 公钥 blob 结构写错**（我记错了 mincrypt 布局）

对照两份"在真机上有效"的实现在源码级定案：

| | 真实格式（AOSP `crypto_utils/android_pubkey.h` + LIGHTBOX `AbstractC0275a.o`） | 我 v1.0.0~v1.0.5 的实现 |
|---|---|---|
| 总长 | **524 字节**（`ANDROID_PUBKEY_ENCODED_SIZE`=4+4+256+256+4） | 276 字节 |
| 第 1 个字段 | `modulus_size_words = 64`（**32 位字数**） | `276`（字节数） |
| 结构 | n0inv + 256B 模数(**小端**) + 256B rr(**小端**) + e | n0inv + **多一个 nlen** + 256B 模数(**大端**) + **4 字节 rr** + e |
| `rr` | 完整 `2^4096 mod n`（R=2²⁰⁴⁸） | 只取了 `.intValue()` 低 32 位 |

后果：车机能弹授权框（它只按 blob 算指纹），但**存下来的公钥是垃圾**，之后每次验签都失败 → 每次重弹。
**签名算法本身是对的**：`daemon/auth.cpp:204` 用 `RSA_verify(NID_sha1, token, token_size, sig, …)`，
即 PKCS#1 v1.5 over **SHA1(token)** = Java 的 `SHA1withRSA`（LIGHTBOX 用 `RSA/ECB/NoPadding` 手拼前缀，等价）。

**修法**：`AdbClient.adbPublicKeyBytes()` 改为输出 524 字节小端 `android_pubkey`；
`tests/mock_adbd.py` 的解析器同步改成**严格校验**（长度=524、words=64、模数小端、n0inv、`rr == 2^4096 mod n`），
成为回归用例 —— 以后谁改回错误结构，联测立刻红。

### ③ 顺带的可观测性改进
- 卡片④新增 **「复制日志」（一键进剪贴板，长日志直接发人）+「清空日志」**；日志上限提到 12000 字符；
- 装前自检车机 `/data` 可用空间并打印（大包要留"临时文件+安装副本+解压 so"的余量，空间不足是常见失败原因）；
- 每个方式失败都把**车机原文**（首 300 + 末 900）打进日志。


## v1.0.7（2026-09-25 深夜）：应用列表查错空间 + 授权落盘一键判定

实机反馈两条，第一条是我的 bug：

### ① 「已读取 5 个空间、**0 个第三方应用**」——查错了用户空间
原来用 `pm list packages -3`：**不带 `--user` 时它只看 shell 自己的 user（默认 0）**。
而我们的应用都是用 `adb install --user 12` 装进**活跃空间 12** 的 → 在 user 0 视角下"没装" → 列表自然空。
**修法**：对**每个存在的用户空间**分别 `pm list packages -3 --user N`，再聚合：
- 列表项显示成 `com.xxx   [空间 12]`（多空间显示 `12/13`），顺便让「彻底卸载」知道要清哪些空间；
- 状态栏的"0 个第三方应用"随之正确。

### ② 「关掉 App 再打开又要重新 adb 认证」——新增一键判定责任方
新增卡片①按钮 **「检查车机是否已记住本机密钥」**：读车机 `/data/misc/adb/adb_keys`，
把里面每把钥匙的 base64 与**本机当前公钥**逐字比对，输出两种结论：
- `结论：车机已记住本机密钥 ✅` → 格式/签名链路没问题，弹框另有原因（把日志发我）；
- `结论：车机没有本机密钥记录` → **授权框里的「始终允许」没勾**（或点成了"仅本次/拒绝"）。
  ⚠️ 安卓 adb 授权框只点「允许」= 仅本会话有效，**一断开就失效**；必须勾「始终允许」才会写进 `adb_keys`
  —— 这正是"退出 App 再进又要重新认证"的直接解释（LIGHTBOX 的提示文案里也专门写了"请选择允许，并勾选始终允许"）。

同时 `AdbClient` 增加 **认证轨迹**（`authTrace`），连接后直接打进日志，例如：
```
AUTH(type=1,tokenLen=20) → 已发签名; → CNXN(设备就绪) [凭已存密钥签名通过]
AUTH(type=1,tokenLen=20) → 已发签名; AUTH(type=1,…) → 已发公钥(第1次,车机会弹框); → CNXN(设备就绪) [本次提交过公钥]
```
一眼就能看出车机是"认了我们的签名"还是"又要求公钥授权"。

### ③ 顺带修：日志中间步骤看不见
`stepLog()` 原来直接 `setText()`，**会覆盖整块日志**（导致"步骤 2/4 ✅…"等行看不到）。已改为走 `log()` 追加。


## v1.0.8（2026-09-25 深夜）：**签名语义双变体自适应 —— 免弹框的最后一块拼图**

v1.0.7 实机日志给出了决定性证据（用户提供）：

```
认证轨迹：AUTH(type=1) → 已发签名; AUTH(type=1) → 已发公钥(第1次,弹框); → CNXN [本次提交过公钥]
车机 /data/misc/adb/adb_keys：… QAAAAMFSxu+/wtJu… ← 本机密钥 ✅   （共 7 把）
```
- `QAAAA…` 解码首 4 字节 = `40 00 00 00` = **64** ✓ = 我们新写的 `android_pubkey`（字数为单位）→ **公钥格式已经对了，而且车机确实把它存下来了**；
- 但车机**仍然拒绝我们的签名**（AUTH(1) 又来一次）→ 退回"发公钥"→ 弹框。
- ⇒ 问题在**签名语义**：现代 adbd 用 `RSA_verify(NID_sha1, token, …)`（对 token 再哈希），而这台车机认的是**老 mincrypt 语义**——把 token 当作"已经算好的摘要"，块尾直接放 token。LIGHTBOX 正是用裸 RSA（`RSA/ECB/NoPadding` + 固定 DigestInfo 前缀 + token）来兼容这一类设备。

**修法（`AdbClient`）**：签名改双变体自适应，依次尝试，**都不行才发公钥**：
1. 变体1 = `SHA1withRSA`（PKCS#1 v1.5 over `SHA1(token)`）→ 现代 adbd；
2. 变体2 = 手工拼 `00 01 FF…FF 00 || DigestInfo(SHA1) || token` 后**裸 RSA 加密** → 老 adbd / 本车机。

**联测**：`mock_adbd.py` 增加 `MOCK_LEGACY_ONLY=1` 模式（只认变体2，模拟领克900），`tests/run.sh` 第二阶段专门跑它：
```
✅ 变体1 被拒后自动用变体2 验签通过（未再发公钥、无授权弹框）
```
⚠️ 顺带修了测试脚本自身的 bug：客户端密钥文件与 mock 的已授权列表**原本共用同一个文件**、互相覆盖（导致此前老式阶段的结论不可信）——现已分成 `client_keys.txt` / `mock_keys.txt`。

**遗留观察**：`adb_keys` 里还有 4 把 `FAEAAA…`（解码首 4 字节 = 276）——那正是 v1.0.0~v1.0.5 我记错布局时写进去的垃圾公钥，无害，可留作"新旧格式都进过车机"的交叉证据。


## v1.0.11（2026-09-25 深夜）：免弹授权框成功 ✅ + 装机冲突元凶锁定为 KEEP_DATA 残留记录

### 免弹授权框：已确认修好
实机轨迹（用户提供）：
```
AUTH(type=1,tokenLen=20) → 已发签名(变体1); AUTH(type=1,tokenLen=20) → 已发签名(变体2); → CNXN(设备就绪) [凭已存密钥签名通过]
```
**没有"已发公钥"那一跳** → 车机接受了**变体2（老式 mincrypt 语义：token 当已算好的摘要）**。
⇒ 「ADB 公钥 524 字节小端格式」+「签名双变体自适应」两处一起才对；从此不必每次重新授权。

### 装机冲突：连"预置包"也不是，是**已卸载但保留记录**
用户反馈：`musichub` 在应用列表里搜不到（说明连系统/预置包都没有），但安装仍报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.sumsg.musichub signatures do not match previously installed version`。

根因（**我上一版漏了一个参数**）：`pm list packages` **默认不列"已卸载但保留数据"（KEEP_DATA）的记录**，
要 **`-u`** 才列得出来；而 PM 装包时会拿**这条残留记录里的签名**去比 → 所以"卸载了、列表也没有"照样冲突。

**修法**：
1. 全量包查询改成 `pm list packages -u --user N`（含残留记录）→ 现在筛选框输 `musichub` 能看到它（标 `[预置/系统或残留记录]`）；
2. 卡片③新增**包名输入框 + 「诊断 + 彻底卸载这个包名」**按钮 —— 列表里搜不到也能直接按包名操作；
3. 诊断结论三分类：`预置/系统同名包` / `KEEP_DATA 残留记录` / `都无`，并各给解法：
   - **A（推荐）改包名**：出 `com.sumsg.musichub.mod` 版，与原版共存、彻底绕开签名比对；
   - B 用**官方原版 APK** 先装一次（签名对得上）→ `pm uninstall <pkg>`（**不带 -k**）干净卸载 → 残留记录清除；
   - C `pm uninstall --user all <pkg>`（有时能连记录一起清）。

> 这条经验通用：**"卸载干净了却仍报签名不一致" → 先怀疑 KEEP_DATA 残留记录，用 `pm list packages -u` 看**。


## v1.0.12（2026-09-25 深夜）：ADB 断连自愈（自动重连 + 心跳保活）

现象：车机助手用着用着断连（如 `读取失败：Software caused connection abort`），每次都要手动重连。

**修法（4 层）**：
1. **统一 shell 入口 `sh()`**：所有 `adb.shell` 调用都走它 —— 捕获 `IOException` 后**自动快速重连并重试一次**，用户只会在日志里看到"连接被中断 → 自动重连并重试 …"；
2. **快速重连 `reconnect()`**：**优先直连上次成功的 IP**（热点 IP 基本不变，跳过 48 并发网段扫描），失败才回退到完整发现流程；密钥已持久化 → 重连不再弹授权框；
3. **心跳保活**：空闲时每 45 秒轻量 `echo k` ping 一次，发现断了就自动重连（车机休眠/热点抖动导致的断链常见）；`busy` 标志 + `adbLock` 串行化，保证心跳**绝不与用户操作同时读写同一条 socket**；
4. **TCP keepalive**（`socket.setKeepAlive(true)`）+ 卡片①新增「**重连车机（快速，跳过扫描）**」按钮；`ensureConnected()` 也改为"先尝试自动重连，再报未连接"。


## v1.0.13（2026-09-26 凌晨）：**"假重连"修复 —— `Socket.isConnected()` 会骗人**

实机日志揭示了 v1.0.12 自动重连失效的真因：

```
[+69s] 连接被中断（Software caused connection abort）→ 自动重连并重试 …
[+69s] 读取失败：Broken pipe        ← 重连"成功"了，却立刻又失败
```

**根因**：`Socket.isConnected()` **只表示"曾经 connect 成功过"**，对端断链后它**仍然返回 true**。
v1.0.12 的 `reconnect()` 第一句就是 `if (adb.isConnected()) return true;` → 直接"假重连"成功返回，
真正的 socket 早已死透 → 重试必然 `Broken pipe`；心跳也因此反复空转。

**修法（双保险）**：
1. `AdbClient` 增加 `dead` 标志：`readFully`/`send` 里任何 IOException、以及 `close()` 都置 `dead = true`，
   `isConnected()` 先看它 → **断链后立即为 false**；`connect()` 成功时复位；
2. `reconnect()` 不再信任任何状态判断：**无条件 `adb.close()` 掉旧连接** → 直连上次 IP → 连上后**用 `echo k` 实测验证**，
   只有命令真的跑通才置 `connected = true`；不通过就换下一个 IP / 走完整发现流程，并在日志里写"未通过实测"；
3. 心跳线程简化成直接 `sh("echo k")`，失败自然会走 `sh()` 内部的重连+重试链路。

> 通用教训：**判断"连接是否还活着"不能看 `isConnected()`，必须发一个真实的小请求（或依赖读写异常）**。


## v1.0.14 / v1.0.15（2026-09-26 凌晨）：杆机"授权给副驾后主驾桌面看不到应用"的真因 = launcher 缓存

实机现象：把已装在主驾(12)的网易爆米花 / 箭头音乐「授权给副驾(13)」后，**主驾桌面看不到**这两个应用；
改为"12+13 同时授权"后主驾仍看不到。

**诊断结论（车机实测数据）**：
```
· user 12：pm path 有 ✅  ceDataInode=3965256 installed=true hidden=false stopped=false ...
· user 13：pm path 有 ✅  ceDataInode=3973129 installed=true hidden=false stopped=false ...
· user 0/10/11：installed=false（本来就没装）
```
⇒ **包状态完全正常**，问题是**车机 launcher 的应用列表缓存**：`pm install-existing --user N` 之后桌面不重建列表。

**解决**：`am force-stop --user <uid> com.flyme.auto.launcher` + 重新拉起 → **用户实测"重启 launcher 好了"** ✅

**固化为工具能力**：
- v1.0.14 新增「诊断：该应用在各空间的安装状态」（逐空间 `pm path` + `dumpsys` 的 installed/enabled/hidden/stopped）与「刷新主驾桌面（重启 launcher）」；
- v1.0.15 让**授权 / 取消授权 / 彻底卸载后自动刷新对应空间桌面**（不用再手动点），并修掉诊断输出的排版问题（按行扫描 `User N:` 段 + 同空间去重）。

## v1.0.13 实机确认：真重连生效 ✅

```
[+25s] 连接被中断（Software caused connection abort）→ 自动重连并重试 …
[+25s] 已重连 172.22.13.66:5555（实测通过；轨迹：… 凭已存密钥签名通过）
```
⇒ `dead` 标志 + "无条件关旧连接 → 直连上次 IP → `echo k` 实测验证"这套修法在车上成立：
断连后**自己回来且不需要重新授权**。也再次印证：**判断连接存活不能看 `Socket.isConnected()`**。


## v1.1.0（2026-09-26）：UI 重构 —— 主页只留连接 + 功能入口，三个功能各自成页

需求：「检查车机是否已记住本机密钥」按钮没用了删掉；把**安装应用到车机 / 应用授权空间管理 / 运行日志**做成独立页面，
主页只保留连接车机功能和其他功能入口；安装页与管理页要在页面上简要显示执行情况。

**做法（单 Activity + 页面切换，保持状态零序列化）**：
- `MainActivity` 内部四个页面（`pages[0..3]`）：`buildHomePage()` / `buildInstallPage()` / `buildManagePage()` / `buildLogPage()`，
  各自是独立 ScrollView，`showPage(page)` 切换可见性；**返回键**（`onBackPressed`）与页内「‹ 返回」都回主页；
- **主页**：状态卡（连接状态/设备类型/活跃空间/可安装空间）+ 「一键连接车机」「重连车机（快速）」+ 三个功能入口，
  每个入口下方一行**摘要**（`上次安装：xxx → 空间 12 ✅ 19 秒` / `空间 5 个 · 第三方应用 6 个` / `最近：<最后一条日志>`）；
- **安装页**：顶部独立**执行状态区** + 目标空间单选 + 选择本地 APK / 一键装全屏工具 / 授权悬浮窗+悬浮球 + 「查看运行日志」；
- **授权管理页**：顶部独立**执行状态区** + 加载列表 / 空间多选 / 应用搜索（含预置与残留包）/ 授权·取消授权·彻底卸载·各空间诊断·刷新桌面 + 包名直输 + 查看日志；
- **日志页**：复制/清空 + 日志正文；
- `setStatus()` 同时刷新主页与两个功能页的状态区（在哪个页面都看得到最新进展）；`refresh()` 只更新主页状态，**不会覆盖功能页的执行状态**；
- 删除 `checkAuthKeys()` 及其按钮（含 `adb_keys` 比对逻辑）。

## 待办

- [x] 实机复验 v1.0.1：二次连接不再弹授权框 ✅、空间标签正确 ✅（装机失败 → 见 v1.0.2）
- [x] 实机复验 v1.0.2：定位到「本车机 pm install 文件路径会解析失败、必须走流式」✅
- [x] 实机复验 v1.0.3：流式安装成功 ✅（用户回执）
- [ ] 可选：照 LIGHTBOX 补 `cmd` 能力探测（先探测再选路，省一次失败尝试）
- [ ] 可选：装后校验 `pm path --user N <pkg>` + `appops set … SYSTEM_ALERT_WINDOW allow`
- [ ] 可选：shell 命令改用 `shell,v2,raw:`（二进制/退出码更干净）
- [ ] 实机验证万物全屏（悬浮球）切换 App 后是否保持；若掉则加自动重放守护
- [ ] 大包推送性能：`WRTE` 流控改为窗口化
- [ ] 后排空间命名按实机校准（不同固件的空间编号可能不同）
- [ ] 装机后校验：`pm path --user N <pkg>` 确认落点
- [ ] 多屏批量安装（一次装到多个空间）
- [ ] 图标与深色/横屏适配打磨

## 许可

MIT
