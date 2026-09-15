package top.weixiansen574.hybridfilexfer.jdkcore;

import top.weixiansen574.hybridfilexfer.core.CheckpointEntry;
import top.weixiansen574.hybridfilexfer.core.CheckpointManager;
import top.weixiansen574.hybridfilexfer.core.HFXClient;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;

public class JdkHFXClient extends HFXClient {

    public JdkHFXClient(String serverControllerAddress, int serverPort, String homeDir) {
        super(serverControllerAddress, serverPort, homeDir);
    }

    @Override
    public ByteBuffer createBuffer(int size) {
        try {
            return ByteBuffer.allocateDirect(size);
        } catch (OutOfMemoryError e) {
            //契约是「分配失败返回 null」，由 HFXClient.connect 走 onOOM 优雅退出（提示已创建/需要的块数）。
            //直接放行 OutOfMemoryError 会让整个 JVM 崩掉（Issue #84 的 Direct buffer memory 栈）。
            //缓冲区是堆外内存，上限由 -XX:MaxDirectMemorySize 决定（未设置时等于 -Xmx）。
            return null;
        }
    }

    @Override
    public long getAvailableMemoryMB() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    @Override
    protected boolean deleteLocalFile(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("文件或目录不存在: " + path);
            return false;
        }

        // 如果是目录，递归删除
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) { // 检查是否为空
                for (File subFile : files) {
                    deleteLocalFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }

    @Override
    protected boolean mkdir(String parent, String child) throws Exception {
        return new File(parent,child).mkdirs();
    }

    @Override
    protected List<RemoteFile> listFiles(String path) throws Exception {
        return Utils.listRemoteFiles(path);
    }

    @Override
    protected CheckpointManager createCheckpointManager() {
        return new JdkCheckpointManager();
    }

    /**
     * 本机稳定设备标识：持久化在 {@code ~/.hybridfilexfer/device.id}（与检查点同目录），首次使用时生成。
     * <p>用文件而非 IP：同一台电脑走 ADB（127.0.0.1）或 WLAN 时应视为同一对端，
     * 否则换一种连接方式就丢失全部续传进度。写失败时退回主机名。</p>
     */
    @Override
    protected String deviceId() {
        File idFile = new File(new File(System.getProperty("user.home"), ".hybridfilexfer"), "device.id");
        try {
            if (idFile.isFile()) {
                String id = new String(Files.readAllBytes(idFile.toPath()), StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            idFile.getParentFile().mkdirs();
            String id = UUID.randomUUID().toString();
            Files.write(idFile.toPath(), id.getBytes(StandardCharsets.UTF_8));
            return id;
        } catch (IOException e) {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                return "unknown-device";
            }
        }
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, Map<String, Long> checkpoints) {
        return new JdkWriteFileCall(buffers, dequeCount, checkpoints, getCheckpointManager(), peerId);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount, Map<String, Long> checkpoints) {
        return new JdkReadFileCall(buffers, files, localDir, remoteDir, operateThreadCount, checkpoints);
    }

    @Override
    protected boolean isCheckpointValid(String transferPath, CheckpointEntry entry) {
        File file = new File(transferPath);
        return file.isFile() && file.length() >= entry.completedBytes;
    }

    @Override
    protected String computeFileMd5(String localPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(localPath)) {
            return Utils.md5Hex(fis);
        } catch (IOException e) {
            return null;
        }
    }
}