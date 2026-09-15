package top.weixiansen574.hybridfilexfer.droidcore;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.Config;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.CheckpointEntry;
import top.weixiansen574.hybridfilexfer.core.CheckpointManager;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.ReceiveFileCall;
import top.weixiansen574.hybridfilexfer.core.SendFileCall;
import top.weixiansen574.hybridfilexfer.core.SpeedMonitorThread;
import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public class HFXServer extends HFXService {
    public static HFXServer instance;
    protected final IIOService ioService;
    private final Context context;
    protected ServerSocketChannel serverSocketChannel;
    protected int remoteFileSystem;
    protected String remoteHomeDir;

    public HFXServer(IIOService ioService, Context context) {
        this.ioService = ioService;
        this.context = context;
    }

    public void startServer(int port, List<ServerNetInterface> interfaceList, int localBufferCount, int remoteBufferCount, StartServerCallback callback) throws IOException {
        ServerSocketChannel serverSocketChannel;
        try {
            serverSocketChannel = ServerSocketChannel.open().bind(new InetSocketAddress(port));
        } catch (IOException e) {
            callback.onBindFailed(port);
            return;
        }
        this.serverSocketChannel = serverSocketChannel;
        callback.onStatedServer();
        //控制通道
        DataByteChannel ctChannel;
        //传输通道
        List<TransferConnection> connections;
        //第一此accept的为控制通道
        conn:
        while (true) {
            //协议判断
            java.nio.channels.SocketChannel socketChannel = serverSocketChannel.accept();
            //以控制通道对端地址作为对端标识的兜底（握手时会被稳定设备标识覆盖）
            peerId = ((InetSocketAddress) socketChannel.getRemoteAddress()).getAddress().getHostAddress();
            ctChannel = new DataByteChannel(socketChannel);
            byte[] headerBytes = HFXServer.CLIENT_HEADER.getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[headerBytes.length];
            ctChannel.readFully(header);
            if (!Arrays.equals(headerBytes, header)) {
                ctChannel.writeBytes("protocol error\n");
                ctChannel.close();
                continue;
            }
            //版本判断
            int versionCode = ctChannel.readInt();
            if (versionCode != HFXServer.VERSION_CODE) {
                //返回当前服务端版本信息
                ctChannel.writeBoolean(false);//版本未正确匹配
                ctChannel.writeInt(HFXServer.VERSION_CODE);
                ctChannel.close();
                continue;
            }
            ctChannel.writeBoolean(true);//版本正确匹配
            //握手互换稳定设备标识（取代 IP）：换连接方式或 IP 变更后断点续传的检查点仍能命中
            ctChannel.writeUTF(deviceId());
            peerId = ctChannel.readUTF();
            ctChannel.writeInt(interfaceList.size());//网卡IP数量
            for (ServerNetInterface netInterface : interfaceList) {
                byte[] address = netInterface.address.getAddress();
                ctChannel.writeUTF(netInterface.name);//网卡名称
                ctChannel.writeByte(address.length);//地址长度（IPv4：4与IPv6：16）
                ctChannel.write(address);//地址
                if (netInterface.clientBindAddress == null) {
                    ctChannel.writeByte(0);//地址长度（null:0）
                } else {
                    byte[] bAddress = netInterface.clientBindAddress.getAddress();
                    ctChannel.writeByte(bAddress.length);//地址长度（IPv4：4与IPv6：16）
                    ctChannel.write(bAddress);//地址
                }
            }
            //连接传输通道
            connections = new ArrayList<>(interfaceList.size());
            for (int i = 0; i < interfaceList.size(); i++) {
                //对方连接通道是否成功
                boolean succeed = ctChannel.readBoolean();
                //对方所连接通道的名称（由控制器通道发送名称）
                String name = ctChannel.readUTF();
                if (succeed) {
                    //accept通道
                    connections.add(new TransferConnection(name, new DataByteChannel(serverSocketChannel.accept())));
                    ctChannel.writeBoolean(true);
                    callback.onAccepted(name);
                } else {
                    ctChannel.writeBoolean(false);
                    ctChannel.close();
                    callback.onAcceptFailed(name);
                    //本轮已 accept 的传输通道必须关掉：continue conn 会把局部 connections 整个丢掉，
                    //不关则 App 进程里 socket fd 随每次失败累积（选多个网卡时更明显）
                    closeAcceptedConnections(connections);
                    continue conn;
                }
            }
            break;
        }

        LinkedBlockingDeque<ByteBuffer> buffers = this.buffers;
        //告知对方创建的缓冲区块数量
        ctChannel.writeInt(remoteBufferCount);
        if (!ctChannel.readBoolean()) {
            callback.onPcOOM();
            closeAcceptedConnections(connections);
            serverSocketChannel.close();
            return;
        }
        //创建缓冲区块
        for (int i = 0; i < localBufferCount; i++) {
            ByteBuffer buffer = NativeMemory.allocateLargeBuffer(FileBlock.BLOCK_SIZE);
            if (buffer != null) {
                buffers.add(buffer);
            } else {
                //释放缓冲区块内存
                for (ByteBuffer buff : buffers) {
                    NativeMemory.freeBuffer(buff);
                }
                buffers.clear();
                ctChannel.writeBoolean(false);
                closeAcceptedConnections(connections);
                serverSocketChannel.close();
                callback.onMeOOM(i, localBufferCount);
                return;
            }
        }
        ctChannel.writeBoolean(true);
        //读取对方文件系统信息
        this.remoteFileSystem = ctChannel.readInt();
        //读取对方设定的主目录
        this.remoteHomeDir = ctChannel.readUTF();
        //读取对方是否请求传输完成后执行 MD5 校验
        this.peerRequestsChecksum = ctChannel.readBoolean();
        this.ctChannel = ctChannel;
        this.connections = connections;
        callback.onConnectSuccess();
    }

    public void closeServerSocket() {
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 关闭本轮握手已 accept 的传输通道。
     * <p>必须把列表显式传进来：{@code startServer} 里有个同名**局部变量**遮住了
     * {@link HFXService#connections} 字段，而字段要到握手末尾才赋值——helper 去读字段只会拿到 null，
     * 这些失败路径上一个都关不掉（fd 依旧泄漏）。</p>
     */
    private void closeAcceptedConnections(List<TransferConnection> accepted) {
        if (accepted == null) {
            return;
        }
        for (TransferConnection connection : accepted) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
        accepted.clear();
    }

    public void disconnect(BackstageTask.BaseEventHandler callback) {
        new DisconnectTask(callback, this).execute();
    }

    public int getRemoteFileSystem() {
        return remoteFileSystem;
    }

    public String getRemoteHomeDir() {
        return remoteHomeDir;
    }

    public void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
        sendFiles(files,localDir,remoteDir,callback);
    }

    public void sendFilesToShelf(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
        ctChannel.writeInt(files.size());
        for (RemoteFile file : files) {
            ctChannel.writeUTF(file.getPath());
        }
        ctChannel.writeUTF(localDir.path);
        ctChannel.writeInt(localDir.fileSystem);
        ctChannel.writeUTF(remoteDir.path);
        receiveFiles(callback);
    }

    public List<RemoteFile> listLocalFiles(String path) throws RemoteException {
        return listLocalFiles(ioService, path);
    }

    public boolean deleteLocalFile(String file) throws RemoteException {
        return ioService.deleteFile(file);
    }

    public boolean createLocalDir(String parent, String child) throws RemoteException {
        return ioService.appendAndMkdirs(parent, child);
    }

    public List<RemoteFile> listClientFiles(String path) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
        ctChannel.writeUTF(path);
        int listSize = ctChannel.readInt();
        if (listSize == -1) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
        for (int i = 0; i < listSize; i++) {
            RemoteFile remoteFile = new RemoteFile(
                    ctChannel.readUTF(),//name
                    ctChannel.readUTF(),//path
                    ctChannel.readLong(),//lastModified
                    ctChannel.readLong(),//size
                    ctChannel.readBoolean()//isDirectory
            );
            remoteFiles.add(remoteFile);
        }
        return remoteFiles;
    }

    public boolean deleteRemoteFile(String file) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
        ctChannel.writeUTF(file);
        return ctChannel.readBoolean();
    }

    public boolean createRemoteDir(String parent, String child) throws IOException {
        ctChannel.writeShort(ControllerIdentifiers.MKDIR);
        ctChannel.writeUTF(parent);
        ctChannel.writeUTF(child);
        return ctChannel.readBoolean();
    }

    public static List<RemoteFile> listLocalFiles(IIOService ioService, String path) throws RemoteException {
        int[] chunkIds = ioService.listFiles(path);
        if (chunkIds == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>();
        for (int chunkId : chunkIds) {
            remoteFiles.addAll(ioService.getAndRemoveFileListSlice(chunkId));
        }
        return remoteFiles;
    }

    public List<String> getConnectionListINames() {
        List<String> names = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            names.add(connection.iName);
        }
        return names;
    }

    public void disconnect(){
        //释放缓冲区块内存
        for (ByteBuffer buffer : buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
        if (serverSocketChannel != null) {
            if (ctChannel != null) {
                try {
                    //通知断开连接
                    ctChannel.writeShort(ControllerIdentifiers.SHUTDOWN);
                } catch (IOException ignored) {
                }
            }
            try {
                serverSocketChannel.close();
            } catch (IOException ignored) {
            }
        }
        //关闭所有用于传输的连接
        if (connections != null) {
            for (TransferConnection connection : connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void close(){
        //释放并清理缓冲区块
        for (ByteBuffer buffer : buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
        //关闭serverSocket，释放端口绑定，不用管其他accept的Socket
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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
        return new DroidWriteFileCall(buffers, dequeCount, ioService, checkpoints, getCheckpointManager(), peerId);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount, Map<String, Long> checkpoints) {
        return new DroidReadFileCall(ioService, buffers, files, localDir, remoteDir, operateThreadCount, checkpoints);
    }

    @Override
    protected boolean isCheckpointValid(String transferPath, CheckpointEntry entry) {
        try {
            //除长度外还要求「目标文件没有在检查点记录之后被改过」（见 CheckpointEntry.timestamp）：
            //否则被换成另一个更大的同名文件时会跳过前 N 字节，静默写出混合文件
            return ioService.isFile(transferPath)
                    && ioService.getFileSize(transferPath) >= entry.completedBytes
                    && ioService.getFileLastModified(transferPath) <= entry.timestamp;
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    protected String computeFileMd5(String localPath, Runnable onProgress) throws Exception {
        ParcelFileDescriptor pfd = ioService.openReadableFile(localPath);
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
}
