# 断点续传 + 文件校验 + 进度显示 — 完整规划文档

> 状态：规划完成，待实现
> 目标：为 HybridFileXfer 增加块级断点续传、传输后文件校验、整体进度显示

---

## 一、背景与需求来源

### 用户 Issue 驱动

| Issue | 标题 | 类型 | 评论 |
|-------|------|------|------|
| #43 | 能否增加断点续传？ | 需求 | 2 |
| #109 | 视频文件传输丢帧掉帧、时间错乱 | 严重 Bug | 4 |
| #5 | 双端增加 md5/sha256 文件校验 | 需求 | 8 |
| #115 | 文件传输完毕是否应该加一个校验 | 需求 | 0 |
| #34 | 非法文件名导致 Windows 端崩溃 | Bug | 9 |
| #35 | Windows 不支持 `:*?"<>` 字符 | Bug | 5 |
| #8 | 传输时被强制中断（EOFException） | Bug | 7 |
| #11 | 文件传输中断（速度归零后报错） | Bug | 4 |
| #75 | 传输卡死然后闪退 | Bug | 5 |

### 功能汇总

```
┌─────────────────────────────────────────────┐
│           HybridFileXfer 改进计划            │
├─────────────────────────────────────────────┤
│  P0: 断点续传 (Issue #43, #8, #11, #75)     │
│  P1: 文件校验 (Issue #109, #5, #115)        │
│  P2: 进度显示 (新需求)                       │
│  P3: 文件名兼容 (Issue #34, #35)            │
└─────────────────────────────────────────────┘
```

---

## 二、现状分析

### 当前传输流程

```
发送方                              接收方
  │                                   │
  ├─ ReadFileCall ─→ 1MB分块 ─→ 队列  │
  ├─ SendFileCall × N ─→ 网络 ──→ ReceiveFileCall × N
  │                                   ├─ 排序队列
  │                                   └─ WriteFileCall ─→ 磁盘
```

### 为什么没有断点续传

| 问题 | 位置 | 影响 |
|------|------|------|
| 传输状态不持久化 | `ConfigDB` 只存书签 | 中断后无进度可恢复 |
| 文件每次重建 | `JdkWriteFileCall.createAndOpenFile()` 调用 `setLength()` 截断 | 已传数据全部丢弃 |
| 发送方不知接收方进度 | 无 checkpoint 交换协议 | 只能从头发 |
| 中断只报错不恢复 | `onIncomplete()` 仅弹提示 | 无恢复入口 |

### 为什么视频会丢帧（Issue #109）

`WriteFileCall` 从多个通道队列中取块时，理论上通过 `compareTo()` 排序保证顺序。但存在边界情况：

1. **多通道并发写入**：通道 A 的块 3 和通道 B 的块 2 同时到达，`tryTakeBlockInternal()` 取最小块，但如果写入线程在 `channel.write()` 时被中断（GC、IO 阻塞），块可能以非预期顺序落盘
2. **FileChannel.write() 不保证一次写入全部字节**：`channel.write(data)` 是单次调用，但不保证原子性。在极端情况下，如果进程被 kill -9，文件可能处于不一致状态
3. **无完整性校验**：传输完成后不验证数据一致性，错就错了

### 为什么没有进度显示

| 问题 | 位置 | 影响 |
|------|------|------|
| 无总大小概念 | 传输开始前不计算总字节数 | 无法显示整体百分比 |
| 仅 per-file 回调 | `onFileUploading(path, target, total)` | 用户看不到总体进度 |
| `TransferDialog` 无进度条 | 只有速度和事件日志 | 大文件传输无体感 |

### 可利用的现有机制

- 文件按 1MB 分块，块索引 `(fileIndex, index)` 严格有序
- `WriteFileCall` 通过 `channel.position(cursor)` 支持 seek 到任意位置写入
- 控制器通道 `ctChannel` 已有完整的请求/响应协议框架
- `FileBlock.compareTo()` 保证块按 `(fileIndex, index)` 排序取出
- `TransferConnection` 已追踪 `uploadTraffic` / `downloadTraffic` 字节数
- `TransferFileCallback` 已有 `onFileUploading` / `onFileDownloading` 回调

---

## 三、设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 续传粒度 | **块级（1MB）** | 文件级太粗，大文件中断后重传浪费 |
| 校验粒度 | **文件级 md5** | 块级校验开销太大，文件级足够 |
| 校验时机 | **传输完成后** | 不阻塞传输流程，异步计算 |
| 进度粒度 | **字节级（总体百分比）** | 比文件级精细，比块级简单 |
| 持久化 | Android: SQLite 扩展 `ConfigDB` / PC: JSON 文件 | 简单可靠，跨会话恢复 |
| 触发方式 | **自动** | 中断后重连自动加载 checkpoint，用户无感 |
| 检查点键 | `(filePath, peerId)` | 同一对文件对同一对端自动续传 |
| 有效性校验 | `totalSize` + `lastModified` | 防止文件被改动后 checkpoint 失效 |
| 握手时机 | 传输开始前，通过 `ctChannel` 交换 | 复用已有控制器通道，不新增连接 |
| checkpoint 生命周期 | 传输完成自动清除 / 手动清除 / 超过 7 天自动清理 | 防止残留数据堆积 |

---

## 四、协议扩展

### 新增常量

```java
// ControllerIdentifiers.java
public static final short CHECKPOINT_REQUEST  = 14;  // 发送方请求接收方的检查点
public static final short CHECKPOINT_RESPONSE = 15;  // 接收方返回检查点数据
public static final short FILE_CHECKSUM_REQUEST  = 16;  // 请求传输文件校验和
public static final short FILE_CHECKSUM_RESPONSE = 17;  // 返回文件校验和
```

### 握手流程

```
发送方                              接收方
  │                                   │
  |--- REQUEST_RECEIVE/SEND --------->|       ← 现有流程
  |--- CHECKPOINT_REQUEST ----------->|       ← 新增
  |                                    |  读取本地 checkpoint
  |<--- CHECKPOINT_RESPONSE ----------|       ← 新增
  |                                    |
  | 创建 ReadFileCall                  |
  |   (跳过已传块)                     |
  | 创建 WriteFileCall                 |
  |   (从断点续写)                     |
  |--- 数据传输 ---------------------->|
  |                                    |
  |--- FILE_CHECKSUM_REQUEST ------>|       ← 新增：传输完成后校验
  |<--- FILE_CHECKSUM_RESPONSE ------|       ← 新增
```

### checkpoint 数据序列化格式

```
int:               checkpoint 条目数量
for each entry:
  String (UTF):    file_path
  long:            total_size
  long:            last_modified
  int:             completed_blocks
```

### 文件校验序列化格式

```
int:               文件数量
for each file:
  String (UTF):    file_path
  String (UTF):    md5_hex (32 chars)
```

---

## 五、文件修改清单

### 5.1 新增文件

| 文件 | 位置 | 用途 |
|------|------|------|
| `CheckpointEntry.java` | `core/` | 序列化实体 |
| `CheckpointManager.java` | `core/` | checkpoint 数据层抽象接口 |
| `AndroidCheckpointManager.java` | Android `droidcore/` | SQLite 实现 |
| `JdkCheckpointManager.java` | PC `jdkcore/` | JSON 文件实现 |
| `FileSanitizer.java` | `core/` | 文件名非法字符清洗 |
| `TransferProgress.java` | `core/` | 传输进度追踪器 |
| `BREAKPOINT_RESUME_PLAN.md` | 项目根目录 | 本规划文档 |

### 5.2 修改文件

| 文件 | 改动 | 关联 Issue |
|------|------|------------|
| `ControllerIdentifiers.java` | 新增 `CHECKPOINT_REQUEST/RESPONSE`, `FILE_CHECKSUM_REQUEST/RESPONSE` | #43, #5 |
| `HFXService.java` | checkpoint 握手 + 文件校验握手 + 进度回调 | #43, #5, #115 |
| `WriteFileCall.java` | 跳过已传块 + 续写 + 存档 + 顺序写入加固 + md5 累积 | #43, #109 |
| `ReadFileCall.java` | 加载 checkpoint，跳过已传块 + md5 累积 | #43, #5 |
| `JdkWriteFileCall.java` | `createAndOpenFile()` 支持 skipBlocks + 文件名清洗 | #43, #34 |
| `JdkReadFileCall.java` | `readToDeque()` 支持跳过已传块 | #43 |
| `DroidWriteFileCall.java` | `createAndOpenFile()` 支持 skipBlocks + 文件名清洗 | #43, #34 |
| `DroidReadFileCall.java` | `readToDeque()` 支持跳过已传块 | #43 |
| `IIOService.aidl` | 新增 `createAndOpenWriteableFile(path, length, skipBlocks)` | #43 |
| `IOServiceImpl.java` | 实现新的 AIDL 接口 + 文件名清洗 | #43, #34 |
| `ConfigDB.java` (Android) | 新增 `transfer_checkpoint` 表 + CRUD | #43 |
| `HFXClient.java` | 传递 peerId + 进度回调 | #43, 进度 |
| `HFXServer.java` | 传递 peerId + 进度回调 | #43, 进度 |
| `TransferFileCallback.java` | 新增 `onOverallProgress()` 回调方法 | 进度 |
| `TransferDialog.java` (Android) | 添加总体进度条 + 校验结果展示 | 进度, #5 |
| `ClientActivity.java` (Android) | 中断后恢复提示 | #8, #11 |
| `TransferActivity.java` (Android) | 手动清除 checkpoint | #43 |
| `Main.java` (PC) | 进度显示 + 校验结果输出 | 进度, #5 |

---

## 六、各模块详细设计

### 6.1 CheckpointEntry

```java
public class CheckpointEntry implements Serializable {
    String filePath;
    long totalSize;
    long lastModified;
    int completedBlocks;  // 已完成的块数（块 0 ~ completedBlocks-1）
    String peerId;
    long timestamp;
}
```

### 6.2 CheckpointManager 接口

```java
public interface CheckpointManager {
    void saveCheckpoint(String filePath, long totalSize, long lastModified,
                        int completedBlocks, String peerId);
    Map<String, CheckpointEntry> loadCheckpoints(List<String> filePaths, String peerId);
    void clearCheckpoint(String filePath, String peerId);
    void clearAllCheckpoints(String peerId);
    void cleanupOldCheckpoints(int maxAgeDays);
}
```

### 6.3 Android: ConfigDB 扩展

```sql
CREATE TABLE transfer_checkpoint (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path TEXT NOT NULL,
    total_size INTEGER NOT NULL,
    last_modified INTEGER NOT NULL,
    completed_blocks INTEGER NOT NULL DEFAULT 0,
    peer_id TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    UNIQUE(file_path, peer_id)
);
```

### 6.4 PC: JdkCheckpointManager

JSON 文件 `~/.hybridfilexfer/checkpoints.json`。

### 6.5 FileSanitizer — 文件名清洗（Issue #34, #35）

```java
public class FileSanitizer {
    // Windows 禁止字符
    private static final char[] WINDOWS_INVALID_CHARS = 
        {'\\', '/', ':', '*', '?', '"', '<', '>', '|'};

    public static String sanitize(String path) {
        // 1. 替换 Windows 禁止字符为下划线
        StringBuilder sb = new StringBuilder(path);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            for (char invalid : WINDOWS_INVALID_CHARS) {
                if (c == invalid) {
                    sb.setCharAt(i, '_');
                    break;
                }
            }
        }
        // 2. 去除尾部空格和点（Windows 保留名问题）
        String result = sb.toString().trim();
        while (result.endsWith(".") || result.endsWith(" ")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        // 3. 处理 Windows 保留名（CON, PRN, AUX, NUL, COM1-9, LPT1-9）
        String name = result;
        int lastSlash = Math.max(lastSlash, 0);
        // ... 保留名检测
        return result;
    }
}
```

### 6.6 TransferProgress — 进度追踪器

```java
public class TransferProgress {
    private long totalBytes;
    private long completedBytes;
    private int totalFiles;
    private int completedFiles;
    private final List<TrafficInfo> connections;

    public TransferProgress(List<TransferConnection> connections) {
        this.connections = connections;
    }

    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

    public long getCompletedBytes() {
        long total = 0;
        for (TransferConnection conn : connections) {
            total += conn.getTotalTraffic().uploadTraffic;  // 或 downloadTraffic
        }
        return total;
    }

    public int getProgressPercent() {
        if (totalBytes == 0) return 0;
        return (int) (getCompletedBytes() * 100 / totalBytes);
    }

    public String getProgressText() {
        return String.format("%d%% (%s/%s)",
            getProgressPercent(),
            formatSize(getCompletedBytes()),
            formatSize(totalBytes));
    }
}
```

### 6.7 WriteFileCall 改动（含 Issue #109 修复）

```java
public abstract class WriteFileCall implements Callable<Void> {
    private final Map<String, Integer> checkpoints;
    private final CheckpointManager checkpointManager;
    private final String peerId;
    private MessageDigest md5Digest;  // 新增：md5 累积
    private Map<String, String> fileMd5s;  // 新增：path → md5_hex

    public WriteFileCall(..., Map<String, Integer> checkpoints,
                         CheckpointManager cm, String peerId) {
        ...
        this.checkpoints = checkpoints;
        this.checkpointManager = cm;
        this.peerId = peerId;
        this.fileMd5s = new HashMap<>();
        try {
            this.md5Digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Void call() throws Exception {
        FileBlock block = takeBlock();
        FileBlock lastBlock = null;
        FileChannel lastChannel = null;
        long cursor = 0;
        String currentFilePath = null;

        while (block != null) {
            if (!block.isFile) {
                // 文件夹处理
                String file = block.path;
                tryMkdirs(file);
                setLastModified(file, block.lastModified);
                block = takeBlock();
                continue;
            }

            int skipBlocks = checkpoints.getOrDefault(block.path, 0);

            if (!block.path.equals(currentFilePath)) {
                // 新文件：切换 md5
                if (currentFilePath != null && md5Digest != null) {
                    String md5 = bytesToHex(md5Digest.digest());
                    fileMd5s.put(currentFilePath, md5);
                }
                currentFilePath = block.path;
                md5Digest.reset();

                if (lastChannel != null) {
                    closeFile();
                    setLastModified(lastBlock.path, lastBlock.lastModified);
                }

                if (skipBlocks > 0 && block.index < skipBlocks) {
                    // 已传块，跳过
                    buffers.add(block.data);
                    lastBlock = block;
                    block = takeBlock();
                    continue;
                }

                // 文件名清洗（Issue #34/#35）
                String safePath = FileSanitizer.sanitize(block.path);
                channel = createAndOpenFile(safePath, block.totalSize, skipBlocks);
                cursor = (long) skipBlocks * FileBlock.BLOCK_SIZE;
                lastChannel = channel;
            } else {
                channel = lastChannel;
            }

            // 写入前 seek（Issue #109 加固：确保位置正确）
            if (cursor != block.getStartPosition()) {
                cursor = block.getStartPosition();
                channel.position(cursor);
            }

            ByteBuffer data = block.data;
            data.flip();

            // Issue #109 加固：写入后验证实际写入字节数
            int written = channel.write(data);
            if (written != data.limit()) {
                throw new IOException("Incomplete write: expected " + data.limit() 
                    + " but wrote " + written);
            }

            // 更新 md5
            if (md5Digest != null) {
                md5Digest.update(data.array(), data.arrayOffset(), data.limit());
            }

            cursor += written;
            buffers.add(block.data);

            // 更新 checkpoint
            checkpoints.put(block.path, block.index + 1);
            checkpointManager.saveCheckpoint(block.path, block.totalSize,
                block.lastModified, block.index + 1, peerId);

            lastBlock = block;
            block = takeBlock();
        }

        // 最后一个文件的 md5
        if (currentFilePath != null && md5Digest != null) {
            String md5 = bytesToHex(md5Digest.digest());
            fileMd5s.put(currentFilePath, md5);
        }

        if (lastBlock != null) {
            closeFile();
            setLastModified(lastBlock.path, lastBlock.lastModified);
            checkpointManager.clearCheckpoint(lastBlock.path, peerId);
        }
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public Map<String, String> getFileMd5s() {
        return fileMd5s;
    }

    protected abstract FileChannel createAndOpenFile(String path, long length, int skipBlocks) throws Exception;
}
```

### 6.8 ReadFileCall 改动（含 md5 累积）

```java
public abstract class ReadFileCall implements Callable<Void> {
    private final Map<String, Integer> checkpoints;
    private MessageDigest md5Digest;
    private Map<String, String> fileMd5s;

    public ReadFileCall(..., Map<String, Integer> checkpoints) {
        ...
        this.checkpoints = checkpoints;
        this.fileMd5s = new HashMap<>();
        try {
            this.md5Digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void readToDeque(RemoteFile file) throws Exception {
        fileIndex++;
        if (file.isDirectory()) {
            deque.add(new FileBlock(false, fileIndex, ...));
            return;
        }

        FileChannel channel = openFile(file.getPath());
        long length = channel.size();
        long lastModified = file.lastModified();

        int skipBlocks = checkpoints.getOrDefault(file.getPath(), 0);
        if (skipBlocks > 0) {
            channel.position((long) skipBlocks * FileBlock.BLOCK_SIZE);
        }

        long remaining = length - (long) skipBlocks * FileBlock.BLOCK_SIZE;
        if (remaining <= 0) {
            closeFile();
            return;
        }

        md5Digest.reset();
        int i = skipBlocks;
        while (remaining > 0) {
            int blkSize = (int) Math.min(remaining, FileBlock.BLOCK_SIZE);
            ByteBuffer buffer = buffers.take();
            buffer.clear();
            buffer.limit(blkSize);
            while (buffer.hasRemaining()) {
                channel.read(buffer);
            }
            // 更新 md5
            if (md5Digest != null) {
                md5Digest.update(buffer.array(), buffer.arrayOffset(), blkSize);
            }
            deque.add(new FileBlock(true, fileIndex, ..., lastModified, length, i, buffer));
            remaining -= blkSize;
            i++;
        }

        // 记录文件 md5
        if (md5Digest != null) {
            String md5 = bytesToHex(md5Digest.digest());
            fileMd5s.put(file.getPath(), md5);
        }
        closeFile();
    }

    public Map<String, String> getFileMd5s() {
        return fileMd5s;
    }
}
```

### 6.9 HFXService.sendFiles() 改动

```java
protected boolean sendFiles(List<RemoteFile> fileList, Directory localDir,
                            Directory remoteDir, TransferFileCallback callback) throws IOException {
    // 1. Checkpoint 握手
    ctChannel.writeShort(ControllerIdentifiers.CHECKPOINT_REQUEST);
    Map<String, Integer> checkpoints = readCheckpointResponse(ctChannel);

    // 2. 计算总传输量（用于进度显示）
    long totalBytes = 0;
    for (RemoteFile f : fileList) {
        totalBytes += f.getSize();
    }
    callback.onTransferStarted(totalBytes, fileList.size());

    // 3. 创建读取调用
    ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir,
        remoteDir, connections.size(), checkpoints);

    // ... 原有传输逻辑 ...

    // 4. 传输完成后：文件校验握手
    ctChannel.writeShort(ControllerIdentifiers.FILE_CHECKSUM_REQUEST);
    Map<String, String> localMd5s = readFileCall.getFileMd5s();
    writeChecksums(ctChannel, localMd5s);
    Map<String, String> remoteMd5s = readChecksums(ctChannel);

    // 5. 比较校验结果
    Map<String, String> mismatches = compareMd5s(localMd5s, remoteMd5s);
    callback.onFileChecksumComplete(mismatches);

    return true;
}
```

### 6.10 HFXService.receiveFiles() 改动

```java
protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
    // 1. Checkpoint 握手
    short req = ctChannel.readShort();
    Map<String, Integer> checkpoints = Collections.emptyMap();
    if (req == ControllerIdentifiers.CHECKPOINT_REQUEST) {
        checkpoints = checkpointManager.loadCheckpoints(currentFilePaths, peerId);
        writeCheckpointResponse(ctChannel, checkpoints);
    }

    // 2. 创建写入调用
    WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size(),
        checkpoints, checkpointManager, peerId);

    // ... 原有传输逻辑 ...

    // 3. 传输完成后：文件校验握手
    short checkReq = ctChannel.readShort();
    if (checkReq == ControllerIdentifiers.FILE_CHECKSUM_REQUEST) {
        Map<String, String> localMd5s = writeFileCall.getFileMd5s();
        writeChecksums(ctChannel, localMd5s);
    }

    return true;
}
```

### 6.11 TransferFileCallback 新增方法

```java
public interface TransferFileCallback {
    // ... 现有方法 ...

    // 新增：总体进度
    void onTransferStarted(long totalBytes, int totalFiles);
    void onOverallProgress(long completedBytes, long totalBytes, int percent);

    // 新增：文件校验结果
    void onFileChecksumComplete(Map<String, String> mismatches);
}
```

### 6.12 TransferDialog 进度显示

在现有 `TransferDialog` 中添加：
- 总体进度条（ProgressBar）
- 百分比文本
- 传输文件数进度（如 "3/10 文件"）
- 校验结果提示（"校验通过" / "N 个文件校验失败"）

---

## 七、实现顺序

```
P0 ─┬─ CheckpointEntry + CheckpointManager 接口
    ├─ Android: ConfigDB 建表 + CRUD
    └─ PC: JSON 文件读写

P1 ─ ControllerIdentifiers 新增常量
    + ctChannel checkpoint 读写

P2 ─ WriteFileCall: 跳过/续写/存档 + md5 累积 + 写入验证
    + JdkWriteFileCall / DroidWriteFileCall 文件打开

P3 ─ ReadFileCall: 跳过已传块 + md5 累积
    + JdkReadFileCall / DroidReadFileCall

P4 ─ FileSanitizer: 文件名非法字符清洗（Issue #34/#35）
    + JdkWriteFileCall / DroidWriteFileCall / IOServiceImpl 集成

P5 ─ HFXService: checkpoint + checksum 握手集成

P6 ─ IIOService.aidl + IOServiceImpl

P7 ─ HFXClient / HFXServer peerId 传递

P8 ─ TransferFileCallback 新增回调方法
    + TransferDialog 进度条 + 校验结果展示

P9 ─ Main.java (PC) 进度输出 + 校验结果
    + ClientActivity / TransferActivity (Android) 中断恢复 + 手动清除
```

---

## 八、边界情况与风险

| 场景 | 处理方式 |
|------|----------|
| 文件被删除 | checkpoint 校验时文件不存在 → 跳过 |
| 文件被修改 | `lastModified` 或 `totalSize` 不匹配 → 跳过 |
| 文件大小变小 | `completedBlocks * BLOCK_SIZE > totalSize` → 跳过 |
| 传输完成 | 自动清除该文件的 checkpoint |
| 全新传输 | checkpoint 为空 → 走原有逻辑 |
| 多文件部分续传 | 每个文件独立 checkpoint |
| checkpoint 过期 | 默认 7 天自动清理 |
| peerId 不匹配 | 不同对端传输不共享 checkpoint |
| 校验不匹配 | `onFileChecksumComplete()` 回调，UI 提示 |
| 写入不完整 | `channel.write()` 返回值校验，不匹配则抛异常 |
| 文件名包含 `:*?"<>` | `FileSanitizer.sanitize()` 替换为 `_` |
| 文件名过长 | 截断或替换（需测试确定安全长度） |
| md5 计算失败 | 降级为跳过校验，不阻塞传输 |

---

## 九、性能影响

| 指标 | 影响 | 缓解 |
|------|------|------|
| 每次块写入 | 多一次 SQLite/JSON 写入 | 异步写入或批量写入 |
| 传输开始 | 多一次 RTT（checkpoint 请求/响应） | 约 1-2ms，可忽略 |
| 磁盘 IO | seek 操作可能降低顺序写入性能 | 仅续传时触发 |
| md5 计算 | 每次块写入多一次哈希更新 | MD5 极快，可忽略 |
| 内存 | `checkpoints` Map + `fileMd5s` Map | KB 级 |
| 进度回调 | 每秒数次回调 | 节流，避免 UI 阻塞 |

---

---

## 十、其他改进点（编码者 & 用户视角）

### 10.1 编码者视角

#### 代码质量

| 优先级 | 改进项 | 问题 | 位置 |
|--------|--------|------|------|
| P1 | `printStackTrace()` → 日志框架 | 生产环境无日志，问题不可排查 | `HFXServer`, `DisconnectTask` 等多处 |
| P1 | 空 catch 块统一处理 | `catch (IOException ignored)` 吞异常，连接可能半开 | `HFXServer.disconnect()` |
| P2 | 魔法注释清理 | `ControllerIdentifiers` 末尾中文字段，GPL 开源项目全球可见 | `ControllerIdentifiers.java` |
| P2 | 硬编码常量抽取配置 | `BLOCK_SIZE = 1MB` 写死，HDD/SSD 无法调节 | `FileBlock.java` |
| P2 | 异常类型统一 | 有的 `throws Exception`，有的 `throws IOException`，裸 `Exception` | 全局 |

#### 架构

| 优先级 | 改进项 | 问题 | 位置 |
|--------|--------|------|------|
| P1 | Android/PC 共享代码抽模块 | `SendFileCall`, `ReceiveFileCall`, `TransferConnection` 两侧几乎相同 | `core/` vs `droidcore/` + `jdkcore/` |
| P1 | PC 端引入 Gradle/Maven | 仅 IntelliJ `.iml`，`javac` 靠手拼，无法 CI/CD | `HybridFileXfer-PC/` |
| P2 | AIDL 接口版本管理 | `IIOService.aidl` 无 version code，新增方法破坏旧客户端 | `IIOService.aidl` |
| P2 | `HFXService` 职责拆分 | 传输编排 + 协议处理 + 缓冲区管理混在一起 | `HFXService.java` |
| P3 | 单元测试 | 零测试，改代码全靠人工验证 | 全局 |
| P3 | `NativeMemory.cpp` 注释和错误处理 | JNI 直接操作 ByteBuffer，bug = crash 或内存泄漏 | `native-memory.cpp` |

#### 可维护性

| 优先级 | 改进项 | 问题 | 位置 |
|--------|--------|------|------|
| P1 | 引入日志框架（SLF4J + Logback） | 全部 `System.out.println`，无法控制级别 | 全局 |
| P2 | 编码风格统一（ import 顺序、命名） | 两侧代码风格不一致 | 全局 |
| P3 | README/文档自动化 | 手动维护，容易过时 | `README.md` |

---

### 10.2 用户视角

#### 传输体验

| 优先级 | 改进项 | Issue | 说明 |
|--------|--------|-------|------|
| P0 | 总体进度条 | 新需求 | 传 18G 文件完全黑箱，无体感 |
| P0 | 断点续传 | #43 | 中断后重头来过，大文件灾难 |
| P0 | 文件校验 (md5) | #5, #115 | 传完不知道文件是否损坏 |
| P1 | 暂停/取消传输 | 新需求 | 传输途中无法暂停，只能强制中断 |
| P1 | 跳过重复文件 | #113 | 同名直接覆盖，无"跳过"选项 |
| P1 | 传输历史记录 | 新需求 | 传过的文件无法复查 |
| P2 | 传输队列 | 新需求 | 同时只能传一批，传完才能选下一批 |
| P2 | 速度限制/节流 | 新需求 | 快速传输时占满带宽影响其他设备 |

#### 平台兼容

| 优先级 | 改进项 | Issue | 说明 |
|--------|--------|-------|------|
| P0 | 文件名非法字符清洗 | #34, #35 | Android 的 `:*?"<>` 在 Windows 直接崩溃 |
| P1 | PC GUI 图形界面 | #85 | 命令行门槛高，普通用户不会用 |
| P1 | TF 卡访问 | #70 | 部分低端手机插卡也能传 |
| P2 | 鸿蒙兼容性 | #52 | 有用户报告目录读不出（暂不处理） |
| P2 | Mac 测试 | #2 | README 自己说"没尝试过"（暂不处理） |

#### 性能

| 优先级 | 改进项 | Issue | 说明 |
|--------|--------|-------|------|
| P0 | 双轨性能分析 | #71 | USB+WiFi 双轨反而不如单 USB，11 条评论 |
| P1 | 分块大小可配置 | #38 | HDD 上 1MB 太小导致频繁寻道 |
| P2 | WiFi 6/7 充分利用 | #32 | 理论 2.8Gbps，实际跑不满 |

#### UI/UX

| 优先级 | 改进项 | Issue | 说明 |
|--------|--------|-------|------|
| P1 | 拖拽多选 | #53 | 一个一个点选太麻烦 |
| P1 | 省电策略提醒 | #74 | 锁屏后传输中断，用户不知原因 |
| P2 | 深色模式 | 新需求 | 夜间使用刺眼 |
| P2 | 实时速度图表 | 新需求 | 数字已有，图表锦上添花 |
| P3 | 启动器跨平台 | #95 | 目前只有 Windows 启动器，Linux/Mac 需手动跑 jar |

---

### 10.3 优先级汇总

```
P0 (必须做):
  ├── 断点续传 (已在 P0-P9 规划)
  ├── 文件校验 md5 (已在 P2/P3/P5 规划)
  ├── 总体进度条 (已在 P8/P9 规划)
  ├── 文件名非法字符清洗 (已在 P4 规划)
  └── 双轨性能分析 (新增，需排查根因)

P1 (应该做):
  ├── printStackTrace → 日志框架
  ├── 空 catch 块统一处理
  ├── 暂停/取消传输
  ├── 跳过重复文件
  ├── 传输历史记录
  ├── PC GUI 图形界面
  ├── TF 卡访问
  ├── 分块大小可配置
  ├── 拖拽多选
  └── 省电策略提醒

P2 (可以做):
  ├── Android/PC 共享代码抽模块
  ├── PC 端 Gradle/Maven
  ├── AIDL 接口版本管理
  ├── HFXService 职责拆分
  ├── 传输队列
  ├── 速度限制/节流
  ├── WiFi 6/7 优化
  ├── 深色模式
  ├── 实时速度图表
  ├── 魔法注释清理
  ├── 硬编码常量配置化
  ├── 异常类型统一
  ├── 编码风格统一
  └── NativeMemory 注释和错误处理

P3 (有空做):
  ├── 单元测试
  ├── README/文档自动化
  └── 启动器跨平台
```

---

*最后更新：2026-09-05*