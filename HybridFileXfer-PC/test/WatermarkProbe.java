/**
 * 断点续传 / 文件名清洗 / 校验超时 的回归自检（无框架，直接编译运行）。
 *
 * <pre>
 * cd HybridFileXfer-PC
 * javac -encoding UTF-8 -cp libs/annotations-24.0.1.jar -d .verify $(find src -name '*.java')
 * javac -encoding UTF-8 -cp "libs/annotations-24.0.1.jar:.verify" -d .verify test/WatermarkProbe.java
 * java -cp "libs/annotations-24.0.1.jar:.verify" WatermarkProbe   # 全部通过时退出码 0
 * </pre>
 *
 * <p>Windows 下 classpath 分隔符用 {@code ;} 代替 {@code :}。</p>
 *
 * <p>覆盖场景：</p>
 * <ol>
 *   <li>多通道乱序落盘时检查点不得越过尚未到达的前序块（旧实现写最后落盘块的末尾，续传时跳过空洞造成静默损坏）；</li>
 *   <li>乱序补齐后水位线连续推进并清除检查点；</li>
 *   <li>空文件检查点可清除；</li>
 *   <li>目标已存在更长的同名文件时，落盘后不得残留旧尾巴（truncate）；</li>
 *   <li>文件名清洗（非法字符 / 尾部点空格 / Windows 保留设备名 / 超长段）与传输路径去重；</li>
 *   <li>校验交换的读超时看门狗（对端卡死时能解除阻塞，有进展时不误杀）。</li>
 * </ol>
 */
import top.weixiansen574.hybridfilexfer.core.CheckpointEntry;
import top.weixiansen574.hybridfilexfer.core.CheckpointManager;
import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.FileSanitizer;
import top.weixiansen574.hybridfilexfer.core.ReadWatchdog;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.jdkcore.JdkWriteFileCall;
import top.weixiansen574.nio.DataByteChannel;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 功能验证：多通道乱序落盘时，检查点是否只记录「连续已完成前缀」。
 * 旧实现记录最后落盘块的末尾偏移，续传会跳过未到达的前序块，留下永久空洞。
 */
public class WatermarkProbe {

    /** 记录检查点调用的内存实现 */
    static class RecordingCM implements CheckpointManager {
        final Map<String, Long> saved = new HashMap<>();
        boolean cleared = false;

        public void saveCheckpoint(String p, long total, long mtime, long bytes, String peer) {
            saved.put(p, bytes);
        }
        public Map<String, CheckpointEntry> loadCheckpoints(List<RemoteFile> f, String peer) {
            return new HashMap<>();
        }
        public void clearCheckpoint(String p, String peer) {
            saved.remove(p);
            cleared = true;
        }
        public void clearAllCheckpoints(String peer) { saved.clear(); }
        public void cleanupOldCheckpoints(int days) {}
    }

    static final int MB = FileBlock.BLOCK_SIZE;
    static int fail = 0;

    /** 断言辅助：实际值与预期不符时计一次失败并打印 */
    static void check(String what, String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("        FAIL：" + what + " = \"" + actual + "\"（预期 \"" + expected + "\"）");
            fail++;
        }
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** 构造一个已填满的块（position=length，交给 WriteFileCall 后由其 flip） */
    static FileBlock block(String path, long totalSize, int index, int length, byte fill) {
        ByteBuffer buf = ByteBuffer.allocate(MB);
        buf.clear();
        for (int i = 0; i < length; i++) buf.put(fill);
        return new FileBlock(true, 0, path, 1757000000000L, totalSize, index, buf);
    }

    static LinkedBlockingDeque<ByteBuffer> pool(int n) {
        LinkedBlockingDeque<ByteBuffer> d = new LinkedBlockingDeque<>();
        for (int i = 0; i < n; i++) d.add(ByteBuffer.allocate(MB));
        return d;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("hfx-probe").toFile();

        // ===== 场景 1：乱序 + 中断。块 1、3 已落盘，块 0、2 未到达 =====
        {
            String path = new File(dir, "a.bin").getPath();
            RecordingCM cm = new RecordingCM();
            WriteFileCall w = new JdkWriteFileCall(pool(8), 2, new HashMap<>(), cm, "peer");
            Thread t = new Thread(() -> {
                try { w.call(); } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            // 快通道先送到块 1 和块 3（块 0、2 还在慢通道路上）
            w.putBlock(block(path, 4L * MB, 1, MB, (byte) 1), 0);
            w.putBlock(block(path, 4L * MB, 3, MB, (byte) 3), 0);
            Thread.sleep(300);
            w.cancel();          // 模拟连接中断
            t.join(3000);

            Long saved = cm.saved.get(path);
            long watermark = saved == null ? 0 : saved;
            System.out.println("[场景1] 乱序落盘块 1、3 后中断（块 0、2 从未到达）");
            System.out.println("        持久化检查点 = " + watermark + " 字节");
            System.out.println("        旧实现会写 = " + (4L * MB) + " 字节（块 3 末尾）-> 续传跳过块 0~2，文件留空洞");
            if (watermark == 0) {
                System.out.println("        PASS：水位线未越过未到达的块 0");
            } else {
                System.out.println("        FAIL：检查点越过了空洞");
                fail++;
            }
        }

        // ===== 场景 2：乱序但最终补齐。块 1、0、3、2 依次到达 =====
        {
            String path = new File(dir, "b.bin").getPath();
            RecordingCM cm = new RecordingCM();
            WriteFileCall w = new JdkWriteFileCall(pool(8), 2, new HashMap<>(), cm, "peer");
            Thread t = new Thread(() -> {
                try { w.call(); } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            w.putBlock(block(path, 4L * MB, 1, MB, (byte) 1), 0);
            Thread.sleep(80);
            w.putBlock(block(path, 4L * MB, 0, MB, (byte) 0), 1);
            Thread.sleep(80);
            w.putBlock(block(path, 4L * MB, 3, MB, (byte) 3), 0);
            Thread.sleep(80);
            w.putBlock(block(path, 4L * MB, 2, MB, (byte) 2), 1);
            Thread.sleep(200);
            w.finishChannel(0);
            w.finishChannel(1);
            t.join(3000);

            long size = new File(path).length();
            System.out.println("[场景2] 块按 1,0,3,2 乱序到达并补齐");
            System.out.println("        落盘大小 = " + size + "（预期 " + (4L * MB) + "）");
            System.out.println("        检查点已清除 = " + cm.cleared + "，残留条目 = " + cm.saved.size());
            boolean ok = size == 4L * MB && cm.saved.isEmpty();
            System.out.println(ok ? "        PASS：补齐后水位线吸收超前块并判定完成"
                                  : "        FAIL");
            if (!ok) fail++;
        }

        // ===== 场景 3：空文件的检查点必须能清除 =====
        {
            String path = new File(dir, "empty.bin").getPath();
            RecordingCM cm = new RecordingCM();
            WriteFileCall w = new JdkWriteFileCall(pool(4), 1, new HashMap<>(), cm, "peer");
            Thread t = new Thread(() -> {
                try { w.call(); } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            w.putBlock(block(path, 0L, 0, 0, (byte) 0), 0);
            Thread.sleep(200);
            w.finishChannel(0);
            t.join(3000);
            System.out.println("[场景3] 空文件（totalSize=0）");
            System.out.println("        文件已创建 = " + new File(path).exists()
                    + "，检查点已清除 = " + cm.cleared);
            if (cm.cleared && new File(path).exists()) {
                System.out.println("        PASS");
            } else {
                System.out.println("        FAIL：空文件检查点无法清除（旧实现 isFileComplete 恒为 false）");
                fail++;
            }
        }

        // ===== 场景 4：目标已存在更长的同名文件，落盘后不得残留旧尾巴 =====
        {
            String path = new File(dir, "stale.bin").getPath();
            byte[] old = new byte[4 * MB];
            for (int i = 0; i < old.length; i++) old[i] = (byte) 0x7f;
            Files.write(new File(path).toPath(), old);   // 上次传输遗留的 4MB 旧文件

            RecordingCM cm = new RecordingCM();
            WriteFileCall w = new JdkWriteFileCall(pool(8), 2, new HashMap<>(), cm, "peer");
            Thread t = new Thread(() -> {
                try { w.call(); } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            w.putBlock(block(path, 2L * MB, 0, MB, (byte) 1), 0);
            w.putBlock(block(path, 2L * MB, 1, MB, (byte) 2), 1);
            Thread.sleep(200);
            w.finishChannel(0);
            w.finishChannel(1);
            t.join(3000);

            long size = new File(path).length();
            System.out.println("[场景4] 目标已有 4MB 旧文件，本次只传 2MB");
            System.out.println("        落盘大小 = " + size + "（预期 " + (2L * MB) + "）");
            if (size == 2L * MB) {
                System.out.println("        PASS：只缩不扩的 truncate 清掉了旧文件尾巴");
            } else {
                System.out.println("        FAIL：残留 " + (size - 2L * MB) + " 字节旧数据（去掉 truncate 即会重现）");
                fail++;
            }
        }

        // ===== 场景 5：文件名清洗与传输路径去重（Issue #34/#35 与检查点主键唯一性） =====
        {
            int win = Directory.FILE_SYSTEM_WINDOWS;
            int unix = Directory.FILE_SYSTEM_UNIX;
            System.out.println("[场景5] 文件名清洗（Windows / Linux）与传输路径去重");
            check("非法字符", "2025-2-8 17_20_25.jpg",
                    FileSanitizer.sanitizeSegment("2025-2-8 17:20:25.jpg", win));
            check("尾部点", "a.b", FileSanitizer.sanitizeSegment("a.b.", win));
            check("尾部空格", "a", FileSanitizer.sanitizeSegment("a. ", win));
            check("全是点", "_", FileSanitizer.sanitizeSegment("...", win));
            check("保留设备名", "_CON", FileSanitizer.sanitizeSegment("CON", win));
            check("保留名+扩展名", "_con.txt", FileSanitizer.sanitizeSegment("con.txt", win));
            check("空名", "_", FileSanitizer.sanitizeSegment("", win));
            check("反斜杠", "a_b", FileSanitizer.sanitizeSegment("a\\b", win));
            //Linux 允许尾部点与保留名，不应改写（否则会无谓丢失续传兼容性）
            check("Linux 尾部点", "dot.", FileSanitizer.sanitizeSegment("dot.", unix));
            check("Linux 保留名", "CON", FileSanitizer.sanitizeSegment("CON", unix));
            check("超长段截断", repeat("日", 200),
                    FileSanitizer.sanitizeSegment(repeat("日", 260), unix));

            //清洗撞车：a:b.txt 与 a_b.txt 都变成 a_b.txt，第二个必须加序号
            //（否则两个文件共享同一份 FileState，水位线互相污染 → 输出损坏）
            Set<String> used = new HashSet<>();
            FileSanitizer.uniquePath("C:\\dest\\a_b.txt", used);
            check("撞车去重 1", "C:\\dest\\a_b_1.txt",
                    FileSanitizer.uniquePath("C:\\dest\\a_b.txt", used));
            check("撞车去重 2", "C:\\dest\\a_b_2.txt",
                    FileSanitizer.uniquePath("C:\\dest\\a_b.txt", used));
            Set<String> usedNoExt = new HashSet<>();
            FileSanitizer.uniquePath("C:\\dest\\nodot", usedNoExt);
            check("无扩展名去重", "C:\\dest\\nodot_1",
                    FileSanitizer.uniquePath("C:\\dest\\nodot", usedNoExt));
            System.out.println("        （以上任一项不符会单独列出 FAIL）");
        }

        // ===== 场景 6：校验交换的读超时看门狗（对端卡死时不得永久阻塞） =====
        {
            ServerSocketChannel srv = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) srv.getLocalAddress()).getPort();

            SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            SocketChannel accepted = srv.accept();
            DataByteChannel channel = new DataByteChannel(client);
            ReadWatchdog watchdog = new ReadWatchdog(channel, 500);
            Thread blocked = new Thread(() -> {
                try { channel.read(ByteBuffer.allocate(1)); } catch (Exception ignored) { }
            });
            blocked.setDaemon(true);
            blocked.start();
            blocked.join(5000);
            System.out.println("[场景6] 对端无响应（模拟卡死）");
            System.out.println("        读线程已解除阻塞 = " + !blocked.isAlive() + "，通道已关闭 = " + !channel.isOpen());
            if (!blocked.isAlive() && !channel.isOpen()) {
                System.out.println("        PASS：看门狗关通道解除了阻塞读（SO_TIMEOUT 对 NIO 阻塞读无效，实测）");
            } else {
                System.out.println("        FAIL：卡死时没有超时");
                fail++;
            }
            watchdog.cancel();

            //有进展时不得误杀（对端算大文件 MD5 时不能被判超时）
            SocketChannel client2 = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            SocketChannel accepted2 = srv.accept();
            DataByteChannel channel2 = new DataByteChannel(client2);
            ReadWatchdog watchdog2 = new ReadWatchdog(channel2, 400);
            for (int i = 0; i < 6; i++) {
                Thread.sleep(100);
                watchdog2.touch();
            }
            boolean kept = channel2.isOpen();
            watchdog2.cancel();
            System.out.println("        持续有进展时通道保持打开 = " + kept);
            if (kept) {
                System.out.println("        PASS");
            } else {
                System.out.println("        FAIL：有进展却被误杀");
                fail++;
            }
            channel.close(); accepted.close(); channel2.close(); accepted2.close(); srv.close();
        }

        System.out.println();
        System.out.println(fail == 0 ? "全部通过" : (fail + " 项失败"));
        deleteRecursively(dir);
        if (fail != 0) System.exit(1);
    }

    static void deleteRecursively(File f) {
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
