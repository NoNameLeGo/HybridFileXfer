package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

public abstract class WriteFileCall implements Callable<Void>, ProgressSource {
    /**
     * 同一文件的检查点最小持久化间隔（毫秒）。
     * <p>高速传输时每块一次存储写入开销显著（PC 端为 JSON 全量重写、Android 端为 SQLite 同步写），
     * 故按时间节流。节流丢失的进度最多为该间隔内传输的字节数，
     * 续传时表现为多重传一小段——方向是安全的，不会产生空洞。</p>
     */
    private static final long CHECKPOINT_SAVE_INTERVAL_MS = 1000;

    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final boolean[] channelFinished;
    private final ArrayList<LinkedList<FileBlock>> dequeArray;
    /** 断点续传：传输路径 → 已确认完成的字节偏移（与块大小解耦） */
    private final Map<String, Long> checkpoints;
    private final CheckpointManager checkpointManager;
    private final String peerId;
    private final AtomicLong completedBytes = new AtomicLong(0);
    /** 传输路径 → 该文件的落盘状态。多通道乱序到达时同一文件会被反复写入，状态必须按文件保存 */
    private final Map<String, FileState> fileStates = new HashMap<>();
    private boolean canceled = false;

    public WriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount,
                         Map<String, Long> checkpoints, CheckpointManager checkpointManager, String peerId) {
        this.buffers = buffers;
        this.checkpoints = checkpoints;
        this.checkpointManager = checkpointManager;
        this.peerId = peerId;
        dequeArray = new ArrayList<>(dequeCount);
        channelFinished = new boolean[dequeCount];  // 初始化通道结束状态
        for (int i = 0; i < dequeCount; i++) {
            dequeArray.add(new LinkedList<>());
        }
    }

    @Override
    public long getCompletedBytes() {
        return completedBytes.get();
    }

    /**
     * 单个文件的落盘状态。
     * <p>核心是 {@link #contiguousBytes}（连续已完成水位线）：只有从文件起始开始
     * 不间断落盘的字节才计入。多通道并行时块会乱序到达（快通道的后续块可能先于
     * 慢通道的前序块落盘），若直接以"最后落盘块的末尾偏移"作为检查点，
     * 续传时会跳过尚未到达的前序块，在文件中留下永久空洞。</p>
     */
    private static final class FileState {
        final long totalSize;
        final long lastModified;
        /** 连续已完成字节水位线：[0, contiguousBytes) 区间的数据确定已落盘 */
        long contiguousBytes;
        /** 已落盘但尚未被水位线覆盖的超前块：块起始偏移 → 块末尾偏移 */
        final TreeMap<Long, Long> aheadBlocks = new TreeMap<>();
        /** 续传跳过的字节数（对齐到块边界），这些数据由上次传输写入 */
        long skipBytes;
        /** 是否已完成首次打开（跳过量与进度只在首次打开时结算一次） */
        boolean opened;
        /** 检查点最近一次持久化时间，用于节流 */
        long lastSaveTime;
        /** 存在被节流跳过的检查点更新，需在结束前补写 */
        boolean pendingSave;
        /** 该文件已完整落盘且检查点已清除 */
        boolean checkpointCleared;

        FileState(long totalSize, long lastModified) {
            this.totalSize = totalSize;
            this.lastModified = lastModified;
        }

        boolean isComplete() {
            return contiguousBytes >= totalSize;
        }
    }

    @Override
    public Void call() throws Exception {
        //当前已打开的文件路径与句柄（乱序到达时可能被切换/重开）
        String openPath = null;
        FileChannel channel = null;
        //当前句柄的写指针；-1 表示位置未知，下次写入前强制 seek
        long cursor = -1;

        try {
            FileBlock block = takeBlock();

            while (block != null) {
                if (block.isDirectory()) {
                    tryMkdirs(block.path);
                    setLastModified(block.path, block.lastModified);
                    block = takeBlock();
                    continue;
                }
                //创建文件的父目录，如果不存在，保证后续文件能够创建
                createParentDirIfNotExists(block.path);
                FileState state = stateOf(block);

                if (!block.path.equals(openPath)) {
                    if (channel != null) {
                        closeFile();
                        setLastModified(openPath, fileStates.get(openPath).lastModified);
                    }
                    channel = createAndOpenFile(block.path, block.totalSize);
                    //只缩不扩地清掉旧文件尾巴：同名文件被改小后重传时，旧实现靠 setLength 预分配自带
                    //截断，去掉预分配后必须显式截断，否则比本次传输更长的旧内容残留在文件尾部。
                    //续传时文件长度小于 totalSize，truncate 不改动文件，也不会像 setLength 那样撑出空洞
                    channel.truncate(block.totalSize);
                    openPath = block.path;
                    cursor = -1;
                    if (!state.opened) {
                        state.opened = true;
                        initSkip(block, state, channel);
                    }
                }

                //断点续传：跳过已被确认的块（不写入磁盘；进度已在首次打开时计入）
                if (block.getStartPosition() < state.skipBytes) {
                    buffers.add(block.data);
                    block = takeBlock();
                    continue;
                }

                //如果上个指针与当前指针不一致就进行 seek 操作
                if (cursor != block.getStartPosition()) {
                    cursor = block.getStartPosition();
                    channel.position(cursor);
                }

                ByteBuffer data = block.data;
                data.flip();
                int length = data.limit();
                //FileChannel.write 不保证一次写完，必须循环写尽，
                //否则剩余字节随缓冲区回收而永久丢失，在文件中留下空洞
                while (data.hasRemaining()) {
                    if (channel.write(data) <= 0) {
                        throw new IOException("write stalled at " + cursor + ": " + block.path);
                    }
                }
                cursor += length;
                completedBytes.addAndGet(length);
                //回收缓冲区块
                buffers.add(data);

                advanceWatermark(state, block.getStartPosition(), length);
                saveOrClearCheckpoint(block.path, state);

                block = takeBlock();
            }
        } catch (IOException e) {
            cancel();
            throw e;
        } finally {
            //无论正常结束还是 IO 异常，都必须关闭当前句柄并补写被节流跳过的检查点：
            //否则残留的 fd（Android 为跨进程 PFD）泄漏，且最后一段水位线丢失退化为多重传
            closeCurrentFile(channel, openPath);
            flushCheckpoints();
        }
        return null;
    }

    /** 关闭当前文件句柄并回写修改时间；关闭失败不能掩盖原始异常 */
    private void closeCurrentFile(FileChannel channel, String openPath) {
        if (channel == null || openPath == null) {
            return;
        }
        try {
            closeFile();
            FileState state = fileStates.get(openPath);
            if (state != null) {
                setLastModified(openPath, state.lastModified);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private FileState stateOf(FileBlock block) {
        FileState state = fileStates.get(block.path);
        if (state == null) {
            state = new FileState(block.totalSize, block.lastModified);
            fileStates.put(block.path, state);
        }
        return state;
    }

    /**
     * 首次打开文件时结算断点续传跳过量。
     * <p>持久化值可能来自不同的块大小配置，与发送端一致地对齐到当前块大小边界。
     * 每个文件只结算一次，避免乱序重开时重复累加进度。</p>
     */
    private void initSkip(FileBlock block, FileState state, FileChannel channel) throws IOException {
        long skipBytes = (checkpoints.getOrDefault(block.path, 0L) / FileBlock.BLOCK_SIZE) * FileBlock.BLOCK_SIZE;
        if (skipBytes <= 0) {
            return;
        }
        if (channel.size() < skipBytes) {
            //握手后目标文件被删除/截断：skip 状态已失效，抛错终止本次传输，
            //下次握手时磁盘校验会判定检查点无效，从而全量重传，避免写出空洞文件
            throw new IOException("checkpoint stale: target file missing or truncated: " + block.path);
        }
        state.skipBytes = skipBytes;
        //跳过的数据即已完成的连续前缀，作为水位线起点
        state.contiguousBytes = skipBytes;
        //检查点显示的已完成数据会计入进度
        completedBytes.addAndGet(Math.min(skipBytes, block.totalSize));
    }

    /**
     * 推进连续水位线：块紧接水位线时前移，并连带吸收此前记录的超前块；
     * 否则仅登记为超前块，等待前序块到达后再吸收。
     */
    private void advanceWatermark(FileState state, long startPosition, int length) {
        long end = startPosition + length;
        if (startPosition == state.contiguousBytes) {
            state.contiguousBytes = end;
            Long next;
            while ((next = state.aheadBlocks.remove(state.contiguousBytes)) != null) {
                state.contiguousBytes = next;
            }
        } else if (startPosition > state.contiguousBytes) {
            state.aheadBlocks.put(startPosition, end);
        }
        //startPosition < contiguousBytes 为重复块，正常流程不会出现，忽略
    }

    /**
     * 文件完整落盘则立即清除检查点；否则按节流间隔持久化当前水位线。
     * <p>清除时机取决于水位线而非文件切换：乱序传输下"切到下一个文件"
     * 并不意味着上一个文件已收全。</p>
     */
    private void saveOrClearCheckpoint(String path, FileState state) {
        if (state.isComplete()) {
            if (!state.checkpointCleared) {
                checkpointManager.clearCheckpoint(path, peerId);
                state.checkpointCleared = true;
                state.pendingSave = false;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - state.lastSaveTime < CHECKPOINT_SAVE_INTERVAL_MS) {
            state.pendingSave = true;
            return;
        }
        state.lastSaveTime = now;
        state.pendingSave = false;
        checkpointManager.saveCheckpoint(path, state.totalSize, state.lastModified,
                state.contiguousBytes, peerId);
    }

    private void flushCheckpoints() {
        for (Map.Entry<String, FileState> entry : fileStates.entrySet()) {
            FileState state = entry.getValue();
            if (state.checkpointCleared) {
                continue;
            }
            if (state.isComplete()) {
                checkpointManager.clearCheckpoint(entry.getKey(), peerId);
                state.checkpointCleared = true;
            } else if (state.pendingSave) {
                checkpointManager.saveCheckpoint(entry.getKey(), state.totalSize,
                        state.lastModified, state.contiguousBytes, peerId);
                state.pendingSave = false;
            }
        }
    }

    public ByteBuffer getBuffer() throws InterruptedException {
        return buffers.take();
    }

    // 新增方法：标记通道结束
    public synchronized void finishChannel(int tIndex) {
        channelFinished[tIndex] = true;
        notify();  // 唤醒可能阻塞的写线程
    }

    public synchronized void cancel(){
        canceled = true;
        //回收未写入硬盘的块的ByteBuffer
        for (LinkedList<FileBlock> deque : dequeArray) {
            for (FileBlock fileBlock : deque) {
                if (fileBlock.data != null){
                    buffers.add(fileBlock.data);
                }
            }
        }
        notify();
    }

    // 修改后的putBlock（保持原有逻辑）
    public synchronized void putBlock(FileBlock block, int tIndex) {
        dequeArray.get(tIndex).add(block);
        notify();  // 唤醒可能阻塞的写线程
    }


    // 重构后的takeBlock（实现阻塞等待）
    private synchronized FileBlock takeBlock() throws InterruptedException {
        while (true) {
            FileBlock block = tryTakeBlockInternal();

            if (block != null) return block;

            //检查终止条件：所有通道结束 + 所有队列为空
            if (canceled || (allChannelsFinished() && allQueuesEmpty())) {
                return null;
            }

            wait();  // 阻塞等待直到被唤醒
        }
    }

    public synchronized FileBlock tryTakeBlockInternal() {
        FileBlock minHead = null;
        int mdqIndex = -1;

        for (int i = 0; i < dequeArray.size(); i++) {
            LinkedList<FileBlock> deque = dequeArray.get(i);
            if (!deque.isEmpty()) {
                FileBlock head = deque.getFirst();
                if (minHead == null || head.compareTo(minHead) < 0) {
                    minHead = head;
                    mdqIndex = i;
                }
            }
        }
        if (minHead != null) {
            dequeArray.get(mdqIndex).removeFirst();
        }
        return minHead;
    }

    private boolean allChannelsFinished() {
        for (boolean finished : channelFinished) {
            if (!finished) return false;
        }
        return true;
    }

    // 辅助方法：检查所有队列是否为空
    private boolean allQueuesEmpty() {
        for (LinkedList<FileBlock> deque : dequeArray) {
            if (!deque.isEmpty()) return false;
        }
        return true;
    }

    private void setLastModified(String file, long time) throws Exception {
        if (!setFileLastModified(file,time)) {
            System.out.println("Warning! file cannot set last modified:" + file);
        }
    }

    protected abstract void createParentDirIfNotExists(String path) throws Exception;
    protected abstract void tryMkdirs(String path) throws Exception;
    protected abstract FileChannel createAndOpenFile(String path,long length) throws Exception;
    protected abstract void closeFile() throws Exception;
    protected abstract boolean setFileLastModified(String path,long time) throws Exception;
}
