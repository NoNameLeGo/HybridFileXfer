package top.weixiansen574.hybridfilexfer.core;

/**
 * 断点续传检查点实体。
 * 记录接收方某个文件已完成的块数，键为接收方的传输路径。
 */
public class CheckpointEntry {
    /** 接收方文件路径（传输路径） */
    public final String filePath;
    /** 文件总大小，用于有效性校验 */
    public final long totalSize;
    /** 文件最后修改时间，用于有效性校验 */
    public final long lastModified;
    /** 已完成的块数（块 0 ~ completedBlocks-1 已完成） */
    public final int completedBlocks;
    /** 对端标识 */
    public final String peerId;
    /** 记录时间戳（毫秒） */
    public final long timestamp;

    public CheckpointEntry(String filePath, long totalSize, long lastModified, int completedBlocks, String peerId, long timestamp) {
        this.filePath = filePath;
        this.totalSize = totalSize;
        this.lastModified = lastModified;
        this.completedBlocks = completedBlocks;
        this.peerId = peerId;
        this.timestamp = timestamp;
    }
}