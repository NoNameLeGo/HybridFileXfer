package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class DroidReadFileCall extends ReadFileCall {
    private final IIOService ioService;

    private ParcelFileDescriptor pfd;
    private FileInputStream fileInputStream;
    private FileChannel channel;

    public DroidReadFileCall(IIOService ioService, LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount, Map<String, Long> checkpoints) {
        super(buffers, files, localDir, remoteDir, operateThreadCount, checkpoints);
        this.ioService = ioService;
    }

    @Override
    protected boolean fileExists(String path) throws Exception {
        return ioService.fileExists(path);
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return HFXServer.listLocalFiles(ioService,path);
    }

    @Override
    protected FileChannel openFile(String path) throws Exception {
        pfd = ioService.openReadableFile(path);
        if (pfd == null) {
            //打开失败时 IOServiceImpl 返回 null（如路径过长、SAF 无权限）；
            //不判空会在这里抛 NullPointerException（Issue #113），报错信息里连文件名都没有
            throw new IOException("cannot open file for reading: " + path);
        }
        fileInputStream = new FileInputStream(pfd.getFileDescriptor());
        channel = fileInputStream.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        channel.close();
        fileInputStream.close();
        pfd.close();
    }

}
