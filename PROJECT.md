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

## 待办

- [x] 实机复验 v1.0.1：二次连接不再弹授权框 ✅、空间标签正确 ✅（装机失败 → 见 v1.0.2）
- [x] 实机复验 v1.0.2：定位到「本车机 pm install 文件路径会解析失败、必须走流式」✅
- [x] 实机复验 v1.0.3：流式安装成功 ✅（用户回执）
- [ ] 可选：照 LIGHTBOX 补 `cmd` 能力探测（先探测再选路，省一次失败尝试）
- [ ] 可选：装后校验 `pm path --user N <pkg>` + `appops set … SYSTEM_ALERT_WINDOW allow`
- [ ] 可选：shell 命令改用 `shell,v2,raw:`（二进制/退出码更干净）
- [ ] 大包推送性能：`WRTE` 流控改为窗口化
- [ ] 后排空间命名按实机校准（不同固件的空间编号可能不同）
- [ ] 装机后校验：`pm path --user N <pkg>` 确认落点
- [ ] 多屏批量安装（一次装到多个空间）
- [ ] 图标与深色/横屏适配打磨

## 许可

MIT
