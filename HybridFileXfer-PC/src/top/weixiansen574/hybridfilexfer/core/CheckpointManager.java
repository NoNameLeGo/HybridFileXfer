package top.weixiansen574.hybridfilexfer.core;

import java.util.List;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * 断点续传检查点数据层抽象。
 * <p>持久化单位为字节偏移（completedBytes），与分块大小解耦；
 * 键约定：filePath 使用接收方的传输路径（对端落盘路径），peerId 用于区分不同对端。</p>
 */
public interface CheckpointManager {

    /**
     * 保存/更新一个文件的检查点。
     *
     * @param filePath        接收方文件路径（传输路径）
     * @param totalSize       文件总大小
     * @param lastModified    文件最后修改时间
     * @param completedBytes  已确认完成的字节偏移
     * @param peerId          对端标识
     */
    void saveCheckpoint(String filePath, long totalSize, long lastModified, long completedBytes, String peerId);

    /**
     * 加载文件列表中匹配的检查点。
     * <p>仅返回 peerId 匹配、且 totalSize 与 lastModified 均与存储一致（防止文件被改动）的条目。</p>
     *
     * @param files  本次传输的文件列表（传输路径）
     * @param peerId 对端标识
     * @return filePath → CheckpointEntry
     */
    Map<String, CheckpointEntry> loadCheckpoints(List<RemoteFile> files, String peerId);

    /**
     * 清除单个文件的检查点（文件传输完成时调用）。
     */
    void clearCheckpoint(String filePath, String peerId);

    /**
     * 清除某个对端的全部检查点。
     */
    void clearAllCheckpoints(String peerId);

    /**
     * 清理超过指定天数未更新的检查点。
     *
     * @param maxAgeDays 最大保留天数
     */
    void cleanupOldCheckpoints(int maxAgeDays);
}