package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.CheckpointManager;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;

public class DroidWriteFileCall extends WriteFileCall {
    private final IIOService ioService;
    private ParcelFileDescriptor pfd;
    private FileOutputStream fileOutputStream;
    private FileChannel channel;
    public DroidWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, IIOService ioService,
                              Map<String, Long> checkpoints, CheckpointManager checkpointManager, String peerId) {
        super(buffers, dequeCount, checkpoints, checkpointManager, peerId);
        this.ioService = ioService;
    }

    @Override
    protected void createParentDirIfNotExists(String path) throws Exception {
        String exception = ioService.createParentDirIfNotExists(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected void tryMkdirs(String path) throws Exception {
        String exception = ioService.tryMkdirs(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected FileChannel createAndOpenFile(String path, long length) throws Exception {
        pfd = ioService.createAndOpenWriteableFile(path, length);
        if (pfd == null) {
            //创建/打开失败时 IOServiceImpl 返回 null（如路径过长、父目录不可写）；
            //不判空会在这里抛 NullPointerException（Issue #113），报错信息里连文件名都没有
            throw new IOException("cannot open file for writing: " + path);
        }
        fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
        channel = fileOutputStream.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        channel.close();
        fileOutputStream.close();
        pfd.close();
    }

    @Override
    protected boolean setFileLastModified(String path, long time) throws Exception {
        return ioService.setFileLastModified(path,time);
    }
}
