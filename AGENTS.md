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

# 查看帮助
java -jar HybridFileXfer.jar -h
```

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
│   │       ├── core/          ← 传输核心（跨平台抽象）
│   │       │   ├── HFXService.java         ← 传输编排（sendFiles / receiveFiles）
│   │       │   ├── ReadFileCall.java       ← 文件读取 + 分块
│   │       │   ├── WriteFileCall.java      ← 文件写入（排序 + 续写）
│   │       │   ├── SendFileCall.java       ← 发送线程
│   │       │   ├── ReceiveFileCall.java    ← 接收线程
│   │       │   ├── FileBlock.java          ← 1MB 分块数据结构
│   │       │   ├── TransferConnection.java ← 单条传输通道
│   │       │   ├── TransferIdentifiers.java← 块级协议常量
│   │       │   └── ControllerIdentifiers.java← 控制器协议常量
│   │       ├── tasks/         ← Android 后台任务封装
│   │       ├── listadapter/   ← 文件列表 Adapter
│   │       └── IOServiceImpl.java ← AIDL Service 实现
│   └── ...
├── HybridFileXfer-PC/         ← PC 端（Java 应用）
│   ├── src/top/weixiansen574/hybridfilexfer/
│   │   ├── jdkcore/           ← JDK 平台特化实现
│   │   │   ├── JdkHFXClient.java
│   │   │   ├── JdkReadFileCall.java
│   │   │   └── JdkWriteFileCall.java
│   │   ├── core/              ← 传输核心（与 Android 共享抽象）
│   │   └── Main.java          ← 入口 + 命令行解析
│   ├── out/                  ← 编译输出
│   └── adb.exe               ← ADB 工具（用于 USB 转发）
├── HybridFileXferLauncher/    ← 启动器
├── script/                   ← 辅助脚本
└── README.md                 ← 完整文档
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
| `HFXService.VERSION_CODE` | 300 | 协议版本 |
| `HFXService.CLIENT_HEADER` | "HFXC" | 控制通道握手标识 |
| `ControllerIdentifiers.REQUEST_RECEIVE` | 10 | 请求接收文件 |
| `ControllerIdentifiers.REQUEST_SEND` | 11 | 请求发送文件 |
| `TransferIdentifiers.FILE` | 0 | 文件数据块 |
| `TransferIdentifiers.FOLDER` | 1 | 文件夹标记 |
| `TransferIdentifiers.EOF` | 3 | 传输结束 |

---

## 构建与打包

### Android 端

```bash
cd HybridFileXfer-Android
./gradlew assembleDebug   # 调试包
./gradlew assembleRelease # 正式包（需签名配置）
```

### PC 端

PC 端为 IntelliJ IDEA 项目（`.iml`），源码在 `src/`，编译输出在 `out/`。

```bash
# 在 IntelliJ 中直接运行 Main.java
# 或手动编译
javac -d out -cp "src" src/top/weixiansen574/hybridfilexfer/*.java \
  src/top/weixiansen574/hybridfilexfer/core/*.java \
  src/top/weixiansen574/hybridfilexfer/core/bean/*.java \
  src/top/weixiansen574/hybridfilexfer/core/callback/*.java \
  src/top/weixiansen574/hybridfilexfer/jdkcore/*.java \
  src/top/weixiansen574/hybridfilexfer/nio/*.java

# 打 jar
jar cvf HybridFileXfer.jar -C out .
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

当前版本**不支持断点续传**，**无文件校验**，**无总体进度显示**，且存在多项编码和用户体验问题。

### 完整规划

详见 `BREAKPOINT_RESUME_PLAN.md`（十章，含协议设计、逐文件改动、边界情况、编码者/用户视角改进点）。

### Issue 驱动

| Issue | 标题 | 关联阶段 |
|-------|------|----------|
| #43 | 断点续传 | P0-P3, P5-P7 |
| #109 | 视频帧错乱 | P2（写入验证） |
| #5 / #115 | md5/sha256 校验 | P2, P3, P5 |
| #34 / #35 | 非法文件名崩溃 | P4 |
| #8 / #11 | 传输中断崩溃 | P0-P9（天然覆盖） |
| #71 | 双轨速度反而更慢 | P13 |
| #38 | 分块大小可配置 | P13 |
| #53 | 拖拽多选 | P14 |
| #74 | 省电提醒 | P14 |
| #85 | PC GUI | P12 |
| #70 | TF 卡访问 | P12 |

### 实现进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| P0 | CheckpointManager + 数据层（SQLite / JSON） | ✅ 已完成（Android CheckpointEntry 存 ConfigDB transfer_checkpoint 表 / PC JdkCheckpointManager 存 JSON Lines，7 天自动清理） |
| P1 | ControllerIdentifiers 新增常量 + checkpoint 协议读写 | ✅ 已完成（CHECKPOINT_REQUEST=14；握手中交换文件列表 + 检查点；VERSION_CODE 升至 301） |
| P2 | WriteFileCall：续写 + checkpoint + md5 + 写入验证 | ◑ 部分完成（续写 + 每块存档 + 完成即清除 + 磁盘校验兜底；md5 累积与写入验证未做） |
| P3 | ReadFileCall：跳过已传块 + md5 累积 | ◑ 部分完成（跳过已传块；md5 未做） |
| P4 | FileSanitizer：文件名非法字符清洗 | ⬜ 待开始（Directory.generateTransferPath 已有基础替换） |
| P5 | HFXService：checkpoint + checksum 握手集成 | ◑ 部分完成（checkpoint 握手 + 磁盘有效性校验；checksum 握手未做） |
| P6 | IIOService.aidl + IOServiceImpl | ◑ 部分完成（新增 getFileSize=14 + 打开文件不再截断；createAndOpenWriteableFile 带 skipBlocks 未做） |
| P7 | HFXClient / HFXServer peerId 传递 | ✅ 已完成（客户端=服务器地址，服务端=控制通道对端 IP） |
| P8 | TransferFileCallback 新增回调 + TransferDialog 进度条 | ✅ 已完成（onTransferStarted / onOverallProgress 默认回调 + 进度条 UI） |
| P9 | Main / ClientActivity / TransferActivity UI 集成 | ✅ 已完成（PC 单行进度刷新；Android TransferDialog 与 ClientActivity 显示百分比/字节） |
| P10 | 日志框架 + 异常处理统一 | ⬜ 待开始 |
| P11 | 暂停/取消 + 跳过重复文件 + 传输历史 | ⬜ 待开始 |
| P12 | PC GUI + TF 卡访问 | ⬜ 待开始 |
| P13 | 分块大小可配置 + 双轨性能分析 | ⬜ 待开始 |
| P14 | 拖拽多选 + 省电提醒 | ⬜ 待开始 |
| P15 | 代码抽模块 + PC Gradle + 单元测试 | ⬜ 待开始（断点续传核心逻辑已有本地集成测试通过） |