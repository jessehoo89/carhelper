# 车机助手 · CarHelper

> ⚠️ **本项目为独立第三方开源工具，与浙江吉利控股集团、领克汽车、极氪汽车、亿咖通科技（ECARX）、魅族科技均无任何关联，亦未获其授权或认可。**
> 仅供**自有车辆**的互操作与学习研究使用。使用者须自行确认并遵守当地法律法规与车辆保修条款。详见 [DISCLAIMER.md](DISCLAIMER.md)。

一个面向 **吉利 / 领克（亿咖通 ECARX 平台）Android 车机** 的开源小工具：

> **手机连车机热点 → 通过 ADB 把 APK 装进指定屏幕空间 → 管理各空间的应用授权 → 一键「万物全屏」**

手机端已内置车机端 APK，**手机只需装一个 App**：

| 组件 | 包名 | 说明 |
|---|---|---|
| 手机端 App | `com.carhelper.phone` | 发现车机、连接 ADB、安装 APK、空间授权管理、诊断排障 |
| 车机端 APK | `com.carhelper.fullscreen` | 「万物全屏」悬浮球（通过车机自带多屏管理服务调整显示区域） |

---

## 功能一览

### 手机端（主页 + 三个独立功能页）

**主页**：连接状态卡 + 「一键连接车机」+「重连车机（快速）」+ 三个功能入口（入口下带上次结果摘要）

**① 安装应用到车机（独立页）**
- 目标屏幕空间单选（按当前热点自动筛除不可用空间，如「12 · 主驾/中控屏」「13 · 副驾屏」）
- 选择本地 APK 安装 / 一键安装内置「全屏工具」
- **流式安装**（`exec:cmd package install -S <size>`，与 `adb install` 同一条路，不落临时文件）+ 大包进度
- **装前自检**车机 `/data` 可用空间；失败时汇总三次尝试的原因并给出人话建议
- 页内**独立执行状态区**：正在传输 xx MB / 安装成功（用时 N 秒，车机原文 `Success`）

**② 应用授权空间管理（独立页）**
- 「加载空间与应用列表」：**按每个用户空间**查询（`pm list packages -3 --user N`），列表显示 `com.xxx [空间 12]`
- 关键字搜索时会**连预置/系统包与"已卸载但保留记录"的包一起搜**（这两类是"装不上"的常见元凶）
- 空间多选 + 应用单选 → 「授权到选中空间」(`pm install-existing --user N`) / 「从选中空间取消授权」(`pm uninstall --user N`)
- **「彻底卸载（所有空间，清残留）」**：逐空间卸载 + 对所有用户卸载 + `pm clear` + 逐空间复核，并诊断「预置同名包 / KEEP_DATA 残留记录」
- **「诊断：该应用在各空间的安装状态」**：逐空间打印 `pm path` + `installed= / enabled= / hidden= / stopped=`
- **「刷新主驾桌面（重启 launcher）」**：装上但桌面不显示时的解药（授权/卸载后会**自动**刷新）
- 页内**独立执行状态区** + 主页摘要行

**③ 运行日志（独立页）**：一键「复制日志」（长日志直接发人排障）+「清空日志」；主页入口显示最近一行

### 连接与稳定性
- **一键连接**：检测 WiFi → 发现车机 → 连接 ADB → 识别设备类型与可用空间；任一步失败都给**具体原因**
- **ADB 密钥持久化**：授权一次**长期免弹授权框**（不会每次连接都拉授权界面）
- **断连自愈**：连接被中断时**自动快速重连并重试**（优先直连上次 IP，跳过扫描）+ 45 秒心跳保活 + TCP keepalive
- **首次启动风险告知**：车机风险 / 驾驶风险 / 保修风险 / 数据隐私，同意后方可使用

### 车机端（万物全屏）
- **悬浮球**（`TYPE_APPLICATION_OVERLAY`）：在任意 App 前台点它 → 全屏当前页面；再点 → 还原
- 常驻通知「全屏中 · 应用名」+「退出全屏」；拖前等稳定、搬后复核、不支持则回滚
- 装完后手机端会自动 `appops set … SYSTEM_ALERT_WINDOW allow` + `am start-foreground-service` 把悬浮球拉起来

---

## 兼容性

| 平台 | 状态 |
|---|---|
| **领克 900**（Flyme Auto，ECARX 平台） | ✅ **已大量实机验证**（装机 / 空间授权 / 万物全屏 / 诊断全流程） |
| 其他领克 / 吉利 / 极氪车机（高通 8295 + Flyme Auto 2.0+） | ⚠️ 可能适用，**待验证** |
| 其他平台 | ❌ 未验证（屏幕空间编号、系统服务名、launcher 包名可能不同） |

> 车机端「万物全屏」依赖系统服务 `geely_multi`；若不存在则全屏失效，**装机与授权管理功能不受影响**。

## 使用步骤

1. 手机装 `carhelper-phone.apk`（Android 或鸿蒙 + 兼容层均可）
2. 手机 WiFi 连上车机热点（领克车机 SSID 通常为 `Lynk&Co`）
3. 车辆挂 **P 挡**、车机屏幕保持唤醒
4. 打开 App → 同意风险提示 → 点「一键连接车机」
5. 车机屏若弹「允许调试」授权框，点**允许**（**务必勾选「始终允许」**，否则一断开就要重新授权）
6. 回主页进「安装应用到车机」→ 选目标屏幕 → 选择 APK 安装（或一键装全屏工具）
7. 需要给副驾/其他空间用 → 进「应用授权空间管理」→ 勾选空间 → 授权（**会自动刷新桌面**）

## 常见问题（FAQ）

| 现象 | 原因与解法 |
|---|---|
| 装不上，报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: … signatures do not match previously installed version` | 车机上还留着**同名应用**（官方版 / 预置版 / KEEP_DATA 残留记录）。→ 进「授权管理」→ 点「彻底卸载（所有空间，清残留）」→ 看诊断结论：预置同名包 或 KEEP_DATA 残留；再重装 |
| 应用列表里搜不到某个包 | 列表默认只列第三方包；**输入关键字**时会连预置/系统包与"已卸载但保留记录"的包一起搜（会标注 `[预置/系统或残留记录]`） |
| 授权给副驾后，主驾桌面看不到这些应用 | 车机 **launcher 列表缓存**：`pm install-existing` 只改包状态，桌面不会自己重建 → 点「刷新主驾桌面」（本工具在授权/卸载后会自动刷新）；实在不行重启车机 |
| 用着用着提示断连 | 车机热点链路空闲断链（车机休眠/省电）。本版**自动重连并重试**，日志会写「连接被中断 → 自动重连并重试 …」「已重连 …（实测通过）」 |
| 每次都弹授权框 | 需装 **v1.0.8+**：早期版本的 ADB 公钥格式/签名语义与真机不兼容（详见下文技术说明）；另外授权框里要勾「始终允许」 |
| `pm install` 从文件路径装崩在 `ApkLiteParseUtils` | 该系统从 `/data/local/tmp` 读文件安装会解析失败 → 本工具**优先走流式安装**（`-S`），文件方式仅作兜底 |
| 想彻底删干净某个包（连数据） | 「彻底卸载（所有空间，清残留）」会逐空间卸载并复核残留 |

---

## 技术说明

### 车机发现

只认带 IPv4 的 WiFi 网络；**第一候选是默认网关**（手机连车机热点时网关即车机），未命中再按本机 IP 前三段并发扫 `1..254` 的 `:5555`（48 并发 / 单次 450ms）。⚠️ 不同车机热点网段不同（实测见过 `172.21.204.x`、`172.22.13.x`），不要硬编码。

### ADB 客户端（`phone/src/…/AdbClient.java`，自实现、无三方库）

- 24 字节消息头（小端）：`cmd / arg0 / arg1 / data_length / crc32 / magic(=cmd ^ 0xFFFFFFFF)`
- 握手 `CNXN`；认证 `AUTH`：`TOKEN` → `SIGNATURE`，被拒才发 `RSAPUBLICKEY`（触发车机屏授权框）
- 流操作：`OPEN` / `WRTE` / `OKAY` / `CLSE`；`shell:` 执行命令、`sync:` 推文件、`exec:` 流式安装
- **密钥持久化**：RSA 密钥落盘（SharedPreferences）。密钥一变车机就会重新弹框 → **必须**持久化
- **公钥必须用 AOSP `android_pubkey` 的 524 字节小端格式**（`4+4+256+256+4`）：首字段是**模数的 32 位字数 `64`**，随后 `n0inv`、256 字节小端模数、256 字节小端 `rr = R² mod n`（R=2²⁰⁴⁸）、`e`。
  ⚠️ 写成「字节数 len + nlen + 大端模数 + 4 字节 rr」（276 字节）时车机**照样弹授权框**，但存下来的公钥永远验不过签名 → **每次连接都重新弹框**。别按记忆里的旧 mincrypt 结构写。
- **签名要双变体自适应**：现代 adbd 用 `RSA_verify(NID_sha1, token, …)`（对 token 再哈希）→ 需 `SHA1withRSA`；
  老式/mincrypt 语义则把 **token 当作"已算好的摘要"**（`00 01 FF…FF 00 ‖ DigestInfo(SHA1) ‖ token`，裸 RSA 加密）。
  两者依次尝试，**都不行才发公钥**（实测领克 900 认的是变体 2）。
- **`SEND_V1` 推送载荷是 `<路径>,<八进制权限>`**（如 `/data/local/tmp/a.apk,0644`），不是「长度前缀路径 + 二进制 mode」；权限串**必须带前导 `0`**（adbd 用 `strtoul(s, NULL, 0)`，`644` 会被当十进制）。格式错时 adbd 回 `FAIL missing , in ID_SEND_V1` 或直接断连。
- **不要靠"等应答"判定成败**：AOSP host 端推文件全程 `WriteOrDie` 不等回复，现代 adbd 对 `SEND` 请求根本不回（只在 `DONE` 后写一次 `OKAY`），服务流结束也**可能不发 `CLSE`**。本工具只"软等"传输层流控应答，**以车机上的文件字节数 / `pm install` 输出为准**。
- 分块上限取 adbd 通告的 `maxdata` 与 `SYNC_DATA_MAX`(64KB) 的较小值
- 所有流的 `OKAY`/`CLSE` 按 `arg1 == 本端 local id` 判归属（上一条流滞后的报文不能被下一条流误认，否则 `shell()` 会静默返回空）
- **连接存活不能看 `Socket.isConnected()`**：对端断链后它仍返回 `true`（"假在线"）→ 本实现用 `dead` 标志（读写异常即置位）+ 重连后跑一条 `echo k` **实测验证**
- 所有 shell 调用走统一的 `sh()`：**断线自动重连并重试一次**；心跳线程与用户操作用锁串行化，避免抢同一条 socket

### 安装方式阶梯

1. **A 流式安装**（首选，与 `adb install` 同路）：`exec:cmd package install -S <size> -r --user N`，APK 从 stdin 直喂，**不落临时文件**；失败再试 `exec:pm install -S`、`shell:cmd package install -S`
2. **B 推送再装**：`shell:cat > /data/local/tmp/xxx.apk` → 核对字节数 → `pm install`
3. **C sync 推送再装**：容错版 `sync` → 核对字节数 → `pm install`

> 实测（领克 900）：**只有 A 能成**。B/C 会在车机侧 `PackageManagerShellCommand.setParamsSize → ApkLiteParseUtils → ApkAssets.nativeLoadFd` 崩 —— 该系统从文件路径解析 APK 失败，与文件本身无关（A 已解析成功才会报签名冲突）。

### 屏幕空间编号

| 空间号 | 屏幕 | 说明 |
|---|---|---|
| 0 | 主驾（司机基础空间） | |
| 12 | 主驾 / 中控屏 | 实测活跃空间 |
| 13 | 副驾屏 | 车机上名为 `12_clone`（副驾常实现为主空间的克隆） |
| 100 / 101 | 后排娱乐屏 / 中控+副驾 | 后排热点下使用 |
| 10 / 11 | 访客 / 访客克隆 | |

⚠️ 查询包列表必须**逐空间**（`pm list packages -3 --user N`）：不带 `--user` 时只看 shell 自己的 user（默认 0），会得出"0 个第三方应用"的错误结论。

### 万物全屏（车机端）

通过系统服务 `geely_multi` 的隐藏 AIDL `android.view.IGeelyMultiManagerExt` 调整显示区域：
`1001` 中控 / `1002` 副驾 / `1003` 全屏；全屏 = `moveScreen2Screen(区, 1003, false)`（`transact(6)`，第三参固定 `0`），还原反之；顶层应用 = `getTopPkgName(区)`（`transact(4)`）。

**它搬的是「该区域当前显示的那个页面」，所以触发时机决定成败**：在本工具自己的 Activity 前台调用只会搬走自己 → 必须用**非 Activity 的悬浮球**（`TYPE_APPLICATION_OVERLAY`）在目标 App 前台时触发。完整流程：点球 → 读 `getTopPkgName(1001/1002)` 选目标区（排除自己/launcher/systemui）→ 轮询等页面稳定（≤2.6s / 120ms）→ 搬屏 → 800ms 后复核，不符则**回滚**并提示「该应用不支持全屏（系统限制）」→ 常驻通知可退出；服务被回收时自动还原。

### 联测（`tests/`）

```bash
./tests/run.sh      # 桌面联测：mock adbd 按 AOSP 语义复刻，验证客户端协议实现
```

`tests/mock_adbd.py` 复刻 adbd（CNXN / AUTH 验签 / `SEND_V1` 逗号解析 / shell 流 / 流控 / `cmd package install -S`），并支持两个"真机怪癖"开关：**`SILENT`**（对 sync 完全不回）、**`NOCLSE`**（服务结束不发 CLSE）、**`MOCK_LEGACY_ONLY=1`**（只认老式签名语义，模拟领克 900）。
`tests/TestMain.java` 断言覆盖：首次公钥授权、**524 字节小端公钥格式（长度/words/n0inv/完整 rr）**、shell 通道、200KB 同步推送逐字节一致、150KB shell 兜底一致、设备静默时仍完成、不发 CLSE 时立即返回、流式安装可用、`FAIL` 原文透传、跨流隔离、复用密钥不再弹框、**变体 1 被拒后自动用变体 2 通过且不再发公钥**。
`AdbClient.DEBUG = true` 可打印收发报文。

---

## 更新日志（摘要）

| 版本 | 要点 |
|---|---|
| v1.0.0 | 首版：一键连接 / 装机 / 空间授权 / 一键全屏 |
| v1.0.1 | ADB 密钥持久化（免重复授权）、空间标签显示屏幕归属、日志加时间戳 |
| v1.0.2 | 不再等车机应答（AOSP host 语义），安装改三方式阶梯，流式安装优先 |
| v1.0.3 | 修正 `SEND_V1` 载荷格式与流 id 归属；`pm install` 报错人话化 |
| v1.0.4~1.0.5 | 「万物全屏」改悬浮球触发（搬屏时机）+ 装后自动 `appops` 授权起服务 |
| v1.0.6 | **修正 ADB 公钥为 524 字节小端 `android_pubkey`**（重弹授权真因）+ 彻底卸载 |
| v1.0.7 | 应用列表按每个用户空间查询（原来查 user 0 得 0 个）+ 授权落盘判定 |
| v1.0.8 | **签名双变体自适应**（现代 `SHA1(token)` / 老式 mincrypt）→ 彻底免弹授权框 |
| v1.0.9~1.0.11 | 彻底卸载带诊断（预置同名包 / KEEP_DATA 残留）、列表可搜预置与残留包、包名直输 |
| v1.0.12~1.0.13 | 断连自愈（统一 `sh()` 重连重试 + 心跳 + keepalive）、**修复 `Socket.isConnected()` 假在线导致的重连失效** |
| v1.0.14~1.0.15 | 各空间安装状态诊断、刷新主驾桌面（launcher 缓存）、授权/卸载后自动刷新桌面 |
| **v1.1.0** | **UI 重构：主页 + 安装/授权管理/日志三个独立功能页（各带执行状态区）**；移除密钥检查按钮 |

---

## 设计原则

| 项 | 本工具 |
|---|---|
| 修改车机系统组件 | ❌ 不做 |
| 关闭安装校验器（如 `xsfinstallverifier`） | ❌ 不做（该车机上本就不存在） |
| 外联服务器 / 上报数据 | ❌ 零外联，只连车机 `:5555`（以及歌词补充可选直连公共接口，可关） |
| 手机端权限 | 仅 `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` |
| 车机端常驻 | 只有用户主动安装的全屏工具（悬浮球服务，可随时停止/退出） |

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

# 3) 联测（可选）
./tests/run.sh
```

> 注：`build/sdk`、`build/out`、`build/keystore`、`tests/*_keys.txt` 已在 `.gitignore` 中；首次构建会自动生成 `build/keystore/debug.keystore`（**升级必须用同一把**，请自行备份）。
> 若镜像里的文件名有变化，也可从任意 Android SDK 发行版取 `build-tools` 与 `platforms/android-34/android.jar` 放到对应目录。

环境要求：JDK 17+、`zip`、`curl`。

## ⚠️ 免责声明

- **驾驶风险**：请在车辆停稳、挂 P 挡时操作。行车中操作或观看第三方应用内容会分散注意力，可能导致交通事故。
- **车机风险**：第三方应用可能存在兼容性问题，影响车机系统稳定性。请自行评估并承担风险。
- **保修风险**：车辆厂家可能对"因第三方应用导致的故障"不予保修。本工具不越权、不破解、不修改系统组件，但装车行为本身仍可能引起保修争议。
- **适用范围**：已在**领克 900** 实机验证；其他领克 / 吉利 / 极氪车机可能适用但**待验证**；其他平台不保证可用。
- 本项目仅供**自有车辆**的互操作与学习研究使用，请在遵守当地法律法规与车辆保修条款的前提下使用。

## 许可

[MIT](LICENSE)

本项目**仅提供源代码**，不提供预编译安装包 —— 请按上方构建说明自行编译。
