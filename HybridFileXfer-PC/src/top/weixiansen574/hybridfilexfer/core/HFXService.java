package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    /**
     * 协议版本。304：校验应答期间发「空路径心跳」，单个大文件算 MD5 超过看门狗阈值时不再被误判卡死。
     * 303：握手互换稳定设备标识（取代 IP 作为检查点对端键），
     * 新增「传输完成后校验」的请求标志与结果回传（FILE_CHECKSUM_RESULT）。
     * 302：修正检查点握手清单的字段读写不对称（旧版每条多读 2 字节导致控制通道错位死锁），
     * 并将握手清单改为递归展开后的完整文件清单。与 302 及更早版本不兼容。
     */
    public static final int VERSION_CODE = 304;
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;
    /**
     * 对端标识：作为断点续传检查点的键，取握手互换的稳定设备标识（{@link #deviceId()}）。
     * <p>不能用 IP：同一台电脑换连接方式（ADB 的 127.0.0.1 / WLAN 的局域网 IP）、
     * 或手机 IP 因 DHCP 变更后会被当成新对端，已有检查点全部匹配不上，续传静默退化为全量重传。</p>
     */
    protected String peerId;
    /**
     * 本端是否请求「传输完成后执行 MD5 校验」。
     * <p>由客户端 App 设置（PC 端命令行 {@code -x/--checksum}），随握手告知服务端；
     * 因服务端没有控制通道读循环，校验统一由服务端发起、客户端应答（见 {@link #verifyFiles}）。</p>
     */
    public boolean requestChecksumOnTransfer = false;
    /** 对端是否请求本端在传输完成后执行 MD5 校验（握手时读取），传输结束时据此自动发起校验 */
    protected boolean peerRequestsChecksum = false;
    /** 校验进行中标志：服务端自动校验与用户手动点击可能并发，两条线程同时读写控制通道会串流 */
    private final AtomicBoolean verifying = new AtomicBoolean(false);
    /**
     * 校验交换的「无进展」超时：超过该时长没收到对端数据就判定卡死，关闭控制通道。
     * <p>值取得很宽（单文件 MD5 在慢速存储上可能耗时数分钟），宁可晚一点放弃也不能误杀正常校验。</p>
     */
    private static final long CHECKSUM_STALL_TIMEOUT_MS = 10 * 60 * 1000;
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

    /**
     * 本机稳定设备标识：首次生成后持久化，握手时提供给对端作为断点续传检查点的对端键。
     * <p>不能使用 IP 或连接地址：同一设备换连接方式（USB/WLAN）或 IP 变更后会被当成新对端。</p>
     */
    protected abstract String deviceId();

    /**
     * 对端是否请求了传输后校验。UI 据此在校验结果返回前锁住传输对话框，
     * 避免用户在自动校验进行中再发起一笔传输——那会让两条线程同时读写控制通道。
     */
    public boolean isPeerRequestsChecksum() {
        return peerRequestsChecksum;
    }

    protected boolean sendFiles(List<RemoteFile> fileList,Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        receiverSide = false;
        transferToSource.clear();
        //0. 递归展开待传清单：握手、进度总量、校验清单都必须覆盖子目录内容。
        //checkpoints 先以空 map 传入，握手拿到结果后再填充同一实例（读线程尚未启动）
        Map<String, Long> checkpoints = new HashMap<>();
        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size(), checkpoints);
        List<RemoteFile> expandedFiles = readFileCall.expandFileList();
        readFileCall.setExpandedFiles(expandedFiles);

        //1. 统一生成传输路径。清洗后可能撞车（如同目录下 a:b.txt 与 a_b.txt 都变成 a_b.txt），
        //按出现顺序加序号去重：传输路径同时是检查点主键、fileStates 键与校验清单键，
        //撞车会让两个文件共享一份落盘状态（水位线互相污染）→ 输出损坏。
        //目录也要映射：目录块的 path 同样来自这里
        Map<String, String> transferPaths = new HashMap<>(expandedFiles.size());
        Set<String> usedTransferPaths = new HashSet<>();
        for (RemoteFile file : expandedFiles) {
            transferPaths.put(file.getPath(), FileSanitizer.uniquePath(
                    localDir.generateTransferPath(file.getPath(), remoteDir), usedTransferPaths));
        }
        readFileCall.setTransferPaths(transferPaths);

        //仅文件参与握手：目录不需要检查点，也不计入进度总量
        List<RemoteFile> transferFiles = new ArrayList<>();
        for (RemoteFile file : expandedFiles) {
            if (!file.isDirectory()) {
                transferFiles.add(file);
            }
        }
        //记录传输角色与路径映射（传输路径 → 本地源路径），供传输完成后的可选校验使用
        for (RemoteFile file : transferFiles) {
            transferToSource.put(transferPaths.get(file.getPath()), file.getPath());
        }

        //2. 断点续传握手：请求接收方返回检查点（传输路径 → 已确认完成的字节偏移）。
        //写入字段顺序必须与 receiveFiles 的读取顺序严格一致
        ctChannel.writeShort(ControllerIdentifiers.CHECKPOINT_REQUEST);
        ctChannel.writeInt(transferFiles.size());
        for (RemoteFile file : transferFiles) {
            ctChannel.writeUTF(transferPaths.get(file.getPath()));
            ctChannel.writeLong(file.getSize());
            ctChannel.writeLong(file.lastModified());
        }
        Map<String, Long> remoteCheckpoints = new HashMap<>();
        int checkpointCount = ctChannel.readInt();
        for (int i = 0; i < checkpointCount; i++) {
            remoteCheckpoints.put(ctChannel.readUTF(), ctChannel.readLong());
        }
        //将接收方以传输路径为键的检查点，转换为以本地源路径为键
        for (RemoteFile file : transferFiles) {
            Long completedBytes = remoteCheckpoints.get(transferPaths.get(file.getPath()));
            if (completedBytes != null) {
                checkpoints.put(file.getPath(), completedBytes);
            }
        }

        //3. 计算总传输量并通知（用于总体进度显示）
        long totalBytes = 0;
        for (RemoteFile file : transferFiles) {
            totalBytes += file.getSize();
        }
        callback.onTransferStarted(totalBytes, transferFiles.size());
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
        verifyIfRequested(callback);
        return true;
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        //0. 断点续传握手：应答发送方的检查点请求
        short req = ctChannel.readShort();
        if (req != ControllerIdentifiers.CHECKPOINT_REQUEST) {
            throw new IOException("protocol error: expected CHECKPOINT_REQUEST, got " + req);
        }
        //清单只含文件（不含目录），字段顺序与 sendFiles 的写入顺序严格一致：
        //transferPath(UTF) + size(long) + lastModified(long)
        int fileCount = ctChannel.readInt();
        List<RemoteFile> fileList = new ArrayList<>(fileCount);
        for (int i = 0; i < fileCount; i++) {
            String transferPath = ctChannel.readUTF();
            long size = ctChannel.readLong();
            long lastModified = ctChannel.readLong();
            //name 字段接收方不使用，用传输路径占位
            fileList.add(new RemoteFile(transferPath, transferPath, lastModified, size, false));
        }
        //记录传输角色与落盘路径（校验时按传输路径直接读取本地文件）
        receiverSide = true;
        receivedTransferPaths.clear();
        for (RemoteFile file : fileList) {
            receivedTransferPaths.add(file.getPath());
        }
        //按文件列表匹配本地检查点（peerId + totalSize + lastModified 校验）
        Map<String, CheckpointEntry> entries = getCheckpointManager().loadCheckpoints(fileList, peerId);
        Map<String, Long> checkpoints = new HashMap<>(entries.size());
        long totalBytes = 0;
        for (RemoteFile file : fileList) {
            totalBytes += file.getSize();
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
        callback.onTransferStarted(totalBytes, fileList.size());

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
            verifyIfRequested(callback);
        } else {
            callback.onReadFileError(ctChannel.readUTF());
        }
        return true;
    }

    /**
     * 对端在握手时请求了传输后校验则自动发起。
     * <p>此时对端（客户端）已回到或即将回到其控制通道读循环，能应答本端的校验请求。</p>
     */
    private void verifyIfRequested(TransferFileCallback callback) throws IOException {
        if (peerRequestsChecksum) {
            verifyFiles(callback);
        }
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
    protected abstract String computeFileMd5(String localPath, Runnable onProgress) throws Exception;

    /**
     * 根据本地角色，将传输路径解析为本机实际文件路径：
     * 接收方（receiverSide）落盘路径即传输路径；发送方通过 transferToSource 映射回源路径。
     */
    protected String resolveLocalPath(String transferPath) {
        return receiverSide ? transferPath : transferToSource.get(transferPath);
    }

    /**
     * 计算与传输路径对应的本机文件 MD5；本地缺失/计算失败返回 null。
     *
     * @param onProgress 计算过程中的报活回调（可为 null），见 {@link #handleFileChecksumRequest}
     */
    protected String localMd5(String transferPath, Runnable onProgress) {
        String localPath = resolveLocalPath(transferPath);
        if (localPath == null) {
            return null;
        }
        try {
            return computeFileMd5(localPath, onProgress);
        } catch (Exception e) {
            return null;
        }
    }

    /** 不需要报活的版本（本端自己算 MD5 时不占用控制通道） */
    protected String localMd5(String transferPath) {
        return localMd5(transferPath, null);
    }

    /**
     * 传输完成后的可选文件校验：对最近一次传输的文件，
     * 请求对方计算其本地副本的 MD5，与本机副本对比，结果通过回调返回。
     * <p>本机计算失败/缺失计为校验失败；文件内容不一致也计为失败。</p>
     * <p>角色约定：服务端（HFXServer）没有控制通道读循环，故**由服务端发起、客户端在控制循环中应答**；
     * 客户端想校验时通过握手告知（{@link #requestChecksumOnTransfer}），
     * 服务端在传输结束时自动调用本方法（见 {@link #verifyIfRequested}），
     * 并把结论回传（{@link ControllerIdentifiers#FILE_CHECKSUM_RESULT}）。</p>
     * <p>同一时刻只允许一次校验：并发调用返回 false（调用方应据此恢复 UI 状态，
     * 否则按钮会一直停在「校验中…」）。</p>
     *
     * @return true 表示本次确实执行了校验
     */
    public boolean verifyFiles(TransferFileCallback callback) throws IOException {
        //重复发起（服务端自动校验与用户点击并发）直接返回，避免两条线程同时读写控制通道
        if (!verifying.compareAndSet(false, true)) {
            return false;
        }
        try {
            verifyFilesInternal(callback);
            return true;
        } finally {
            verifying.set(false);
        }
    }

    private void verifyFilesInternal(TransferFileCallback callback) throws IOException {
        List<String> transferPaths = new ArrayList<>(receiverSide ? receivedTransferPaths : transferToSource.keySet());
        if (transferPaths.isEmpty()) {
            //清单为空也要把结果帧发出去：请求校验的一方（PC 的 -x）在控制循环里等这条帧，
            //不回它就永远看不到校验结论（传输已成功，但结果栏一直空着）
            //注：这种情况下对端会显示「校验通过」——本次没有文件可校验，属于空洞地成立，
            //真要区分“没校验”与“校验过且一致”需要再动一次协议，暂不做。
            if (peerRequestsChecksum) {
                ctChannel.writeShort(ControllerIdentifiers.FILE_CHECKSUM_RESULT);
                ctChannel.writeInt(0);
            }
            callback.onFileChecksumComplete(true, new ArrayList<>());
            return;
        }
        ctChannel.writeShort(ControllerIdentifiers.FILE_CHECKSUM_REQUEST);
        ctChannel.writeInt(transferPaths.size());
        for (String transferPath : transferPaths) {
            ctChannel.writeUTF(transferPath);
        }
        Map<String, String> remoteMd5s = new HashMap<>();
        //读超时看门狗：对端卡死时不让本线程永久阻塞（SO_TIMEOUT 对 NIO 阻塞读无效，只能关通道）
        ReadWatchdog watchdog = new ReadWatchdog(ctChannel, CHECKSUM_STALL_TIMEOUT_MS);
        try {
            int count = ctChannel.readInt();
            watchdog.touch();
            int received = 0;
            //对端每算完一块会发一个空路径心跳（见 handleFileChecksumRequest），跳过它，
            //否则单个大文件算 MD5 超过阈值时看门狗会误判对端卡死
            while (received < count) {
                String path = ctChannel.readUTF();
                watchdog.touch();
                if (path.isEmpty()) {
                    continue;
                }
                String md5 = ctChannel.readUTF();
                watchdog.touch();
                remoteMd5s.put(path, md5);
                received++;
            }
        } finally {
            //本地 MD5 计算不经过控制通道，无需继续看守
            watchdog.cancel();
        }
        List<String> mismatchFiles = new ArrayList<>();
        for (String transferPath : transferPaths) {
            String local = localMd5(transferPath);
            String remote = remoteMd5s.get(transferPath);
            if (local == null || remote == null || !local.equals(remote)) {
                mismatchFiles.add(transferPath);
            }
        }
        if (peerRequestsChecksum) {
            //回传结果（失败文件名清单）：请求校验的一侧（客户端）会在其控制循环中读取并展示
            ctChannel.writeShort(ControllerIdentifiers.FILE_CHECKSUM_RESULT);
            ctChannel.writeInt(mismatchFiles.size());
            for (String path : mismatchFiles) {
                ctChannel.writeUTF(path);
            }
        }
        callback.onFileChecksumComplete(mismatchFiles.isEmpty(), mismatchFiles);
    }

    /**
     * 读取校验发起方回传的结果（{@link ControllerIdentifiers#FILE_CHECKSUM_RESULT}）。
     * <p>仅在本端握手时请求过校验（{@link #requestChecksumOnTransfer}）时才会收到。</p>
     */
    protected void handleFileChecksumResult(TransferFileCallback callback) throws IOException {
        int count = ctChannel.readInt();
        List<String> mismatchFiles = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            mismatchFiles.add(ctChannel.readUTF());
        }
        callback.onFileChecksumComplete(mismatchFiles.isEmpty(), mismatchFiles);
    }

    /**
     * 应答对方的文件校验请求：按请求中的传输路径清单计算本机 MD5 并回传。
     * <p>由客户端主循环（HFXClient.start）在处理到 FILE_CHECKSUM_REQUEST 时调用。</p>
     */
    protected void handleFileChecksumRequest() throws IOException {
        ReadWatchdog watchdog = new ReadWatchdog(ctChannel, CHECKSUM_STALL_TIMEOUT_MS);
        try {
            int count = ctChannel.readInt();
            watchdog.touch();
            ctChannel.writeInt(count);
            for (int i = 0; i < count; i++) {
                String transferPath = ctChannel.readUTF();
                watchdog.touch();
                //算 MD5 期间持续报活：
                //  - 发心跳帧：让**对端**的看门狗知道本端还在算（单个大文件可能超过超时阈值）；
                //  - touch 本端看门狗：心跳只写帧，不 touch 自己的话，本端看门狗会在这段本地计算里
                //    判超时并关掉自己的控制通道，症状与不修时一模一样
                String md5 = localMd5(transferPath, () -> {
                    watchdog.touch();
                    sendChecksumHeartbeat();
                });
                ctChannel.writeUTF(transferPath);
                ctChannel.writeUTF(md5 == null ? "" : md5);
            }
        } finally {
            watchdog.cancel();
        }
    }

    /**
     * 校验心跳：空路径帧，只用来告诉对端「我还在算」。
     * <p>协议 304 起；发起方在读取结果时跳过空路径（见 {@link #verifyFilesInternal}）。</p>
     */
    private void sendChecksumHeartbeat() {
        try {
            ctChannel.writeUTF("");
        } catch (IOException ignored) {
            //对端已关通道：忽略。后续的正式写入会抛异常并结束本次交换
        }
    }

}