# 断点续传 / 进度显示 / MD5 校验 — 代码审查发现

> 审查日期：2026-09-06
> 审查范围：`BREAKPOINT_RESUME_PLAN.md` 所规划的三项功能的实际实现
> 结论：三项功能主干均已落地，但存在 1 处会导致静默数据损坏的设计缺陷，
> 且三项功能在「传输文件夹」场景下基本失效。

## 实现完成度总览

| 功能 | 状态 | 主要缺口 |
|------|------|----------|
| 断点续传 | 主干完整 | 检查点语义错误（P0-1）；文件夹递归失效（P0-2）；无手动清除入口（P2-7） |
| 进度显示 | 完整 | 分母漏算文件夹内容（P0-2）；发送方语义为「已读入队列」而非「已发出」（P2-6） |
| MD5 校验 | 可用 | 仅 Android 端可发起（P2-1）；只报数量不报文件名（P2-2）；文件夹内容不校验（P0-2） |
| FileSanitizer（规划 P4） | **未实现** | Issue #34 / #35 未修复 |

### 设计上正确的决策（保留）

- 检查点持久化使用**字节偏移**而非块数（`CheckpointEntry.completedBytes`），与 `FileBlock.BLOCK_SIZE` 解耦；
  `JdkCheckpointManager.parseEntry` 保留旧 `completedBlocks` 字段的兼容读取。
- MD5 改为**传输完成后重读磁盘计算**，而非规划中的「流式累加」。多一遍 I/O，
  但能查出落盘阶段的错误（含 partial write、空洞），比流式方案更可靠。
- `isCheckpointValid` 下沉到平台层做磁盘校验（文件存在 + 长度 >= 检查点字节），
  避免检查点指向已被删除/截断的文件。

---

## P0 级：必须修复

### P0-1 检查点记录的不是连续水位线（静默数据损坏）

**位置**：`core/WriteFileCall.java:122`（PC / Android 各一份）

每写完一块即保存 `block.index * BLOCK_SIZE + written` 作为「已完成字节数」。
该值是**最后落盘那一块的末尾偏移**，而非连续已完成前缀。

多通道并行是本项目核心机制，块必然乱序到达：
`SendFileCall` 各线程从同一 `deque` 抢块，`WriteFileCall.tryTakeBlockInternal()`
只能在**已到达**的队列头中取最小值。USB 通道快于 WiFi 时，block 5 先落盘、
block 3 仍在传输中属于正常情况——`WriteFileCall.java:97` 的 seek 分支本身即为此存在。

**故障链**：

```
block 5 落盘 → 检查点写 6MB → 此刻断线（block 3、4 从未到达）
  → 磁盘 3~5MB 为空洞
  → 下次握手 isCheckpointValid 检查 file.length() >= 6MB → 文件长度确为 6MB → 通过
  → 发送方从 6MB 续传
  → 空洞永久留在文件中
```

且检查点值非单调：block 5 之后到达的 block 3 会将其改回 4MB，
最终存什么取决于哪一块最后落盘。

**影响**：疑为 Issue #109（视频丢帧、时间错乱）的根因之一。
开启 MD5 校验可发现，不开则为静默损坏。

**修复方向**：按文件维护已写块集合，仅在连续前缀推进时 `saveCheckpoint`
（水位线 = 最小未到达块的起始位置），并保证检查点只增不减。

### P0-2 文件夹内的文件不参与握手

**位置**：`core/HFXService.java:57`（握手清单）vs `core/ReadFileCall.java:70`（递归展开）

握手只发送**用户选中的顶层项**，而 `ReadFileCall.listFilesAndRead` 会递归展开子文件。
握手/统计走「选中列表」，实际传输走「递归展开后的列表」，二者不一致。

后果连锁：

- **续传失效**：递归发现的文件路径不在 `checkpoints` map 中（`ReadFileCall.java:97` 查不到），
  永远从 0 重传。接收方仍为每个子文件保存检查点（`WriteFileCall.java:122`），
  但握手时无法匹配，只能等 7 天过期清理——数据库持续堆积垃圾记录。
- **进度分母错误**：`HFXService.java:83` 仅累加顶层非目录项的 size，目录项 size 为 0。
  选中一个文件夹传输，`totalBytes` 即为 0 或极小值，进度条直接顶至 100%
  （`TransferDialog.java:83`、`Main.java:109` 靠 clamp 兜住，但显示已无意义）。
- **校验漏项**：`verifyFiles` 遍历 `transferToSource.keySet()`（`HFXService.java:318`），
  同样只含顶层项，文件夹内容完全不校验。

**修复方向**：传输前先递归展开一次，用完整清单做握手、算总量、做校验。
注意此改动涉及握手协议内容变化，需同步提升 `HFXService.VERSION_CODE`。

### P0-3 partial write 未处理

**位置**：`core/WriteFileCall.java:117`

只调用一次 `channel.write(data)`，随后以 `data.position()` 取实际写入量。
字节数统计准确，但**未写完的部分被直接丢弃**——`data` 随即被回收进缓冲池（`:125`），
剩余数据永久丢失，文件留洞。

`BREAKPOINT_RESUME_PLAN.md:403` 明确要求校验写入量并抛异常，实现中未做。

**修复方向**：改为 `while (data.hasRemaining()) channel.write(data);`

---

## P1 级：应当修复

### P1-1 JdkCheckpointManager 每块全量重写整个 JSON

**位置**：`jdkcore/JdkCheckpointManager.java:52` → `writeToDisk()`

传输 18GB 意味着 18000+ 次全文件重写；条目多时复杂度为 O(n²)。

### P1-2 检查点文件替换非原子

**位置**：`jdkcore/JdkCheckpointManager.java:126`

先 `delete` 再 `rename`，两步之间崩溃会导致 `checkpoints.json` 整体消失，
丢失全部检查点。应使用 `Files.move(..., ATOMIC_MOVE)` 直接覆盖。

### P1-3 进度重复累加

**位置**：`core/WriteFileCall.java:89`

打开文件时将 `skipBytes` 计入 `completedBytes`。乱序场景下同一文件会被反复「重开」
（`:68` 的 `!lastBlock.path.equals(block.path)` 判断在块交错时多次成立），
每次重复累加 skipBytes，续传时进度虚高。

### P1-4 文件切换时无条件清除检查点

**位置**：`core/WriteFileCall.java:72`

注释称「排序取出保证其所有块均到达」——如 P0-1 所述该前提不成立。
文件 B 的块先到会导致文件 A 的检查点被提前清除，之后中断，A 退化为全量重传。

### P1-5 空文件检查点无法清除

**位置**：`core/WriteFileCall.java:154` `isFileComplete()`

`totalSize == 0` 时 `totalBlocks` 算得 0，要求 `index == -1`，永远为 false。

---

## P2 级：可以修复

| 编号 | 问题 | 位置 |
|------|------|------|
| P2-1 | 校验只能 Android 端发起；PC 端无规划中的 `--checksum` 参数，`Main.java:158` 仅打印提示让用户去手机端操作。PC 作为接收方时不便 | `Main.java:158` |
| P2-2 | 校验结果只有 `mismatchCount`，不返回具体文件名，用户无法针对性重传 | `HFXService.java:341` |
| P2-3 | `handleFileChecksumRequest` 在 `HFXClient.start` 主循环中同步执行，大文件 MD5 期间控制通道完全阻塞，无进度、不可取消 | `HFXClient.java:177` |
| P2-4 | `receiverSide` / `transferToSource` 仅保留最近一次传输状态，连传两批只能校验后一批 | `HFXService.java:27-31` |
| P2-5 | 死代码：`FileBlock.calcBlockCount()` 无调用者，且算法与 `isFileComplete` 不一致（前者 `size/BLOCK+1`，后者向上取整），留存是隐患；`WriteFileCall` 中大段 `RandomAccessFile` 注释残留；`ReadFileCall.java:110` 的 `length == 0` 分支位于 `alignedSkip` 判断之后，此时 `alignedSkip` 必为 0，逻辑冗余 | 多处 |
| P2-6 | 发送方 `completedBytes` 语义为「已读入队列」而非「已发出」。缓冲池有界故滞后有界，但与用户理解的「已传输」有偏差 | `ReadFileCall.java:133` |
| P2-7 | `clearAllCheckpoints` 无 UI 入口（规划 P9 要求「手动清除 checkpoint」） | Android UI |
| P2-8 | PC 与 Android 的 `core/` 为逐字节镜像（各约 2600 行），每处修复需改两遍。规划文档自列 P1 架构项 | `core/` × 2 |

---

## 已确认无问题

- **Android 进度回调线程安全**：`BackstageTask.EvProxyHandler` 通过动态代理将所有回调
  统一 post 到 UI 线程，`TransferDialog.showProgress` 直接操作 `ProgressBar` 是安全的。

---

## 验证边界

以上结论来自代码静态分析。**未实际运行双通道传输复现 P0-1**。
该路径的推理链（`SendFileCall` 抢块 → 跨通道速度差 → `tryTakeBlockInternal` 取已到达最小值
→ 检查点写最后落盘块末尾）在代码中是闭合的，且 `WriteFileCall.java:97` 的 seek 分支
本身即证明作者预期乱序会发生。项目零测试，此类并发缺陷靠人工验证难以覆盖。

---

## 修复进度

| 编号 | 摘要 | 状态 |
|------|------|------|
| P0-1 | 检查点连续水位线 | ✅ 已修（见下方第二轮 §B 验证） |
| P0-3 | partial write 循环写入 | ✅ 已修 |
| P1-3 | 进度重复累加 | ✅ 已修 |
| P1-4 | 文件切换清检查点 | ✅ 已修 |
| P1-5 | 空文件检查点 | ✅ 已修 |
| P1-1 | 检查点写入节流 | ✅ 已修（`WriteFileCall` 每文件 1 秒节流 + 结束补写） |
| P1-2 | 原子替换 | ✅ 已修 |
| P0-2 | 文件夹递归展开握手 | ✅ 已修（发送侧展开后同步给握手/总量/校验） |
| P2-5 | 死代码清理 | ✅ 已修（`calcBlockCount`、`readFully` 死代码等） |
| P2-1 | 校验只能 Android 端发起 | ✅ 已修（PC 新增 `-x/--checksum`，结果回传，见 §B6） |
| P2-2 | 校验不返回文件名 | ✅ 已修（FILE_CHECKSUM_RESULT 回传失败文件名清单，PC 与 Android 都列出具体文件） |
| P2-3 | 校验阻塞控制循环 | ✅ 已修（加 `ReadWatchdog` 读超时；校验期间 UI 锁按钮防并发串流；仍占用控制通道但不再永久阻塞） |
| P2-6 | 发送方进度语义 | ⬜ 保持现状（缓冲池有界，滞后有界） |
| P2-7 | 无手动清除检查点入口 | ✅ 已修（主界面菜单「清除断点续传记录」，确认后清空全部对端的记录） |
| P4 | FileSanitizer（Issue #34/#35） | ✅ 已修（非法字符/控制字符、尾部点空格、Windows 保留设备名、超长段截断） |
| B5 | 传输路径唯一性 | ✅ 已修（`FileSanitizer.uniquePath` 按出现顺序加序号） |
| B7 | 校验无读超时 | ✅ 已修（`ReadWatchdog`：SO_TIMEOUT 对 NIO 阻塞读实测无效，改为超时关通道） |

---

# 第二轮复查（同日，修复后）

> 复查方式：静态走查 + 实际编译与运行（不再只是读代码）

## A. 本轮先做的验证

| 项 | 命令 | 结果 |
|---|---|---|
| PC 端全量编译 | `javac -encoding UTF-8 -cp libs/annotations-24.0.1.jar -d .verify $(find src -name '*.java')` | ✅ 退出码 0 |
| Android `core/` 单独编译 | 同类 javac（不依赖 Android SDK） | ✅ 退出码 0 —— 第一轮遗留的「Android 侧从未编译」风险排除 |
| `core/` 双端镜像 | `diff -r` | ✅ 完全一致 |
| 水位线自检 | `HybridFileXfer-PC/test/WatermarkProbe.java` | ✅ 4/4 通过（新增场景 4） |
| AIDL | `getFileSize = 14` 显式事务号 | ✅ 未破坏既有编号 |

## B. 本轮新发现（第一轮审查未覆盖）

### B1 接收端不做截断 → 尾部残留脏数据（已修，已实证）

`af8c1d4` 删掉 `setLength(length)` 是对的（会破坏续传数据），但同时丢掉了截断语义：
接收端以 `rw` / `MODE_WRITE_ONLY`（无 `O_TRUNC`）打开**已存在的更长文件**时只覆写前面一段，
尾部旧内容原样保留。无检查点时不会走 `isCheckpointValid`，这条路径没有任何防护；
MD5 关（默认）时完全静默。

修复：`WriteFileCall` 打开文件后 `channel.truncate(totalSize)`（**只缩不扩**：续传时是 no-op，
也不会像 `setLength` 那样撑出稀疏空洞）。
实证：临时改成 `if (false) channel.truncate(...)` 后自检场景 4 立即 FAIL（落盘 4MB，预期 2MB）。

### B2 peerId 用 IP → 换连接方式后续传静默失效（已修）

服务端 `peerId = 控制通道对端 IP`：走 ADB 是 `127.0.0.1`、走 WLAN 是局域网 IP，
**同一台电脑变成两个对端**，检查点互不匹配 → 全量重传；客户端侧用服务器地址作键，DHCP 变更同样失效。
而本项目主打场景恰是 USB+WLAN 双通道，用户「拔线重连后续传」正好命中。

修复：握手时互换**稳定设备标识**（PC 存 `~/.hybridfilexfer/device.id` 文件，
Android 存 SharedPreferences `device_id`），IP 仅作为握手失败时的兜底。

### B3 写线程 IOException 路径不关文件、不补写检查点（已修）

`WriteFileCall.call()` 的 `catch (IOException)` 直接 `cancel(); throw;`：
Android 侧泄漏跨进程 `ParcelFileDescriptor`，且最后一段被节流的水位线丢失。
修复：改为 `finally` 统一 `closeCurrentFile()` + `flushCheckpoints()`（关文件失败不掩盖原异常）。

### B4 读循环可无限自旋（已修）

`ReadFileCall` 的 `while (buffer.hasRemaining()) channel.read(buffer);`——
`read()` 返回 -1（源文件被截断/替换）时 position 不前进 → 死循环烧 CPU，无报错无超时。
修复：`read < 0` 时关文件并抛 `IOException`。

### B5 传输路径唯一性假设（未修，归入 P4）

`Directory.generateTransferPath` 会把 `[\\:*?"<>|]` 替换成 `_`，
于是不同源文件可能映射到同一传输路径；而新架构把传输路径同时当作 `fileStates` 键、
检查点主键、`transferToSource` 键与握手清单键 → 碰撞会让两个文件共享一个 `FileState`，输出损坏。
概率低但属静默毁数据，应由 P4 FileSanitizer 一并处理（去重/加后缀）。

### B6 校验只能从手机服务端发起（已修）

`verifyFiles` 原仅由 Android 服务端 UI 触发，PC 只能打印提示。
服务端**没有控制通道读循环**（请求全由服务端发起、客户端应答），因此客户端无法自行发起。

修复方案（不引入服务端调度循环）：
1. 客户端握手末尾带上 `requestChecksumOnTransfer`（PC 的新参数 `-x/--checksum`）；
2. 服务端读完握手记下 `peerRequestsChecksum`，传输成功后由服务端发起校验（对端此时已回到其控制循环）；
3. 新增 `FILE_CHECKSUM_RESULT=17`，发起方把结果回传给请求方，因此 **PC 也能直接看到校验结论**。
4. `verifyFiles` 增加 `AtomicBoolean` 并发防护（自动校验与用户点击不会同时读写控制通道）。

遗留（未修）：`verifyFiles` 仍会阻塞控制循环（P2-3），且无读超时——
对端控制循环已退出时可能长时间等待（B7）。
为防“校验期间再发起一笔传输”（两条线程同时读写控制通道）已在 UI 侧处理：
`onComplete` 发现对端请求了校验时把底部按钮改为 “校验中…” 并**禁用**（`TransferDialog.lockButton`），
结果回来后由 `setButton` 重新启用。
副作用：若校验真的卡死（B7），传输对话框会一直锁着（不可取消），极端情况下需要退出 App。

### B8 发送端进度漏算整文件跳过量（已修）

`alignedSkip >= length` 分支在累加进度前返回，而接收端 `totalBytes` 含该文件 → 进度到不了 100%。

### B9 零散问题（已修）

- `ReceiveFileCall` 未校验对端传来的 `length`（越界会抛 `IllegalArgumentException` 绕过 IOException 处理）→ 已加校验；同时删除无用的 `readFully(buffer)`。
- `HFXServer.computeFileMd5` 对同一 fd 双重 close（`FileInputStream` 与 `ParcelFileDescriptor` 各关一次）→ 改为只由 PFD 关闭。
- `JdkWriteFileCall` / `IOServiceImpl` 注释仍称「依据 channel.size() 判断是否从头写」、`JdkCheckpointManager` 类注释仍写 `completedBlocks` → 已订正。
- `WatermarkProbe.java` 留在 PC 根目录且第 66 行被改坏（无法编译）→ 修正并移入 `HybridFileXfer-PC/test/`，新增场景 4，成为水位线逻辑唯一的回归保护。

### 仓库卫生（已修）

- 新增根 `.gitignore`；`HybridFileXfer-PC/out/`（含已入库的 jar 与 .class）取消跟踪；
- 7 个已入库的编辑器备份 `*~`（`MainActivity.java~`、6 个 `strings.xml~` 等，内容均为更早版本）已删除。

## C. 协议变更记录

`HFXService.VERSION_CODE` **302 → 303**，与 302 及更早版本不兼容：

| 变更 | 位置 |
|------|------|
| 握手互换稳定设备标识（客户端与服务端各写一个 UTF，再读对端） | `HFXClient.connect` / `HFXServer.startServer` |
| 客户端握手上报「请求传输后校验」布尔值 | `HFXClient.connect` 末尾 / `HFXServer` 读 `remoteHomeDir` 之后 |
| 新增 `FILE_CHECKSUM_RESULT = 17`（int 失败数 + 失败文件名清单） | `ControllerIdentifiers` |

---

# 第三轮：清理剩余开放项（同日）

| 编号 | 修复内容 |
|------|----------|
| P2-2 | 校验结果从「只报数量」改为**回传失败文件名清单**（脚本与 UI 都列出具体文件，最多 20 个）。`TransferFileCallback.onFileChecksumComplete(boolean, List<String>)` 签名相应调整 |
| P2-3 / B7 | 新增 `core/ReadWatchdog`：校验交换期间若 `CHECKSUM_STALL_TIMEOUT_MS`（10 分钟）无任何进展就关闭控制通道。**为何不用 setSoTimeout**：实测对 NIO 阻塞 `SocketChannel` 设置 SO_TIMEOUT 后，`read()` 仍旧永不返回（探针跑到 20s 被强杀）；而 `close()` 能在 38ms 内让阻塞读抛 `AsynchronousCloseException`。看门狗按「每读到一条响应就 touch」计时，所以对端算大文件 MD5 不会被误杀 |
| P2-7 | 主界面溢出菜单新增「清除断点续传记录」（`ConfigDB.clearAllCheckpoints()` 无参重载），带确认弹窗与删除条数提示 |
| P4 | 新增 `core/FileSanitizer`：非法字符与控制字符、Windows 尾部点/空格（Windows 会静默丢弃，造成传输路径与磁盘文件名不一致 → 续传失效）、Windows 保留设备名（CON/NUL/COM0-9/LPT0-9，含带扩展名形式）、超长段按字符截断。`Directory.generateTransferPath` 改为逐段调用它 |
| B5 | 新增 `FileSanitizer.uniquePath`：清洗撞车（`a:b.txt` 与 `a_b.txt` 都变 `a_b.txt`）时按出现顺序加序号。`HFXService.sendFiles` 在握手前统一生成映射（源路径 → 传输路径），握手清单、总量、`transferToSource` 与文件块全部复用同一份映射，不再各自调用 `generateTransferPath` |

## 第三轮的验证

```
自检 6/6 通过（HybridFileXfer-PC/test/WatermarkProbe.java）
  场景5 文件名清洗 11 项断言 + 去重 3 项断言（Windows / Linux 分开断言）
  场景6 看门狗：卡死时读线程 500ms 内解除阻塞且通道已关闭；持续 touch 时不误杀
反证：把其中一项预期改错后自检退出码 1 并打印 FAIL → 断言确实有效
PC 全量 javac 退出码 0；Android core 单独 javac 退出码 0（仅类型检查：不装 SDK、不跑 Gradle、不产 APK）；core/ 双端 diff 一致
Android 完整构建（含 droidcore）由 push 触发的 GitHub Actions `build.yml` 验证，本次已通过（2m48s）
```

## 仍未修（有意保留）

| 编号 | 说明 |
|------|------|
| P2-6 | 发送方 `completedBytes` 语义为「已读入队列」（缓冲池有界，滞后有界），未改成「已发出」 |
| — | 校验仍无逐文件进度（大文件时对话框只有「正在校验文件…」） |
| — | 服务端仍无控制通道读循环（P12 双向调度），客户端发起的一切能力都得挂在现有握手边上 |

## 仓库卫生与自检（第三轮）

- 新增根 `.gitignore`；`HybridFileXfer-PC/out/`（含已入库 jar 与 .class）取消跟踪，磁盘保留。
- 新增 `HybridFileXfer-PC/test/WatermarkProbe.java`（已修好被改坏的那行），成为水位线 / 截断 /
  文件名清洗 / 校验超时四类逻辑的唯一回归保护；运行方式写在文件头注释里。

