# AGENTS.md — HybridFileXfer

## 仓库结构说明

### 嵌套 Git 仓库结构

```
D:\Vibe-Coding\          ← 父目录 Git 仓库（本地，无 remote）
├── .gitignore           ← 已忽略所有子项目和工具链配置
├── HybridFileXfer\      ← 本项目的独立 Git 仓库
│   └── .git             ← 指向 https://github.com/NoNameLeGo/HybridFileXfer.git
└── ...
```

**关键约束：**
1. 父目录 `D:\Vibe-Coding` 是个人 AI Agent 工作区根目录，**不应推送到任何 remote**
2. 本项目的 remote 是 `origin: https://github.com/NoNameLeGo/HybridFileXfer.git`
3. 子项目目录在父仓库中被 `.gitignore` 忽略，避免 git status 混乱

**对 AI Agent 的影响：**
- 当 Agent 在本项目目录工作时，应只操作本项目文件
- 读到父目录的 git status 时，应理解这是"工作区根目录"而非项目本身
- 不要尝试在父目录执行 `git push` 或修改 remote

**相关文件：**
- 父目录 `.gitignore`: `D:\Vibe-Coding\.gitignore`
- 本项目 `.gitignore`: `D:\Vibe-Coding\HybridFileXfer\.gitignore`

---

## Quick start

### Android 端（手机）

1. 选择 IO 模式（正常 / ROOT / ADB / Shizuku）
2. 勾选要使用的网卡（USB_ADB、WLAN、热点等）
3. 点击「启动服务器并等待连接」
4. 电脑端连接后即可传输

### PC 端（电脑）

```bash
# 安装 Java 运行环境（需要 JDK 17+）
# 下载 https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html

# ADB 连接（USB）
java -jar HybridFileXfer.jar -c adb

# ADB 连接（指定设备）
java -jar HybridFileXfer.jar -c adb -s abcd1234

# 局域网直连
java -jar HybridFileXfer.jar -c 192.168.1.114 -d D:\Transfer\Files

# 传完做一次 MD5 校验（默认关闭）
java -jar HybridFileXfer.jar -c 192.168.1.114 -x

# 查看帮助
java -jar HybridFileXfer.jar -h
```

手机端 APK 需与电脑端同版本（协议 304，旧版会直接报版本不一致）。

---

## 项目架构说明

### 目录结构

```
HybridFileXfer/
├── HybridFileXfer-Android/     ← Android 端（App）
│   ├── app/
│   │   └── src/main/java/top/weixiansen574/hybridfilexfer/
│   │       ├── droidcore/     ← Android 平台特化实现
│   │       │   ├── HFXServer.java          ← 服务端（手机作为服务器）
│   │       │   ├── DroidHFXClient.java     ← 客户端
│   │       │   ├── DroidReadFileCall.java ← Android 文件读取
│   │       │   └── DroidWriteFileCall.java← Android 文件写入
│   │       ├── core/          ← 传输核心（跨平台抽象，两侧逐字节镜像）
│   │       │   ├── HFXService.java         ← 传输编排（sendFiles / receiveFiles）
│   │       │   ├── HFXClient.java          ← 客户端握手 + 控制循环
│   │       │   ├── ReadFileCall.java       ← 文件读取 + 分块（断点处跳过）
│   │       │   ├── WriteFileCall.java      ← 文件写入（排序 + 续写 + 水位线 + 每块存档）
│   │       │   ├── SendFileCall.java       ← 发送线程
│   │       │   ├── ReceiveFileCall.java    ← 接收线程
│   │       │   ├── CheckpointManager.java  ← 检查点读写接口（平台层实现）
│   │       │   ├── CheckpointEntry.java    ← 检查点记录（completedBytes）
│   │       │   ├── FileSanitizer.java     ← 文件名清洗 + 传输路径去重
│   │       │   ├── ReadWatchdog.java       ← 控制通道读超时（SO_TIMEOUT 对 NIO 无效）
│   │       │   ├── ProgressSource.java     ← 进度源
│   │       │   ├── SpeedMonitorThread.java ← 速度/进度上报
│   │       │   ├── FileBlock.java          ← 1MB 分块数据结构
│   │       │   ├── TransferConnection.java ← 单条传输通道
│   │       │   ├── TransferIdentifiers.java← 块级协议常量
│   │       │   ├── ControllerIdentifiers.java← 控制器协议常量
│   │       │   ├── Utils.java              ← md5Hex 等
│   │       │   ├── bean/                   ← Directory / RemoteFile / ServerNetInterface / TrafficInfo
│   │       │   └── callback/               ← ClientCallBack / ConnectServerCallback / TransferFileCallback
│   │       ├── tasks/         ← Android 后台任务封装
│   │       ├── listadapter/   ← 文件列表 Adapter
│   │       ├── ConfigDB.java  ← SQLite（书签、检查点存储）
│   │       └── IOServiceImpl.java ← AIDL Service 实现
│   └── ...
├── HybridFileXfer-PC/         ← PC 端（Java 应用）
│   ├── src/top/weixiansen574/hybridfilexfer/
│   │   ├── jdkcore/           ← JDK 平台特化实现
│   │   │   ├── JdkHFXClient.java
│   │   │   ├── JdkReadFileCall.java
│   │   │   ├── JdkWriteFileCall.java
│   │   │   └── JdkCheckpointManager.java  ← 检查点存 JSON Lines（7 天清理）
│   │   ├── core/              ← 传输核心（与 Android 共享抽象）
│   │   └── Main.java          ← 入口 + 命令行解析
│   ├── test/                  ← 无框架自检（WatermarkProbe / BufferOomProbe）
│   ├── libs/                  ← vendor 的 annotations jar
│   ├── out/                   ← 编译输出（已 gitignore）
│   └── adb.exe                ← ADB 工具（release 打包要用）
├── HybridFileXferLauncher/    ← .NET 启动器（CI 打成 start.exe）
├── script/                    ← 辅助脚本（start-by-adb.bat / start-by-network.bat / USB-forward*.bat）
└── README.md                  ← 完整文档
```

### 核心传输流程

```
发送方                              接收方
  │                                   │
  ├─ ReadFileCall ─→ 1MB分块 ─→ 队列  │
  ├─ SendFileCall × N ─→ 网络 ──→ ReceiveFileCall × N │
  │                                   ├─ 排序队列
  │                                   └─ WriteFileCall ─→ 磁盘
```

- **分块**：文件按 1MB (`FileBlock.BLOCK_SIZE`) 切分，含 `(fileIndex, index)` 索引
- **多通道**：每个网卡一条传输线，独立队列并发收发
- **排序写入**：`WriteFileCall` 从所有队列中取 `(fileIndex, index)` 最小的块，保证顺序写入
- **控制器通道**：`ctChannel` 负责协商（版本、网卡列表、缓冲区），不传文件数据

### 关键常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `FileBlock.BLOCK_SIZE` | 1024×1024 (1MB) | 分块大小 |
| `HFXService.VERSION_CODE` | 304 | 协议版本（303→304：校验应答期间发空路径心跳；302→303：握手互换稳定设备标识 + 校验请求/结果回传） |
| `HFXService.CLIENT_HEADER` | "HFXC" | 控制通道握手标识 |
| `ControllerIdentifiers.REQUEST_RECEIVE` | 10 | 请求接收文件 |
| `ControllerIdentifiers.REQUEST_SEND` | 11 | 请求发送文件 |
| `ControllerIdentifiers.CHECKPOINT_REQUEST` | 14 | 断点续传：交换文件列表与检查点 |
| `ControllerIdentifiers.FILE_CHECKSUM_REQUEST` | 16 | 文件校验：请求对端计算 MD5 |
| `ControllerIdentifiers.FILE_CHECKSUM_RESULT` | 17 | 文件校验：发起方回传校验结果 |
| `TransferIdentifiers.FILE` | 0 | 文件数据块 |
| `TransferIdentifiers.FOLDER` | 1 | 文件夹标记 |
| `TransferIdentifiers.FILE_SLICE` | 2 | 文件切片（预留） |
| `TransferIdentifiers.EOF` | 3 | 传输结束 |
| `TransferIdentifiers.END_OF_INTERRUPTED` | 4 | 中断结束 |
| `TransferIdentifiers.END_OF_READ_ERROR` | 5 | 读失败结束 |
| `TransferIdentifiers.END_OF_WRITE_ERROR` | 6 | 写失败结束 |

---

## 构建与打包

### Android 端

**不要在本地构建 Android 产物（APK）。** Android 构建统一走 GitHub Actions：

- `build.yml`（push 自动触发）：构建 debug/release APK，仅作校验
- `release.yml`（push tag `v*` 或手动触发）：签名并发布到 GitHub Release

原因：本地没有完整的 SDK/NDK/CMake 环境与签名密钥，产物不可用于交付；CI 环境统一且配置了签名 secrets。需要 APK 时到 Actions 运行结果或 Release 页面下载。

补充：**也不要在本地安装 Android 构建环境（SDK/NDK/Gradle）**，磁盘空间有限。
本地唯一允许的 Android 相关校验是**不依赖 SDK 的 `core/` javac 类型检查**（见下方「后续改动必须遵守的约束」第 1 条），
它只编译 `core/**` + `nio/**` 到临时目录，不产生任何 Android 产物。

### PC 端

PC 端为 IntelliJ IDEA 项目（`.iml`），源码在 `src/`，编译输出在 `out/`。

外部依赖仅一个：jetbrains annotations，已 vendor 在 `libs/annotations-24.0.1.jar`（约 30KB，Apache-2.0，仅编译期使用，不进入运行时）。本地与 CI 均无需联网下载；IntelliJ 内编译仍使用 IDE 自己配置的同一依赖。

**CI 覆盖**：`build.yml` 的 `pc` 任务在**每次 push / PR** 上都会跑
「`core/` 与 `nio/` 双端逐字节一致 + javac 全量 + jar 冒烟 + 两个自检」，
所以 PC 端不再依赖“记得本地跑”。本地跑同样的命令只是为了**反馈更快**（约 15s vs 一两分钟）。

**发布注意**：`-v` 里的版本号（`src/messages_*.properties` 的 `version=`，5 个语言文件）
由 `release.yml` 从 tag 自动 sed（含日期，并用 `grep` 校验真的改上了）；Android 的 `versionName` 同样自动同步。
源码里写着的版本号只是本地构建的默认值，不必每次发版手工改。

```bash
# 手动编译（与 CI 命令一致，只是输出目录不同：本地 out/、build.yml 用 out/ci、release.yml 用 out/pc）
find src -name '*.java' > sources.txt
javac -encoding UTF-8 -cp libs/annotations-24.0.1.jar -d out @sources.txt
cp src/messages_*.properties out/

# 打 jar（必须引用 src/META-INF/MANIFEST.MF，缺 Main-Class 会让 java -jar 启动即退）
jar cvfm HybridFileXfer.jar src/META-INF/MANIFEST.MF -C out .

# 回归自检（无框架，通过时退出码 0；Windows 用 ; 分隔 classpath）
javac -encoding UTF-8 -cp libs/annotations-24.0.1.jar -d .verify $(find src -name '*.java')
javac -encoding UTF-8 -cp "libs/annotations-24.0.1.jar;.verify" -d .verify test/WatermarkProbe.java test/BufferOomProbe.java
java -cp "libs/annotations-24.0.1.jar;.verify" WatermarkProbe
# 必须限死堆外内存，否则「分配失败」这条路径不会触发
java -XX:MaxDirectMemorySize=16m -cp "libs/annotations-24.0.1.jar;.verify" BufferOomProbe
```

---

## 贡献规范

- 项目遵循 **GPL-3.0** 许可证（见 `LICENSE.txt`）
- 欢迎提交 PR，代码改动需对项目有实际帮助
- Issues 仅用于讨论 Bug 和功能请求
- Dragonwell JDK 相关代码受 GPL-2.0 + Classpath Exception 约束

---

## 当前开发：断点续传 + 文件校验 + 进度显示 + 其他改进

### 概述

断点续传、传输后 MD5 校验、文件名清洗、总体进度显示**均已实现**（协议 `VERSION_CODE` = 304，
手机端 APK 必须同步升级）。当前在做的是 P10 以后的基础设施与体验项，见下方「实现进度」。

已发版：`v3.0.4`（2026-09-15，协议 303）。**源码 HEAD 已经是协议 304（校验心跳 + 一轮审查修复），尚未发版**；
301/303/304 之间不能互连——手机端 APK 与 PC jar 必须同版本。

遗留的主要短板（有意保留）：P2-6 发送方进度语义、校验无逐文件进度、服务端无控制通道读循环（P12）。

### 完整规划

详见 `BREAKPOINT_RESUME_PLAN.md`（十章，含协议设计、逐文件改动、边界情况、编码者/用户视角改进点）。

### Issue 驱动

> 上游仓库：[weixiansen574/HybridFileXfer](https://github.com/weixiansen574/HybridFileXfer)
> （`README.md:89` 里的 `HybirdFileXfer` 是上游笔误）。**本仓库（fork）关闭了 Issues**，
> 所以 issue 只能在上游读写，`gh issue list -R weixiansen574/HybridFileXfer`。

| Issue | 标题 | 关联阶段 | 状态 |
|-------|------|----------|------|
| #43 | 断点续传 | P0-P3, P5-P7 | 已实现 |
| #5 / #115 | md5/sha256 校验 | P2, P3, P5 | 已实现（`-x/--checksum`，默认关闭） |
| #34 / #35 | 非法文件名（`?` 等）导致 Windows 收端崩溃 | P4 | 已修（`FileSanitizer`） |
| #8 / #11 / #73 / #91 / #100 | 传输中断 / 断连掉速 / 大文件 EOF | P0-P9 | 续传与校验覆盖 |
| #71 | 双轨速度反而更慢 | P13 | 未做 |
| #38 | 分块大小可配置 | P13 | 未做 |
| #53 | 拖拽（区间）多选 | P14 | 未做 |
| #74 / #93 | 锁屏 / 旋转屏幕掉后台 | P14 | 未做 |
| #70 | TF 卡访问 | P12 | 未做 |
| #36 | 跳过大小相同的同名文件 | P11 | 未做 |
| #6 | 传输时间与传输记录 | P11 | 未做 |
| #113 | 长文件名崩溃 + 不能跳过重复文件 | — | 长文件名已修（`pfd` 判空改抛 `IOException`）；跳过重复文件仍归 P11 |
| #84 | 缓冲区块数过大 → `OutOfMemoryError: Direct buffer memory` | — | 已修（`JdkHFXClient.createBuffer` 分配失败返回 null，走既有 `onOOM` 提示） |
| #90 | adb 5740 端口硬编码（被 Hyper-V/WSL 占用即失败） | P12 | 未做 |
| #106 | 用系统 adb 替代自带 `adb.exe` 提速 | — | 未做 |
| #114 | 安全披露：任意文件读写 | — | 未定论（上游作者回「不用担心」，对方未给细节） |
| ~~#109~~ | ~~视频帧错乱~~ | ~~P2（写入验证）~~ | **已关闭且是误报**：发帖人自述为手机可变帧率导致 PR 解析错误，MD5 校验全部通过。不要再拿它当 P2「写入验证」的依据 |
| ~~#85~~ | ~~PC GUI~~ | ~~P12~~ | **映射错误**：#85 是「USB ADB 连接不上」求助帖，与 PC GUI 无关 |

其余开放 issue（`#2` `#4` `#9` `#10` `#12` `#15` `#18` `#19` `#21` `#25` `#27` `#29` `#32` `#42` `#63` `#64` `#68` `#102` `#104` `#107` `#111` `#112`）
为环境适配 / 长期构想 / 使用求助，未纳入规划。

### 实现进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| P0 | CheckpointManager + 数据层（SQLite / JSON） | ✅ 已完成（Android CheckpointEntry 存 ConfigDB transfer_checkpoint 表 / PC JdkCheckpointManager 存 JSON Lines，7 天自动清理） |
| P1 | ControllerIdentifiers 新增常量 + checkpoint 协议读写 | ✅ 已完成（CHECKPOINT_REQUEST=14；握手中交换文件列表 + 检查点；VERSION_CODE 现为 304） |
| P2 | WriteFileCall：续写 + checkpoint + md5 + 写入验证 | ◑ 部分完成（续写 + 每块存档 + 完成即清除 + 磁盘校验兜底；md5 采用传输后可选校验方案，写入验证未做） |
| P3 | ReadFileCall：跳过已传块 + md5 累积 | ◑ 部分完成（跳过已传块；不做传输中 md5 累积，改为传输完成后可选全量校验） |
| P4 | FileSanitizer：文件名非法字符清洗 | ✅ 已完成（新增 `core/FileSanitizer`：非法字符/控制字符、Windows 尾部点与空格、保留设备名 CON/NUL/COM1…、超长段截断；并在握手前对传输路径去重） |
| P5 | HFXService：checkpoint + checksum 握手集成 | ◑ 部分完成（checkpoint 握手 + 磁盘有效性校验；MD5 校验以"传输完成后可选"形式实现：FILE_CHECKSUM_REQUEST=16，服务端发起、客户端主循环响应，双方各算本地副本 MD5 对比；客户端可用 `-x/--checksum` 请求校验，结果经 FILE_CHECKSUM_RESULT=17 回传） |
| P6 | IIOService.aidl + IOServiceImpl | ◑ 部分完成（新增 getFileSize=14 + 打开文件不再截断；长度处理统一由 `WriteFileCall` 做只缩不扩的 `truncate`；createAndOpenWriteableFile 带 skipBlocks 未做） |
| P7 | HFXClient / HFXServer peerId 传递 | ✅ 已完成（双方在握手中互换稳定设备标识 `deviceId()` 作为 peerId；**不能用 IP**，见约束 5） |
| P8 | TransferFileCallback 新增回调 + TransferDialog 进度条 | ✅ 已完成（onTransferStarted / onOverallProgress 进度回调 + onFileChecksumComplete 校验回调 + 进度条 UI + "MD5 校验"按钮） |
| P9 | Main / ClientActivity / TransferActivity UI 集成 | ✅ 已完成（PC 单行进度刷新；Android TransferDialog 与 ClientActivity 显示百分比/字节） |
| P10 | 日志框架 + 异常处理统一 | ⬜ 待开始 |
| P11 | 暂停/取消 + 跳过重复文件 + 传输历史 | ⬜ 待开始 |
| P12 | PC GUI + TF 卡访问 | ⬜ 待开始 |
| P13 | 分块大小可配置 + 双轨性能分析 | ⬜ 待开始 |
| P14 | 拖拽多选 + 省电提醒 | ⬜ 待开始 |
| P15 | 代码抽模块 + PC Gradle + 单元测试 | ⬜ 待开始（自检已覆盖水位线/截断/文件名清洗/校验超时/缓冲区块分配失败：`HybridFileXfer-PC/test/` 下 `WatermarkProbe` + `BufferOomProbe`） |

### 后续改动必须遵守的约束

改动 `core/` 或传输协议前先读这几条（踩过的坑，详见 `CODE_REVIEW_FINDINGS.md`）：

1. **`core/` 在 PC 与 Android 两侧是逐字节镜像。** 改一侧后必须 `cp` 同步另一侧，并分别编译：
   PC 用 `javac`；Android 侧 `core/` 可绕过 Gradle 用 javac 单独编译（不依赖 Android SDK）：

   ```bash
   cd HybridFileXfer-Android/app/src/main/java
   javac -encoding UTF-8 -cp ../../../../../HybridFileXfer-PC/libs/annotations-24.0.1.jar -d /tmp/core \
     top/weixiansen574/hybridfilexfer/core/*.java top/weixiansen574/hybridfilexfer/core/*/*.java top/weixiansen574/nio/*.java
   ```

   （`droidcore/` 依赖 Android SDK，只能在 GitHub Actions 构建里验证。）
2. **检查点只能记录连续前缀水位线**，不能记"最后落盘块的末尾"。多通道乱序下后者会跳过未到达的前序块，
   在文件里留下永久空洞（`WriteFileCall.FileState.contiguousBytes` + `advanceWatermark`）。
3. **接收端打开文件后必须 `truncate(totalSize)`**（只缩不扩）。去掉它，同名文件被改小后重传会残留脏尾巴，
   且无检查点时没有任何防护，MD5 默认关闭时为静默损坏。
4. **握手/控制通道的字段读写顺序必须严格对称**，任何增删字段都要提升 `VERSION_CODE`；
   旧版会因多读/少读字段而错位死锁（表现为传输卡死，不是报错）。
5. **对端标识不能用 IP**（用握手互换的设备标识），否则同一设备换连接方式（USB/WLAN）就会被当成新对端，续传失效。
6. 服务端（HFXServer）**没有控制通道读循环**，只能由服务端发起请求、客户端在控制循环中应答；
   客户端需要发起的能力要挂到现有握手边上，或先补 P12 的双向调度。
7. **传输路径是检查点主键 + 落盘状态（水位线）键 + 校验清单键**，必须唯一且与磁盘真实文件名一致：
   文件名清洗（`FileSanitizer`）必须确定性，清洗后可能撞车时用 `FileSanitizer.uniquePath` 加序号，
   否则两个文件会共享一份 `FileState` → 输出损坏。
8. **控制通道的阻塞读没有 SO_TIMEOUT**（NIO SocketChannel 不支持，实测无效），
   需要超时的地方用 `ReadWatchdog`（超时关通道让阻塞读抛 `AsynchronousCloseException`）。
9. **校验交换期间应答方会插入「空路径心跳」帧**（协议 304 起，`HFXService.sendChecksumHeartbeat`）：
   它靠 `Utils.md5Hex(in, onProgress)` 每读完 1MB 发一次，用来告诉发起方「我还在算」。
   读结果的一侧必须 `if (path.isEmpty()) continue;` 跳过，否则会把心跳当成路径、把真路径当成 md5，
   帧错位后误报校验失败（而且不会有任何报错，只是结论不对）。
   **心跳的回调里必须同时 `watchdog.touch()`**：只写帧不 touch 自己的话，应答方自己的看门狗
   会在这段本地计算里判超时并关掉自己的控制通道（等于没修）。
10. **检查点的 mtime 守卫只降低“目标文件被替换后续写”的概率**（`isCheckpointValid` 要求
    `目标文件 mtime <= entry.timestamp`）：保留旧 mtime 的替换（`cp -p`、robocopy /COPY:T、备份还原）
   依旧能通过；两端时钟偏差或强杀断电会让它误判成无效，退化为全量重传（方向安全）。
   要真正确认完整性只能靠传输后 MD5 校验。
11. **`WriteFileCall.cancel()` 必须保持幂等**：多通道中断时每个 `ReceiveFileCall` 都会调一次，
   写失败路径也会再调一次；不幂等会把队列里同一批缓冲块重复归还，池里出现重复引用后
   轻则下一次传输两个线程共用一块内存（数据错乱），重则 Android 侧 `freeBuffer` 二次 free。