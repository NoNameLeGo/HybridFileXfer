package top.weixiansen574.hybridfilexfer.droidcore;

import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.ConfigDB;
import top.weixiansen574.hybridfilexfer.core.CheckpointEntry;
import top.weixiansen574.hybridfilexfer.core.CheckpointManager;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * Android 端断点续传检查点实现：基于 ConfigDB（SQLite）存储。
 * <p>注意：HFXServer/DroidHFXClient 运行在 App 进程（文件 IO 通过 AIDL 跨进程），
 * 检查点数据可直接使用 App 进程内的数据库。</p>
 */
public class AndroidCheckpointManager implements CheckpointManager {
    /** 检查点默认保留天数 */
    public static final int DEFAULT_MAX_AGE_DAYS = 7;

    private final ConfigDB configDB;

    public AndroidCheckpointManager(Context context) {
        this.configDB = ConfigDB.getInstance(context);
        configDB.cleanupOldCheckpoints(DEFAULT_MAX_AGE_DAYS);
    }

    @Override
    public void saveCheckpoint(String filePath, long totalSize, long lastModified, int completedBlocks, String peerId) {
        configDB.saveCheckpoint(filePath, totalSize, lastModified, completedBlocks, peerId);
    }

    @Override
    public Map<String, CheckpointEntry> loadCheckpoints(List<RemoteFile> files, String peerId) {
        return configDB.loadCheckpoints(files, peerId);
    }

    @Override
    public void clearCheckpoint(String filePath, String peerId) {
        configDB.clearCheckpoint(filePath, peerId);
    }

    @Override
    public void clearAllCheckpoints(String peerId) {
        configDB.clearAllCheckpoints(peerId);
    }

    @Override
    public void cleanupOldCheckpoints(int maxAgeDays) {
        configDB.cleanupOldCheckpoints(maxAgeDays);
    }
}