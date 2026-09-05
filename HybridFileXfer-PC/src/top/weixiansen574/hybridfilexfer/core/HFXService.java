package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    public static final int VERSION_CODE = 301;
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;
    /** 对端标识：作为断点续传检查点的键（区分不同对端） */
    protected String peerId;
    /** 最近一次传输中本方是否为接收方（落盘方）。用于传输完成后可选校验的路径解析 */
    protected boolean receiverSide = false;
    /** 发送方角色：传输路径 → 本地源路径（最近一次 sendFiles 记录） */
    protected final Map<String, String> transferToSource = new HashMap<>();
    /** 接收方角色：最近一次 receiveFiles 收到的传输路径列表（落盘路径） */
    protected final List<String> receivedTransferPaths = new ArrayList<>();
    private CheckpointManager checkpointManager;

    protected synchronized CheckpointManager getCheckpointManager() {
        if (checkpointManager == null) {
            checkpointManager = createCheckpointManager();
        }
        return checkpointManager;
    }

    protected abstract CheckpointManager createCheckpointManager();

    protected boolean sendFiles(List<RemoteFile> fileList,Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        //记录传输角色与路径映射（传输路径 → 本地源路径），供传输完成后的可选校验使用
        receiverSide = false;
        transferToSource.clear();
        for (RemoteFile file : fileList) {
            if (!file.isDirectory()) {
                transferToSource.put(localDir.generateTransferPath(file.getPath(), remoteDir), file.getPath());
            }
        }
        //0. 断点续传握手：请求接收方返回检查点（传输路径 → 已确认完成的字节偏移）
        ctChannel.writeShort(ControllerIdentifiers.CHECKPOINT_REQUEST);
        //发送文件列表（传输路径），供接收方匹配本地检查点
        int dirFileCount = fileList.size();
        ctChannel.writeInt(dirFileCount);
        for (RemoteFile file : fileList) {
            ctChannel.writeUTF(localDir.generateTransferPath(file.getPath(), remoteDir));
            ctChannel.writeLong(file.getSize());
            ctChannel.writeLong(file.lastModified());
            ctChannel.writeBoolean(file.isDirectory());
        }
        Map<String, Long> remoteCheckpoints = new HashMap<>();
        int checkpointCount = ctChannel.readInt();
        for (int i = 0; i < checkpointCount; i++) {
            remoteCheckpoints.put(ctChannel.readUTF(), ctChannel.readLong());
        }
        //将接收方以传输路径为键的检查点，转换为以本地源路径为键
        Map<String, Long> checkpoints = new HashMap<>(remoteCheckpoints.size());
        for (RemoteFile file : fileList) {
            if (file.isDirectory()) {
                continue;
            }
            Long completedBytes = remoteCheckpoints.get(localDir.generateTransferPath(file.getPath(), remoteDir));
            if (completedBytes != null) {
                checkpoints.put(file.getPath(), completedBytes);
            }
        }

        //1. 计算总传输量并通知（用于总体进度显示）
        long totalBytes = 0;
        int totalFiles = 0;
        for (RemoteFile file : fileList) {
            if (!file.isDirectory()) {
                totalBytes += file.getSize();
                totalFiles++;
            }
        }
        callback.onTransferStarted(totalBytes, totalFiles);

        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size(), checkpoints);
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask);
        readThread.setName("FileRead");
        readThread.start();
        //另开一个线程读取传输流量信息，1秒一次
        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, totalBytes, readFileCall);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
        long startTime = System.currentTimeMillis();
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("UL_" + connection.iName);
            thread.start();
        }

        //其中一条通道断掉，可能控制器通道也一起跟着断了
        boolean complete;
        try {
            //等待客户端接收成功或者写入到硬盘时发生IO错误
            complete = ctChannel.readBoolean();
        } catch (IOException e) {
            speedMonitorThread.cancel();
            callback.onIncomplete();
            return false;
        }
        speedMonitorThread.cancel();

        if (!complete) {
            String errMsg = ctChannel.readUTF();
            callback.onWriteFileError(errMsg);
            readFileCall.shutdownByWriteError();
            return true;
        }

        for (FutureTask<Void> transferTask : transferTasks) {
            try {
                transferTask.get();
            } catch (ExecutionException | InterruptedException e) {
                callback.onIncomplete();
                return false;
            }
        }

        long totalUploadTraffic = 0;
        for (TransferConnection connection : connections) {
            totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
        }

        try {
            readFileTask.get();
            ctChannel.writeBoolean(true);
        } catch (ExecutionException | InterruptedException e) {
            Throwable cause = e.getCause();
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeBoolean(false);
            ctChannel.writeUTF(ex);
            callback.onReadFileError(ex);
            return true;
        }

        callback.onComplete(true,totalUploadTraffic, System.currentTimeMillis() - startTime);
        return true;
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        //0. 断点续传握手：应答发送方的检查点请求
        short req = ctChannel.readShort();
        if (req != ControllerIdentifiers.CHECKPOINT_REQUEST) {
            throw new IOException("protocol error: expected CHECKPOINT_REQUEST, got " + req);
        }
        int fileCount = ctChannel.readInt();
        List<RemoteFile> fileList = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) {
            fileList.add(new RemoteFile(
                    ctChannel.readUTF(),//name（传输路径的文件名，接收方不使用）
                    ctChannel.readUTF(),//path（传输路径）
                    ctChannel.readLong(),//lastModified
                    ctChannel.readLong(),//size
                    ctChannel.readBoolean()//isDirectory
            ));
        }
        //记录传输角色与落盘路径（校验时按传输路径直接读取本地文件）
        receiverSide = true;
        receivedTransferPaths.clear();
        for (RemoteFile file : fileList) {
            if (!file.isDirectory()) {
                receivedTransferPaths.add(file.getPath());
            }
        }
        //按文件列表匹配本地检查点（peerId + totalSize + lastModified 校验）
        Map<String, CheckpointEntry> entries = getCheckpointManager().loadCheckpoints(fileList, peerId);
        Map<String, Long> checkpoints = new HashMap<>(entries.size());
        long totalBytes = 0;
        int totalFiles = 0;
        for (RemoteFile file : fileList) {
            if (file.isDirectory()) {
                continue;
            }
            totalBytes += file.getSize();
            totalFiles++;
            CheckpointEntry entry = entries.get(file.getPath());
            //仅当目标文件在磁盘上依然有效（存在且长度 >= 检查点字节）时才启用续传；
            //无效（被删除/截断）则不带该检查点，发送方会全量重传
            if (entry != null && isCheckpointValid(file.getPath(), entry)) {
                checkpoints.put(file.getPath(), entry.completedBytes);
            }
        }
        //写回检查点响应
        ctChannel.writeInt(checkpoints.size());
        for (Map.Entry<String, Long> e : checkpoints.entrySet()) {
            ctChannel.writeUTF(e.getKey());
            ctChannel.writeLong(e.getValue());
        }
        callback.onTransferStarted(totalBytes, totalFiles);

        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size(), checkpoints);
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback, totalBytes, writeFileCall);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i, connection, writeFileCall, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("DL_" + connection.iName);
            thread.start();
        }
        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread thread = new Thread(writeFileTask);
        thread.setName("FileWrite");
        thread.start();
        try {
            writeFileTask.get();
        } catch (InterruptedException | ExecutionException e) {
            speedMonitorThread.cancel();
            Throwable cause = e.getCause();
            ctChannel.writeBoolean(false);
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeUTF(ex);
            callback.onWriteFileError(ex);
            return true;
        }

        for (FutureTask<Void> task : transferTasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                //此时没有连同控制器通道一起断掉，要通知对方，写线程没问题（对方的传输线程通道已出问题）
                ctChannel.writeBoolean(true);
                callback.onIncomplete();
                return false;
            }
        }
        speedMonitorThread.cancel();
        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            callback.onComplete(false,totalDownloadTraffic, System.currentTimeMillis() - startTime);
        } else {
            callback.onReadFileError(ctChannel.readUTF());
        }
        return true;
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, Map<String, Long> checkpoints);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount, Map<String, Long> checkpoints);

    /**
     * 平台层磁盘校验：检查点对应的目标文件是否依然有效（存在且长度不小于已完成字节数）。
     * <p>返回 false 时该检查点不会返回给发送方，从而实现全量重传，避免空洞文件。</p>
     *
     * @param transferPath 接收方传输路径
     * @param entry        检查点条目
     */
    protected abstract boolean isCheckpointValid(String transferPath, CheckpointEntry entry);

    /**
     * 平台层：计算本地文件的 MD5（小写十六进制）。
     * <p>用于传输完成后的可选文件校验。文件不存在或计算失败时返回 null。</p>
     *
     * @param localPath 本地文件路径
     */
    protected abstract String computeFileMd5(String localPath) throws Exception;

    /**
     * 根据本地角色，将传输路径解析为本机实际文件路径：
     * 接收方（receiverSide）落盘路径即传输路径；发送方通过 transferToSource 映射回源路径。
     */
    protected String resolveLocalPath(String transferPath) {
        return receiverSide ? transferPath : transferToSource.get(transferPath);
    }

    /**
     * 计算与传输路径对应的本机文件 MD5；本地缺失/计算失败返回 null。
     */
    protected String localMd5(String transferPath) {
        String localPath = resolveLocalPath(transferPath);
        if (localPath == null) {
            return null;
        }
        try {
            return computeFileMd5(localPath);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 传输完成后的可选文件校验：对最近一次传输的文件，
     * 请求对方计算其本地副本的 MD5，与本机副本对比，结果通过回调返回。
     * <p>本机计算失败/缺失计为校验失败；文件内容不一致也计为失败。</p>
     * <p>注意：仅服务端（HFXServer）可发起（客户端主循环负责响应本请求）。</p>
     */
    public void verifyFiles(TransferFileCallback callback) throws IOException {
        List<String> transferPaths = new ArrayList<>(receiverSide ? receivedTransferPaths : transferToSource.keySet());
        if (transferPaths.isEmpty()) {
            callback.onFileChecksumComplete(true, 0);
            return;
        }
        ctChannel.writeShort(ControllerIdentifiers.FILE_CHECKSUM_REQUEST);
        ctChannel.writeInt(transferPaths.size());
        for (String transferPath : transferPaths) {
            ctChannel.writeUTF(transferPath);
        }
        int count = ctChannel.readInt();
        Map<String, String> remoteMd5s = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            remoteMd5s.put(ctChannel.readUTF(), ctChannel.readUTF());
        }
        int mismatchCount = 0;
        for (String transferPath : transferPaths) {
            String local = localMd5(transferPath);
            String remote = remoteMd5s.get(transferPath);
            if (local == null || remote == null || !local.equals(remote)) {
                mismatchCount++;
            }
        }
        callback.onFileChecksumComplete(mismatchCount == 0, mismatchCount);
    }

    /**
     * 应答对方的文件校验请求：按请求中的传输路径清单计算本机 MD5 并回传。
     * <p>由客户端主循环（HFXClient.start）在处理到 FILE_CHECKSUM_REQUEST 时调用。</p>
     */
    protected void handleFileChecksumRequest() throws IOException {
        int count = ctChannel.readInt();
        ctChannel.writeInt(count);
        for (int i = 0; i < count; i++) {
            String transferPath = ctChannel.readUTF();
            String md5 = localMd5(transferPath);
            ctChannel.writeUTF(transferPath);
            ctChannel.writeUTF(md5 == null ? "" : md5);
        }
    }

}