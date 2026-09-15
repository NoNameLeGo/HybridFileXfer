package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public abstract class ReadFileCall implements Callable<Void>, ProgressSource {
    public static final FileBlock END_POINT = new FileBlock(true, -1, "END_POINT", 0, 0, -1, null);
    public static final FileBlock INTERRUPT = new FileBlock(true, -1, "INTERRUPT", 0, 0, -1, null);
    public static final FileBlock READ_ERROR = new FileBlock(true, -1, "READ_ERROR", 0, 0, -1, null);
    public static final FileBlock WRITE_ERROR = new FileBlock(true, -1, "WRITE_ERROR", 0, 0, -1, null);

    private final LinkedBlockingDeque<FileBlock> deque = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final List<RemoteFile> files;
    private final Directory localDir;
    private final Directory remoteDir;
    private final int operateThreadCount;
    /** 断点续传：本地源路径 → 已确认完成的字节偏移 */
    private final Map<String, Long> checkpoints;
    private final AtomicLong completedBytes = new AtomicLong(0);
    private int fileIndex = -1;
    /** 递归展开后的完整待传清单（含子目录内容）。由 HFXService 在握手前设置，避免重复遍历文件系统 */
    private List<RemoteFile> expandedFiles;
    /**
     * 本地源路径 → 传输路径。由 HFXService 在握手前设置：
     * 握手清单、进度总量、校验清单与文件块都再用同一份映射，
     * 避免两处各自调用 generateTransferPath 时不一致（已清洗撞车的名字会在那里加序号去重）。
     */
    private Map<String, String> transferPaths;

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount, Map<String, Long> checkpoints) {
        this.buffers = buffers;
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.operateThreadCount = operateThreadCount;
        this.checkpoints = checkpoints;
    }

    @Override
    public long getCompletedBytes() {
        return completedBytes.get();
    }

    /**
     * 设置递归展开后的完整待传清单。
     * <p>由 {@link HFXService#sendFiles} 在检查点握手前调用：握手清单、总字节数、
     * 校验清单都必须覆盖子目录内容，否则文件夹内的文件无法续传、不计入进度、不被校验。</p>
     */
    public void setExpandedFiles(List<RemoteFile> expandedFiles) {
        this.expandedFiles = expandedFiles;
    }

    /** 设置源路径 → 传输路径映射（由 {@link HFXService#sendFiles} 在握手前调用） */
    public void setTransferPaths(Map<String, String> transferPaths) {
        this.transferPaths = transferPaths;
    }

    /** 该文件对应的传输路径：优先用握手时确定的映射，未命中时退化为现算 */
    private String transferPath(RemoteFile file) {
        String mapped = transferPaths == null ? null : transferPaths.get(file.getPath());
        return mapped != null ? mapped : localDir.generateTransferPath(file.getPath(), remoteDir);
    }

    /**
     * 递归展开待传清单：目录项在前，其子项紧随其后（与实际读取顺序、fileIndex 分配顺序一致）。
     * <p>沿用原有的容错语义：不存在的顶层项跳过，无法列出的目录跳过其子树，不抛出异常。</p>
     */
    public List<RemoteFile> expandFileList() {
        List<RemoteFile> expanded = new ArrayList<>();
        for (RemoteFile file : files) {
            try {
                if (!fileExists(file.getPath())) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }
            collect(file, expanded);
        }
        return expanded;
    }

    private void collect(RemoteFile file, List<RemoteFile> out) {
        out.add(file);
        if (!file.isDirectory()) {
            return;
        }
        List<RemoteFile> children;
        try {
            children = listFiles(file.getPath());
        } catch (Exception e) {
            return;
        }
        if (children != null) {
            for (RemoteFile child : children) {
                collect(child, out); // 递归遍历子文件夹
            }
        }
    }

    @Override
    public Void call() throws Exception {
        try {
            //清单已在握手阶段展开则直接复用，避免二次遍历文件系统
            List<RemoteFile> targets = expandedFiles != null ? expandedFiles : expandFileList();
            for (RemoteFile file : targets) {
                readToDeque(file);
            }
            for (int i = 0; i < operateThreadCount; i++) {
                deque.add(END_POINT);
            }
        } catch (Exception e) {
            //当发生读取错误时
            for (int i = 0; i < operateThreadCount; i++) {
                deque.add(READ_ERROR);
            }
            throw e;
        }
        return null;
    }

    private void readToDeque(RemoteFile file) throws Exception {
        fileIndex++;
        if (file.isDirectory()) {
            deque.add(new FileBlock(false,
                    fileIndex, transferPath(file),
                    file.lastModified(), 0, 0, null));
            return;
        }
        //RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
        FileChannel channel = openFile(file.getPath());
        //异常路径（读抛错、buffers.take() 被中断）也必须关文件，否则 Android 侧会泄漏
        //ParcelFileDescriptor（跨进程 fd），每次失败漏一个
        try {
            long length = channel.size();
            long lastModified = file.lastModified();
            //断点续传：跳过已确认完成的字节（偏移为持久化单位，与块大小无关）。
            //持久化值可能来自不同的块大小配置，与接收端一致地对齐到当前块大小边界，
            //对齐后的整块从该边界重传，不多不漏
            long skipBytes = checkpoints.getOrDefault(file.getPath(), 0L);
            long alignedSkip = (skipBytes / FileBlock.BLOCK_SIZE) * FileBlock.BLOCK_SIZE;
            long remaining = length;
            if (alignedSkip > 0) {
                if (alignedSkip >= length) {
                    //整个文件已传完（或文件被改动变小），无需再发送任何块，接收方保留现有文件。
                    //进度按文件大小计入，否则本端进度永远到不了 100%
                    completedBytes.addAndGet(length);
                    return;
                }
                channel.position(alignedSkip);
                remaining -= alignedSkip;
                completedBytes.addAndGet(alignedSkip);
            }
            if (length == 0) {
                ByteBuffer buffer = buffers.take();
                buffer.clear();
                buffer.limit(0);
                deque.add(new FileBlock(true,
                        fileIndex, transferPath(file),
                        lastModified, length, 0, buffer));
                return;
            }
            //起始块索引：从对齐后的字节位置对应的块开始
            int i = (int) (alignedSkip / FileBlock.BLOCK_SIZE);
            while (remaining > 0) {
                int blkSize = (int) Math.min(remaining, FileBlock.BLOCK_SIZE);
                ByteBuffer buffer = buffers.take();
                buffer.clear();
                buffer.limit(blkSize);
                int read;
                while (buffer.hasRemaining()) {
                    read = channel.read(buffer);
                    if (read < 0) {
                        //源文件在 size() 之后被截断：必须抛错，否则 hasRemaining() 恒为 true 导致死循环
                        throw new IOException("source file shrank while reading: " + file.getPath());
                    }
                }
                deque.add(new FileBlock(true,
                        fileIndex, transferPath(file),
                        lastModified, length, i, buffer));
                completedBytes.addAndGet(blkSize);
                remaining -= blkSize;
                i++;
            }
        } finally {
            try {
                closeFile();
            } catch (Exception e) {
                //关闭失败不能掩盖原始异常（与 WriteFileCall.closeCurrentFile 一致）
                e.printStackTrace();
            }
        }
    }

    public void recycleBuffer(ByteBuffer buffer) {
        buffers.add(buffer);
    }

    public FileBlock takeBlock() throws InterruptedException {
        return deque.take();
    }

    //当对方写入时发生错误时
    public void shutdownByWriteError() {
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount; i++) {
            deque.addFirst(WRITE_ERROR);
        }
    }

    //当其中任意一条通道断开时
    public void shutdownByConnectionBreak() {
        recycleAllBuffer();
        for (int i = 0; i < operateThreadCount - 1; i++) {
            deque.addFirst(INTERRUPT);
        }
    }

    private void recycleAllBuffer() {
        for (FileBlock fileBlock : deque) {
            if (fileBlock.data != null) {
                recycleBuffer(fileBlock.data);
            }
        }
    }

    protected abstract boolean fileExists(String path) throws Exception;

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    protected abstract FileChannel openFile(String path) throws Exception;

    protected abstract void closeFile() throws Exception;

}