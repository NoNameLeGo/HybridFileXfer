package top.weixiansen574.hybridfilexfer.core;

/**
 * 断点续传检查点实体。
 * 记录接收方某个文件已确认完成的字节偏移，键为接收方的传输路径。
 * <p>持久化单位为字节偏移而非块数，与分块大小解耦：
 * 无论将来块大小如何变化，已确认字节数始终有效。</p>
 */
public class CheckpointEntry {
    /** 接收方文件路径（传输路径） */
    public final String filePath;
    /** 文件总大小，用于有效性校验 */
    public final long totalSize;
    /** 文件最后修改时间，用于有效性校验 */
    public final long lastModified;
    /** 已确认完成的字节偏移（0 ~ totalSize） */
    public final long completedBytes;
    /** 对端标识 */
    public final String peerId;
    /**
     * 记录时间戳（毫秒），每次 saveCheckpoint 刷新。
     * <p>接收方用它判断「目标文件是否在检查点记录之后被改动过」：
     * 正常续传时目标文件的 mtime ≤ 记录时间（写入发生在记录之前，或已回写为源文件的 mtime）；
     * 若目标文件在中断后被外部替换/改过，mtime 会大于该时间戳，检查点作废 → 全量重传。</p>
     */
    public final long timestamp;

    public CheckpointEntry(String filePath, long totalSize, long lastModified, long completedBytes, String peerId, long timestamp) {
        this.filePath = filePath;
        this.totalSize = totalSize;
        this.lastModified = lastModified;
        this.completedBytes = completedBytes;
        this.peerId = peerId;
        this.timestamp = timestamp;
    }
}