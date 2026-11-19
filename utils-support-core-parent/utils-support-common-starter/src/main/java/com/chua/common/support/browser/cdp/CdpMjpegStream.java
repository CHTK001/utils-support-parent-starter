package com.chua.common.support.browser.cdp;

import com.chua.common.support.browser.BrowserPage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 实时画面的 MJPEG 流
 *
 * <p>把 {@link BrowserPage#startScreencast(Consumer)} 推送的 JPEG 帧包装成
 * {@code multipart/x-mixed-replace} 输入流，供 Web 层直接写回响应：
 * 三方前端用 {@code <img src="流地址">} 即可嵌入实时画面，不需要任何 JS。
 * 画面本质是连续的 JPEG 帧，比单张截图「活」：页面一动，画面就跟着变。</p>
 *
 * <p>Spring 控制器示例：</p>
 * <pre>{@code
 * @GetMapping("/browser/live.mjpeg")
 * public void live(@RequestParam String url, HttpServletResponse response) throws Exception {
 *     response.setContentType("multipart/x-mixed-replace; boundary=frame");
 *     BrowserPage page = Cdp.open(url);
 *     try (CdpMjpegStream stream = CdpMjpegStream.start(page)) {
 *         stream.transferTo(response.getOutputStream());
 *     } finally {
 *         page.close();
 *     }
 * }
 * }</pre>
 *
 * <p>前端嵌入：</p>
 * <pre>{@code <img src="/browser/live.mjpeg?url=https%3A%2F%2Fexample.com">}</pre>
 *
 * <p>帧队列有界：消费慢时丢弃最旧帧，保证画面始终接近实时、延迟不累积。
 * 注意 Safari 对 {@code <img>} 里的 MJPEG 支持较差，Chrome / Edge / Firefox 正常。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class CdpMjpegStream extends InputStream {

    /**
     * multipart 边界名（与 Content-Type 里的 boundary 一致）
     */
    private static final String BOUNDARY = "frame";

    /**
     * 帧缓冲容量：消费慢时丢帧，不无限堆积
     */
    private static final int FRAME_BUFFER = 3;

    /**
     * 单次等待新帧的最长时间（毫秒），用于感知流关闭
     */
    private static final long FRAME_WAIT_MILLIS = 1000L;

    /**
     * 帧队列
     */
    private final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>(FRAME_BUFFER);

    /**
     * 流是否仍在推送
     */
    private final AtomicBoolean open = new AtomicBoolean(true);

    /**
     * screencast 关闭句柄
     */
    private volatile AutoCloseable handle;

    /**
     * 当前正在读取的 multipart 分段
     */
    private ByteArrayInputStream segment;

    /**
     * 结束边界是否已写入
     */
    private volatile boolean endWritten;

    /**
     * 禁止外部实例化
     */
    private CdpMjpegStream() {
    }

    /**
     * 在页面上开启实时画面并返回 MJPEG 流
     *
     * <p>开流后会立即截取一张截图：页面静止时合成器可能长时间不产出新帧，
     * 截图能强制合成器立即绘制一帧，保证客户端连上就能看到画面。</p>
     *
     * @param page 已导航到目标页面的页面
     * @return MJPEG 流；用完 close 只停推送，页面生命周期仍由调用方管理
     * @throws IllegalArgumentException 页面为空
     * @throws IllegalStateException    页面不支持实时画面或浏览器连接异常
     */
    public static CdpMjpegStream start(BrowserPage page) {
        if (page == null) {
            throw new IllegalArgumentException("page 不能为空");
        }
        CdpMjpegStream stream = new CdpMjpegStream();
        // 开流即观看：先把页面带到前台。多标签页时后台页面可能收不到滚轮等
        // 输入事件，画面帧也可能因页面不可见而停更
        try {
            page.bringToFront();
        } catch (Exception e) {
            // 尽力而为：激活失败不阻塞开流，后续帧仍按正常节奏推送
        }
        stream.handle = page.startScreencast(stream::offer);
        // 静态页合成器闲置时首帧可能迟迟不来：截图强制其立即产出一帧，客户端免于等待
        try {
            page.screenshot(false);
        } catch (Exception e) {
            // 尽力而为：助推失败不阻塞开流，后续帧仍按正常节奏推送
        }
        return stream;
    }

    /**
     * 接收一帧（由页面回调）
     *
     * @param frame JPEG 字节
     */
    private void offer(byte[] frame) {
        if (!open.get() || frame == null || frame.length == 0) {
            return;
        }
        if (!frames.offer(frame)) {
            // 队列满说明消费慢：丢最旧帧，保留最新画面
            frames.poll();
            frames.offer(frame);
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (buffer == null) {
            throw new NullPointerException("buffer 不能为空");
        }
        if (offset < 0 || length < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length);
        }
        if (length == 0) {
            return 0;
        }
        while (true) {
            if (segment == null) {
                segment = nextSegment();
                if (segment == null) {
                    return -1;
                }
            }
            int count = segment.read(buffer, offset, length);
            if (count > 0) {
                return count;
            }
            segment = null;
        }
    }

    /**
     * 取下一帧的完整 multipart 分段
     *
     * @return 分段字节；流结束返回 null
     * @throws IOException 等待帧时被中断
     */
    private ByteArrayInputStream nextSegment() throws IOException {
        while (true) {
            if (!open.get()) {
                if (endWritten) {
                    return null;
                }
                endWritten = true;
                return new ByteArrayInputStream(endBoundary());
            }
            byte[] frame;
            try {
                frame = frames.poll(FRAME_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("读取实时画面被中断", e);
            }
            if (frame == null) {
                continue;
            }
            return new ByteArrayInputStream(buildSegment(frame));
        }
    }

    /**
     * 组装一帧的 multipart 分段
     *
     * @param frame JPEG 字节
     * @return 分段字节
     */
    private static byte[] buildSegment(byte[] frame) {
        byte[] head = ("--" + BOUNDARY + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + frame.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[head.length + frame.length + tail.length];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(frame, 0, result, head.length, frame.length);
        System.arraycopy(tail, 0, result, head.length + frame.length, tail.length);
        return result;
    }

    /**
     * 结束边界
     *
     * @return 结束边界字节
     */
    private static byte[] endBoundary() {
        return ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() {
        open.set(false);
        AutoCloseable screencast = handle;
        handle = null;
        if (screencast != null) {
            try {
                screencast.close();
            } catch (Exception ignored) {
                // 停止推送失败不影响流关闭
            }
        }
    }
}
