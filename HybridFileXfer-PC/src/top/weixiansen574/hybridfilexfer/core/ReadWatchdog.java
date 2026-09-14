package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;

import top.weixiansen574.nio.DataByteChannel;

/**
 * 控制通道读超时看门狗：长时间收不到对端数据时关闭控制通道，让阻塞中的读抛异常。
 *
 * <p>为什么不用 {@code Socket.setSoTimeout}：控制通道底层是阻塞式 {@code SocketChannel}，
 * 实测对它调用 {@code socket().setSoTimeout(500)} 不会抛异常，但随后阻塞的 {@code read()} 依旧
 * 永不返回——SO_TIMEOUT 对 NIO 通道无效。而 {@code close()} 能在几十毫秒内让阻塞读抛
 * {@code AsynchronousCloseException}，故以此实现超时。</p>
 *
 * <p>每次读到数据后调用 {@link #touch()} 重置计时，只有「长时间完全没有进展」才判定卡死，
 * 因此对端在计算大文件 MD5 时不会被误杀（响应是按文件逐个回传的）。</p>
 *
 * <p>代价：判定超时后会关闭整个控制通道，本次会话作废（文件数据已落盘，重连即可继续）。</p>
 */
public class ReadWatchdog {
    private final DataByteChannel channel;
    private final long stallTimeoutMs;
    private volatile long lastProgress = System.currentTimeMillis();
    private volatile boolean stopped;
    private final Thread thread;

    public ReadWatchdog(DataByteChannel channel, long stallTimeoutMs) {
        this.channel = channel;
        this.stallTimeoutMs = stallTimeoutMs;
        thread = new Thread(this::watch, "ControlChannelWatchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private void watch() {
        long checkInterval = Math.max(200, Math.min(5000, stallTimeoutMs / 10));
        while (!stopped) {
            try {
                Thread.sleep(checkInterval);
            } catch (InterruptedException e) {
                return;
            }
            if (System.currentTimeMillis() - lastProgress > stallTimeoutMs) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                return;
            }
        }
    }

    /** 读操作有进展（或即将开始一次读）时调用，重置计时 */
    public void touch() {
        lastProgress = System.currentTimeMillis();
    }

    /** 交换结束，停止看门狗 */
    public void cancel() {
        stopped = true;
        thread.interrupt();
    }
}
