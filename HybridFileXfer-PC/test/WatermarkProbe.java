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
 *   <li>校验交换的读超时看门狗（对端卡死时能解除阻塞，有进展时不误杀）；</li>
 *   <li>校验应答的空路径心跳帧（协议 304）与 md5 报活回调；</li>
 *   <li>检查点 mtime 守卫（目标文件在记录之后被改过则作废）与 cancel() 后的缓冲块回收。</li>
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
import java.nio.charset.StandardCharsets;
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

    /** 断言辅助：布尔条件不成立时计一次失败并打印 */
    static void checkTrue(String what, boolean condition) {
        if (!condition) {
            System.out.println("        FAIL：" + what);
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
            //超长段：Windows 按 UTF-16 单元（char）截，Linux/Android 按 UTF-8 字节截。
            //汉字 3 字节/个：Linux 侧 240 字节 = 80 个字；旧实现按字符截到 200 字（600 字节）仍会 ENAMETOOLONG
            check("Windows 超长段截断", repeat("a", 200),
                    FileSanitizer.sanitizeSegment(repeat("a", 300), win));
            String cjk80 = FileSanitizer.sanitizeSegment(repeat("日", 260), unix);
            check("Linux 超长段截断", repeat("日", 80), cjk80);
            checkTrue("Linux 截断后 UTF-8 字节数 <= 240",
                    cjk80.getBytes(StandardCharsets.UTF_8).length <= 240);
            checkTrue("Linux 中文名未超限时不被截断",
                    FileSanitizer.sanitizeSegment(repeat("日", 80), unix).equals(repeat("日", 80)));
            checkTrue("Linux 截断不切开代理对",
                    FileSanitizer.sanitizeSegment(repeat("😀", 100), unix).getBytes(StandardCharsets.UTF_8).length <= 240);

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

        // ===== 场景 7：校验交换的空路径心跳（协议 304；单个大文件算 MD5 超过看门狗阈值时不误杀） =====
        {
            System.out.println("[场景7] 校验应答期间的心跳帧（空路径）与 md5 报活回调");
            //md5Hex 必须按块回调，否则对端看门狗收不到任何「还在干活」的信号
            byte[] data = new byte[3 * 1024 * 1024 + 7];
            final int[] beats = {0};
            String md5 = top.weixiansen574.hybridfilexfer.core.Utils.md5Hex(
                    new java.io.ByteArrayInputStream(data), () -> beats[0]++);
            checkTrue("md5Hex 对 3MB+ 输入至少回调 3 次（实测 " + beats[0] + " 次）", beats[0] >= 3);
            check("md5Hex 结果不因回调而变",
                    top.weixiansen574.hybridfilexfer.core.Utils.md5Hex(new java.io.ByteArrayInputStream(data)), md5);

            //发起方读结果时必须跳过空路径心跳，否则会把心跳当成路径、把真正的路径当成 md5 → 帧错位
            ServerSocketChannel srv7 = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
            int port7 = ((InetSocketAddress) srv7.getLocalAddress()).getPort();
            SocketChannel initiatorSide = SocketChannel.open(new InetSocketAddress("127.0.0.1", port7));
            SocketChannel responderSide = srv7.accept();

            File target = new File(dir, "checksum.bin");
            Files.write(target.toPath(), data);
            String expected = md5;

            Thread responder = new Thread(() -> {
                try {
                    DataByteChannel out = new DataByteChannel(responderSide);
                    out.writeInt(1);                       //本次校验 1 个文件
                    out.writeUTF(""); out.writeUTF("");    //心跳：正在算 MD5
                    out.writeUTF(target.getPath());
                    out.writeUTF(expected);
                } catch (Exception e) {
                    e.printStackTrace();
                    //应答方挂了就关掉通道，否则发起方要等看门狗阈值（10 分钟）才失败，CI 会僵住
                    try { responderSide.close(); } catch (Exception ignored) { }
                }
            });
            responder.setDaemon(true);
            responder.start();

            //发起方：真实的 JdkHFXClient（HFXService 子类），用反射注入控制通道与「接收方」角色
            top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient initiator =
                    new top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient("127.0.0.1", 0, dir.getPath());
            java.lang.reflect.Field ctField = top.weixiansen574.hybridfilexfer.core.HFXService.class.getDeclaredField("ctChannel");
            ctField.setAccessible(true);
            ctField.set(initiator, new DataByteChannel(initiatorSide));
            java.lang.reflect.Field sideField = top.weixiansen574.hybridfilexfer.core.HFXService.class.getDeclaredField("receiverSide");
            sideField.setAccessible(true);
            sideField.setBoolean(initiator, true);
            java.lang.reflect.Field pathsField = top.weixiansen574.hybridfilexfer.core.HFXService.class.getDeclaredField("receivedTransferPaths");
            pathsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> receivedPaths = (List<String>) pathsField.get(initiator);
            receivedPaths.add(target.getPath());

            final boolean[] passed = {false};
            final List<String>[] mismatches = new List[]{null};
            initiator.verifyFiles(new top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback() {
                public void onFileUploading(String n, String p, long t, long s) { }
                public void onFileDownloading(String n, String p, long t, long s) { }
                public void onSpeedInfo(List<top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo> l) { }
                public void onChannelComplete(String n, long t, long s) { }
                public void onChannelError(String n, int e, String m) { }
                public void onReadFileError(String m) { }
                public void onWriteFileError(String m) { }
                public void onComplete(boolean u, long t, long s) { }
                public void onIncomplete() { }
                public void onFileChecksumComplete(boolean ok, List<String> files) {
                    passed[0] = ok;
                    mismatches[0] = files;
                }
            });
            responder.join(5000);
            System.out.println("        校验结果 passed=" + passed[0] + " 失败文件=" + mismatches[0]);
            checkTrue("跳过心跳后校验通过（旧代码会把空路径当文件名 → 误报失败）", passed[0]);
            checkTrue("失败清单为空", mismatches[0] != null && mismatches[0].isEmpty());
            initiatorSide.close(); responderSide.close(); srv7.close();
        }

        // ===== 场景 8：检查点 mtime 守卫与 cancel 后的缓冲块回收（本轮新增的两处逻辑） =====
        {
            System.out.println("[场景8] 检查点有效性守卫 + cancel 后归还缓冲块");
            File guarded = new File(dir, "guarded.bin");
            Files.write(guarded.toPath(), new byte[100]);
            long mtime = guarded.lastModified();

            java.lang.reflect.Method valid = top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient.class
                    .getDeclaredMethod("isCheckpointValid", String.class, CheckpointEntry.class);
            valid.setAccessible(true);
            top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient probe =
                    new top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient("127.0.0.1", 0, dir.getPath());

            checkTrue("文件未被改动过（mtime <= 记录时间）→ 续传可用",
                    (Boolean) valid.invoke(probe, guarded.getPath(),
                            new CheckpointEntry(guarded.getPath(), 200, mtime, 50, "peer", mtime + 1000)));
            checkTrue("文件在记录之后被改过（mtime > 记录时间）→ 作废",
                    !(Boolean) valid.invoke(probe, guarded.getPath(),
                            new CheckpointEntry(guarded.getPath(), 200, mtime, 50, "peer", mtime - 1000)));
            checkTrue("长度小于已完成字节 → 作废",
                    !(Boolean) valid.invoke(probe, guarded.getPath(),
                            new CheckpointEntry(guarded.getPath(), 200, mtime, 150, "peer", mtime + 1000)));
            checkTrue("文件不存在 → 作废",
                    !(Boolean) valid.invoke(probe, new File(dir, "not-exist.bin").getPath(),
                            new CheckpointEntry("x", 200, mtime, 50, "peer", mtime + 1000)));

            //cancel() 之后到达的块必须直接归还缓冲池（否则 Android 侧每块 1MB native 内存再也回不来）
            String p8 = new File(dir, "cancelled.bin").getPath();
            LinkedBlockingDeque<ByteBuffer> pool8 = pool(4);
            WriteFileCall w8 = new JdkWriteFileCall(pool8, 1, new HashMap<>(), new RecordingCM(), "peer");
            int before = pool8.size();
            w8.cancel();
            w8.putBlock(block(p8, MB, 0, MB, (byte) 7), 0);
            checkTrue("cancel 后到达的块归还了缓冲池（前 " + before + " → 后 " + pool8.size() + "）",
                    pool8.size() == before + 1);
            checkTrue("cancel 后到达的块未被写入磁盘", !new File(p8).exists());

            //cancel() 幂等：多通道中断时会被调用多次，重复调用不得把同一批块重复归还（池里出现重复引用
            //会让后续传输两个线程共用一块内存，Android 侧还会二次 free）
            String p9 = new File(dir, "cancel-twice.bin").getPath();
            LinkedBlockingDeque<ByteBuffer> pool9 = pool(2);
            WriteFileCall w9 = new JdkWriteFileCall(pool9, 1, new HashMap<>(), new RecordingCM(), "peer");
            int before9 = pool9.size();
            w9.putBlock(block(p9, 2L * MB, 0, MB, (byte) 1), 0);
            w9.putBlock(block(p9, 2L * MB, 1, MB, (byte) 2), 0);
            w9.cancel();
            int afterFirstCancel = pool9.size();
            checkTrue("cancel() 第一次归还了队列里的 2 块（" + before9 + " → " + afterFirstCancel + "）",
                    afterFirstCancel == before9 + 2);
            //队列必须清空：块留着会被写线程再取走一次（takeBlock 先查队列再查 canceled），
            //而它的缓冲块已经归还，可能已被别的线程复用 → 数据错乱
            checkTrue("cancel() 后队列已清空（取不到残留块）", w9.tryTakeBlockInternal() == null);
            w9.cancel();
            checkTrue("cancel() 幂等：重复调用不再重复入池", pool9.size() == afterFirstCancel);
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
