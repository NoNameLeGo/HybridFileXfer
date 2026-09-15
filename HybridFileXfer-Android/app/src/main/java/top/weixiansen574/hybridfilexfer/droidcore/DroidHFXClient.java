package top.weixiansen574.hybridfilexfer.droidcore;

import android.app.ActivityManager;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.Config;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.CheckpointEntry;
import top.weixiansen574.hybridfilexfer.core.CheckpointManager;
import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class DroidHFXClient extends HFXClient {
    private final IIOService iioService;
    private final Context context;
    public DroidHFXClient(String serverControllerAddress, int serverPort,String homeDir, IIOService iioService, Context context) {
        super(serverControllerAddress, serverPort,homeDir);
        this.iioService = iioService;
        this.context = context;
    }

    @Override
    public ByteBuffer createBuffer(int size) {
        return NativeMemory.allocateLargeBuffer(size);
    }

    @Override
    public long getAvailableMemoryMB() {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long totalMemory = memoryInfo.totalMem;

        long availableMemory = memoryInfo.availMem;

        long totalMemoryMB = totalMemory / (1024 * 1024);
        long availableMemoryMB = availableMemory / (1024 * 1024);

        return (long) (availableMemoryMB - (totalMemoryMB * 0.05));
    }

    @Override
    protected boolean deleteLocalFile(String path) throws Exception {
        return iioService.deleteFile(path);
    }

    @Override
    protected boolean mkdir(String parent, String child) throws Exception {
        return iioService.appendAndMkdirs(parent,child);
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return HFXServer.listLocalFiles(iioService,path);
    }

    @Override
    protected CheckpointManager createCheckpointManager() {
        return new AndroidCheckpointManager(context);
    }

    /** 本机稳定设备标识：持久化在 SharedPreferences，首次使用时生成（不能用 IP：换连接方式即失效） */
    @Override
    protected String deviceId() {
        return Config.getInstance(context).getDeviceId();
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, Map<String, Long> checkpoints) {
        return new DroidWriteFileCall(buffers, dequeCount, iioService, checkpoints, getCheckpointManager(), peerId);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount, Map<String, Long> checkpoints) {
        return new DroidReadFileCall(iioService, buffers, files, localDir, remoteDir, operateThreadCount, checkpoints);
    }

    @Override
    protected boolean isCheckpointValid(String transferPath, CheckpointEntry entry) {
        try {
            //除长度外还要求「目标文件没有在检查点记录之后被改过」（见 CheckpointEntry.timestamp）：
            //否则被换成另一个更大的同名文件时会跳过前 N 字节，静默写出混合文件
            return iioService.isFile(transferPath)
                    && iioService.getFileSize(transferPath) >= entry.completedBytes
                    && iioService.getFileLastModified(transferPath) <= entry.timestamp;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    protected String computeFileMd5(String localPath, Runnable onProgress) throws Exception {
        ParcelFileDescriptor pfd = iioService.openReadableFile(localPath);
        if (pfd == null) {
            return null;
        }
        try {
            //fd 的所有权属于 ParcelFileDescriptor，只由它关闭；
            //若再用 FileInputStream 的 try-with-resources 关一次同一个 fd，第二次关闭会落在已被复用的 fd 上
            return Utils.md5Hex(new FileInputStream(pfd.getFileDescriptor()), onProgress);
        } finally {
            pfd.close();
        }
    }

    public void freeBuffers(){
        for (ByteBuffer buffer : buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
    }
}
