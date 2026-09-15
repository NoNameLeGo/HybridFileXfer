package top.weixiansen574.hybridfilexfer.core;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;

public class SpeedMonitorThread extends Thread {
    /** 跨线程读写：cancel() 由 UI/传输线程调用，run() 在监控线程读 */
    private volatile boolean isRun = true;
    private final List<TransferConnection> connections;
    private final TransferFileCallback callback;
    private final long totalBytes;
    private final ProgressSource progressSource;

    public SpeedMonitorThread(List<TransferConnection> connections, TransferFileCallback callback,
                              long totalBytes, ProgressSource progressSource) {
        this.connections = connections;
        this.callback = callback;
        this.totalBytes = totalBytes;
        this.progressSource = progressSource;
    }

    @Override
    @SuppressWarnings("BusyWait")
    public void run() {
        while (isRun){
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
            //cancel() 后不要再回调：对话框可能已经 dismiss，回调会摸到已销毁的 View
            if (!isRun) {
                break;
            }
            List<TrafficInfo> trafficInfoList = new ArrayList<>();
            for (TransferConnection channel : connections) {
                trafficInfoList.add(channel.resetCurrentTrafficInfo());
            }
            callback.onSpeedInfo(trafficInfoList);
            //总体进度：每秒回调一次
            callback.onOverallProgress(progressSource.getCompletedBytes(), totalBytes);
        }
    }

    public void cancel(){
        isRun = false;
        interrupt();
    }
}