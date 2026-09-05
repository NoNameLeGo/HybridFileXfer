package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;

public abstract class WriteFileCall implements Callable<Void>, ProgressSource {
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final boolean[] channelFinished;
    private final ArrayList<LinkedList<FileBlock>> dequeArray;
    /** 断点续传：传输路径 → 已完成块数 */
    private final Map<String, Integer> checkpoints;
    private final CheckpointManager checkpointManager;
    private final String peerId;
    private final AtomicLong completedBytes = new AtomicLong(0);
    private boolean canceled = false;

    public WriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount,
                         Map<String, Integer> checkpoints, CheckpointManager checkpointManager, String peerId) {
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

    @Override
    public Void call() throws Exception {
        try {
            FileBlock block = takeBlock();
            FileBlock lastBlock = null;
            /*File lastFile = null;
            RandomAccessFile lastRaf = null;*/
            FileChannel lastChannel = null;
            long cursor = 0;
            //当前文件的断点续传跳过块数（在打开新文件时确定）
            int skipBlocks = 0;

            while (block != null) {
                if (block.isDirectory()) {
                    //File file = new File(block.path);
                    String file = block.path;
                    tryMkdirs(file);
                    setLastModified(file, block.lastModified);
                    block = takeBlock();
                    continue;
                }
                //创建文件的父目录，如果不存在，保证后续文件能够创建
                createParentDirIfNotExists(block.path);
                //RandomAccessFile raf;
                FileChannel channel;
                //如果上个文件与当前
                if (lastBlock == null || !lastBlock.path.equals(block.path)) {
                    if (lastChannel != null) {
                        closeFile();
                        //上一个文件已完整接收（排序取出保证其所有块均到达并写盘），清除其检查点
                        checkpointManager.clearCheckpoint(lastBlock.path, peerId);
                        setLastModified(lastBlock.path, lastBlock.lastModified);
                    }
                    /*raf = new RandomAccessFile(file, "rw");
                    raf.setLength(block.totalSize);
                    channel = raf.getChannel();*/
                    channel = createAndOpenFile(block.path, block.totalSize);
                    //断点续传：跳过已有数据的块
                    skipBlocks = checkpoints.getOrDefault(block.path, 0);
                    long skipBytes = (long) skipBlocks * FileBlock.BLOCK_SIZE;
                    if (skipBlocks > 0 && channel.size() < skipBytes) {
                        //握手后目标文件被删除/截断：skip 状态已失效，抛错终止本次传输，
                        //下次握手时磁盘校验会判定检查点无效，从而全量重传，避免写出空洞文件
                        throw new IOException("checkpoint stale: target file missing or truncated: " + block.path);
                    }
                    if (skipBlocks > 0) {
                        //检查点显示的已完成数据会计入进度
                        completedBytes.addAndGet(Math.min(skipBytes, block.totalSize));
                    }
                    cursor = 0;
                } else {
                    //raf = lastRaf;
                    channel = lastChannel;
                }
                //如果上个指针与当前指针不不一致就进行seek操作
                if (cursor != block.getStartPosition()) {
                    cursor = block.getStartPosition();
                    channel.position(cursor);
                }
                //断点续传：跳过已被跳过的块（不写入磁盘；进度已在打开文件时计入）
                if (block.index < skipBlocks) {
                    //回收缓冲区块
                    buffers.add(block.data);
                    lastBlock = block;
                    lastChannel = channel;
                    block = takeBlock();
                    continue;
                }
                 /*   logSeek(block);
                } else {
                    logBlock(block);
                }*/

                ByteBuffer data = block.data;
                data.flip();
                channel.write(data);
                cursor += data.position();
                completedBytes.addAndGet(data.position());
                //保存检查点：记录当前文件已完成块数
                checkpointManager.saveCheckpoint(block.path, block.totalSize,
                        block.lastModified, block.index + 1, peerId);
                //回收缓冲区块
                buffers.add(block.data);
                lastBlock = block;
                /*lastFile = file;
                lastRaf = raf;*/
                lastChannel = channel;
                block = takeBlock();
            }
            if (lastBlock != null) {
                closeFile();
                //只有在传输未被取消、且文件最后一块已写入时才清除检查点；
                //传输中断/取消时保留检查点，以便下次续传
                if (!canceled && isFileComplete(lastBlock)) {
                    checkpointManager.clearCheckpoint(lastBlock.path, peerId);
                }
                setLastModified(lastBlock.path, lastBlock.lastModified);
            }
        } catch (IOException e){
            cancel();
            throw e;
        }
        return null;
    }

    private void logSeek(FileBlock block) {
        System.out.printf("seek: %d %s %d %d %d%n",
                block.getStartPosition(), block.path, block.totalSize, block.index, block.getLength());
    }

    /** 判断该文件是否已完整写入（最后一块已落盘） */
    private boolean isFileComplete(FileBlock lastBlock) {
        long totalBlocks = (lastBlock.totalSize + FileBlock.BLOCK_SIZE - 1) / FileBlock.BLOCK_SIZE;
        return lastBlock.index == totalBlocks - 1;
    }

    private void logBlock(FileBlock block) {
        System.out.printf("%s %d %d %d%n",
                block.path, block.totalSize, block.index, block.getLength());
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
        //System.out.println("put:"+block.index+" "+tIndex);
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