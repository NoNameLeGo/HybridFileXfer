package top.weixiansen574.hybridfilexfer.core;

/**
 * 传输进度数据源。
 * <p>ReadFileCall（发送方）与 WriteFileCall（接收方）实现此接口，
 * 由 SpeedMonitorThread 周期读取已完成字节数，用于总体进度显示。</p>
 */
public interface ProgressSource {
    /**
     * @return 已完成的字节数（含断点续传跳过的块）
     */
    long getCompletedBytes();
}