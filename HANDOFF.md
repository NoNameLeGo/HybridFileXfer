# 交接说明 — 断点续传 / 进度显示 / MD5 校验 / 文件名清洗

> 最后更新：2026-09-06（第三轮：剩余开放项已清理）
>
> **本文件只记录「当前状态与下一步」。逐条问题清单与验证证据见 `CODE_REVIEW_FINDINGS.md`；
> 改动约束见 `AGENTS.md` 的「后续改动必须遵守的约束」。**

---

## 一、当前状态

规划中的四项功能（断点续传、进度显示、MD5 校验、文件名清洗 P4）与两轮审查发现的
全部 P0/P1/P2 问题（仅 P2-6 有意保留）均已实现，改动已提交。

**这是破坏性协议变更：`VERSION_CODE` = 303，手机端必须同步升级 APK**，否则连接时会明确报版本不一致
（旧版行为是控制通道错位死锁）。

补充修复（第四轮之后，均不在 `core/`，无需双端同步）：

- **#113 长文件名崩溃**：`IOServiceImpl.openReadableFile` / `createAndOpenWriteableFile` 失败返回 null，
  而 `DroidReadFileCall.openFile` / `DroidWriteFileCall.createAndOpenFile` 直接 `pfd.getFileDescriptor()`
  → NPE（报错里连文件名都没有）。改为判空后抛 `IOException("cannot open file for reading/writing: " + path)`。
- **#84 堆外内存 OOM**：`HFXClient.connect` 依赖 `createBuffer` 「失败返回 null」的契约走 `onOOM` 提示，
  但 `JdkHFXClient.createBuffer` 直接放行 `OutOfMemoryError`，整个 JVM 带栈崩掉。加 try/catch 返回 null。
  缓冲区是堆外内存，上限等于 `-Xmx`（除非显式设 `-XX:MaxDirectMemorySize`）。

## 二、验证过的 / 未验证的

已验证：

| 项 | 方式 |
|----|------|
| PC 全量编译 + jar 冒烟（`java -jar -v`） | 本地 javac，且已由 `build.yml` 的 `pc` 任务在每次 push 上自动复查 |
| `core/` 与 `nio/` 双端逐字节一致 | `diff -r`；同样已进 `build.yml` 的 `pc` 任务 |
| Android `core/` 单独编译 | javac（免 SDK） |
| 水位线、truncate、空文件、文件名清洗/去重、看门狗超时 | `HybridFileXfer-PC/test/WatermarkProbe.java` 6/6 通过；断言有效性的反证也做过；已进 `pc` 任务 |
| 缓冲区块分配失败降级（#84） | `HybridFileXfer-PC/test/BufferOomProbe.java` 2/2 通过；反证：去掉 catch 后该探针以退出码 1 报出泄漏的 `OutOfMemoryError`；已进 `pc` 任务 |
| `-x/--checksum` 参数解析与帮助文案 | `java -cp ... Main --checksum -h` |
| `SO_TIMEOUT` 对 NIO 阻塞读无效、`close()` 可解除阻塞 | 两个独立探针实测 |
| 握手字段对称性、协议版本门 | 人工核对（无自动化手段） |
| Android 完整构建（含 `droidcore/`、CMake、R8、lintVital） | `build.yml` 的 `build` 任务，本次已通过 |

**未验证（接手第一件事）**：

1. `droidcore/`（Android 平台层）本地无法验证。
   **Android 侧一律不要本地构建**：不装 SDK/NDK/Gradle、不产 APK，全部交给 GitHub Actions
   （push 自动触发 `build.yml`，手工需要时用 `gh run list` / Actions 页面看结果）。
   本地唯一允许的是**不依赖 SDK 的 `core/` javac 类型检查**（仅编译 `core/**` + `nio/**` 到临时目录，
   不需要 ANDROID_HOME，也不产生任何 Android 产物）。
   已做的人工核对：`Config` 的 import 已补、`deviceId()` 三处覆写签名一致、无遗留旧签名调用。
2. **没有跑过双端真机传输**。需要实测的场景：
   - 传文件夹（握手清单 / 进度分母 / 子文件续传与校验）；
   - 传输中途拔线 → 重连续传（水位线不得越空洞，进度从断点起算）；
   - 同名文件改小后重传（尾部旧数据不得残留）；
   - **换连接方式续传**：`-c adb` 传一半 → 换 `-c <局域网IP>` 重连，应仍命中检查点（稳定设备标识生效）；
   - 含 `:` `?` `*` 等字符的 Android 文件名传到 Windows（不得再失败，且续传可用）；
   - `-x/--checksum` 全链路（含故意改坏接收端文件，确认能列出失败文件名）。
3. 校验看门狗的 10 分钟无进展阈值未在真机长传输上验证（正常大文件 MD5 不应触发）。

## 三、仍未做（有意保留）

- P2-6 发送方进度语义（「已读入队列」而非「已发出」，缓冲池有界故滞后有界）；
- 校验无逐文件进度（大文件时对话框只有「正在校验文件…」）；
- 服务端无控制通道读循环（P12 双向调度），客户端发起的能力只能挂在现有握手边上；
- P10（日志框架）、P11（暂停/取消、跳过重复文件、传输历史）、P12（PC GUI、TF 卡）、
  P13（分块可配置、双轨性能）、P15（抽模块、Gradle、正式单元测试）。
