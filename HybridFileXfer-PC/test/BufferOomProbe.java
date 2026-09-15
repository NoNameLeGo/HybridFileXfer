/**
 * 缓冲区块分配失败时的优雅降级自检（无框架，退出码 0 表示通过）。
 *
 * <pre>
 * cd HybridFileXfer-PC
 * javac -encoding UTF-8 -cp libs/annotations-24.0.1.jar -d .verify $(find src -name '*.java')
 * javac -encoding UTF-8 -cp "libs/annotations-24.0.1.jar:.verify" -d .verify test/BufferOomProbe.java
 * # 必须限死堆外内存，否则这条断言在内存充裕的机器上不会触发
 * java -XX:MaxDirectMemorySize=16m -cp "libs/annotations-24.0.1.jar:.verify" BufferOomProbe
 * </pre>
 *
 * <p>为什么需要这条自检：{@code HFXClient.connect} 依赖 {@code createBuffer} 「分配失败返回 null」
 * 的契约走 {@code onOOM} 提示。JDK 侧若直接放行 {@code OutOfMemoryError}（Issue #84），
 * 整个 JVM 会带着一段 Direct buffer memory 栈崩掉，用户看不到任何可操作的提示。</p>
 *
 * <p>Windows 下 classpath 分隔符用 {@code ;} 代替 {@code :}。</p>
 */
import top.weixiansen574.hybridfilexfer.jdkcore.JdkHFXClient;

import java.nio.ByteBuffer;

public class BufferOomProbe {

    public static void main(String[] args) {
        JdkHFXClient client = new JdkHFXClient("127.0.0.1", 5740, "");

        // 1. 正常情况下按要求的尺寸分配
        ByteBuffer ok = client.createBuffer(1024 * 1024);
        assertTrue(ok != null && ok.capacity() == 1024 * 1024,
                "1MB 缓冲区块应分配成功，实际：" + ok);

        // 2. 堆外内存不足时必须返回 null，而不是抛 OutOfMemoryError
        ByteBuffer tooBig = null;
        try {
            tooBig = client.createBuffer(1024 * 1024 * 1024);
        } catch (OutOfMemoryError e) {
            fail("createBuffer 泄漏了 OutOfMemoryError：" + e);
        }
        assertTrue(tooBig == null, "堆外内存不足时应返回 null，实际：" + tooBig);

        System.out.println("BufferOomProbe: 2/2 通过");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
        System.out.println("  PASS " + message);
    }

    private static void fail(String message) {
        System.out.println("  FAIL " + message);
        System.exit(1);
    }
}
