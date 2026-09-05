package top.weixiansen574.hybridfilexfer.core.callback;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

public interface TransferFileCallback {
    int ERROR_TYPE_EXCEPTION = 1;
    int ERROR_TYPE_INTERRUPT = 2;
    int ERROR_TYPE_READ_ERROR = 3;
    int ERROR_TYPE_WRITE_ERROR = 4;

    void onFileUploading(String iName, String path, long targetSize, long totalSize);

    void onFileDownloading(String iName, String path, long targetSize, long totalSize);

    void onSpeedInfo(List<TrafficInfo> trafficInfoList);

    void onChannelComplete(String iName, long traffic, long time);

    void onChannelError(String iName, int errorType, String message);//异常信息 or 断开

    void onReadFileError(String message);

    void onWriteFileError(String message);

    void onComplete(boolean isUpload,long traffic, long time);

    //传输通道有其中一个断开时
    void onIncomplete();

    /**
     * 传输开始（已协商完毕，即将开始读/写文件）。
     *
     * @param totalBytes 总传输字节数（不含文件夹，断点续传时仍为文件完整大小）
     * @param totalFiles 总文件数（不含文件夹）
     */
    default void onTransferStarted(long totalBytes, int totalFiles) {
    }

    /**
     * 总体传输进度（约每秒回调一次）。
     *
     * @param completedBytes 已完成字节数（含断点续传跳过的块）
     * @param totalBytes     总传输字节数
     */
    default void onOverallProgress(long completedBytes, long totalBytes) {
    }

}
