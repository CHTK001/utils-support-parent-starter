import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * 慢客户端 / SSE 帧完整性探针。
 *
 * <p>两种模式:</p>
 * <ul>
 *   <li>{@code slow}:连接后发 {@code GET /sse} 却不读取,验证服务端在写队列积压超过
 *       {@code maxPendingWriteBytes} 后主动断开慢消费者,且断开发生时的已发字节数远小于
 *       该连接本应推送的全量字节数(即没有把整条流缓冲下来)。</li>
 *   <li>{@code fast}:正常读取直到对端关闭,逐个校验 chunked 帧结构,验证 SSE 帧被拆分成
 *       多次写入后没有造成分块编码破损。</li>
 * </ul>
 *
 * <p>用法: {@code java SlowClientProbe <port> <slow|fast> [expectedEvents] [payloadBytes]}</p>
 */
public class SlowClientProbe {

    /**
     * 工具类,不实例化。
     */
    private SlowClientProbe() {
    }

    /**
     * 入口。
     *
     * @param args 端口、模式、事件数、负载大小
     * @throws Exception 连接或读取失败
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18340;
        String mode = args.length > 1 ? args[1] : "slow";
        int expectedEvents = args.length > 2 ? Integer.parseInt(args[2]) : 50000;
        int payloadBytes = args.length > 3 ? Integer.parseInt(args[3]) : 512;

        // 每个事件帧: "data: " + payload + "\n\n"
        int frameBytes = 6 + payloadBytes + 2;
        long expectedPayloadBytes = (long) frameBytes * expectedEvents;

        long start = System.nanoTime();
        if ("fast".equals(mode)) {
            runFast(port, expectedEvents, frameBytes, expectedPayloadBytes, start);
        } else {
            runSlow(port, expectedEvents, expectedPayloadBytes, start);
        }
    }

    /**
     * 正常读取模式:校验 chunked 帧结构与事件数。
     *
     * @param port                服务端口
     * @param expectedEvents      预期事件数
     * @param frameBytes          单帧数据长度
     * @param expectedPayloadBytes 预期总数据字节
     * @param start               开始时间
     * @throws IOException 连接失败
     */
    private static void runFast(int port, int expectedEvents, int frameBytes,
                                long expectedPayloadBytes, long start) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(20000);
            OutputStream out = socket.getOutputStream();
            out.write(request(expectedEvents).getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] body = readToEof(socket.getInputStream());
            ChunkParser parser = new ChunkParser(body);
            boolean framingOk = parser.parse();
            long ms = (System.nanoTime() - start) / 1_000_000L;

            System.out.println("MODE fast");
            System.out.println("es_events=" + parser.chunks);
            System.out.println("es_payload_bytes=" + parser.payloadBytes);
            System.out.println("es_framing=" + (framingOk ? "ok" : "BAD"));
            System.out.println("es_all_same_len=" + (parser.mismatched == 0));
            System.out.println("es_terminated=" + parser.terminated);
            System.out.println("es_ms=" + ms);
            boolean ok = framingOk && parser.terminated && parser.mismatched == 0
                    && parser.chunks == expectedEvents
                    && parser.payloadBytes == expectedPayloadBytes;
            System.out.println("es_ok=" + ok);
        }
    }

    /**
     * 慢客户端模式:发送请求后不读取,等待服务端因背压断开。
     *
     * @param port                 服务端口
     * @param expectedPayloadBytes 该连接本应推送的全量数据字节
     * @param start                开始时间
     * @throws Exception 连接失败
     */
    private static void runSlow(int port, int expectedEvents, long expectedPayloadBytes,
                                long start) throws Exception {
        try (Socket socket = new Socket()) {
            // 小接收窗口:让内核不会替我们缓冲太多,服务端更快触及写队列门限
            socket.setReceiveBufferSize(4096);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(1000);
            OutputStream out = socket.getOutputStream();
            out.write(request(expectedEvents).getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // 阶段一:完全停止读取,模拟卡死的消费者
            Thread.sleep(3000);

            // 阶段二:缓慢排空,直到对端关闭(FIN/RST)或超时
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[8192];
            long received = 0;
            String closedBy = "timeout";
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                try {
                    int n = in.read(buf);
                    if (n < 0) {
                        closedBy = "FIN";
                        break;
                    }
                    received += n;
                } catch (SocketTimeoutException e) {
                    // 没新数据,继续等待
                } catch (IOException e) {
                    closedBy = "RST";
                    break;
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000L;

            System.out.println("MODE slow");
            System.out.println("es_closed_by=" + closedBy);
            System.out.println("es_received_bytes=" + received);
            System.out.println("es_expected_bytes=" + expectedPayloadBytes);
            System.out.println("es_expected_events=" + expectedEvents);
            System.out.println("es_delivered_pct_x100="
                    + (expectedPayloadBytes <= 0 ? 0 : received * 10000 / expectedPayloadBytes));
            System.out.println("es_ms=" + ms);
            boolean ok = !"timeout".equals(closedBy) && received < expectedPayloadBytes;
            System.out.println("es_ok=" + ok);
        }
    }

    /**
     * 构造最简单的 SSE 请求报文。
     *
     * @param events 请求的事件数(通过 ?n= 传给服务端)
     * @return 请求字符串
     */
    private static String request(int events) {
        return "GET /sse?n=" + events + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Accept: text/event-stream\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
    }

    /**
     * 读到对端关闭为止。
     *
     * @param in 输入流
     * @return 读到的全部字节
     * @throws IOException 读取失败
     */
    private static byte[] readToEof(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * 极简 chunked 解析器:逐帧校验长度行与数据长度是否一致。
     */
    private static final class ChunkParser {

        /**
         * 原始响应字节。
         */
        private final byte[] raw;

        /**
         * 正文起点。
         */
        private int pos;

        /**
         * 已解析的 chunk 数。
         */
        private int chunks;

        /**
         * 已解析的数据字节数。
         */
        private long payloadBytes;

        /**
         * 长度行与实际数据不一致的次数。
         */
        private int mismatched;

        /**
         * 是否遇到终止块。
         */
        private boolean terminated;

        /**
         * 构造。
         *
         * @param raw 完整响应字节
         */
        ChunkParser(byte[] raw) {
            this.raw = raw;
            int headEnd = indexOf(raw, new byte[]{'\r', '\n', '\r', '\n'}, 0);
            this.pos = headEnd < 0 ? 0 : headEnd + 4;
        }

        /**
         * 逐帧解析。
         *
         * @return 结构是否完整无破损
         */
        boolean parse() {
            while (pos < raw.length) {
                int lineEnd = indexOf(raw, new byte[]{'\r', '\n'}, pos);
                if (lineEnd < 0) {
                    return false;
                }
                String sizeLine = new String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
                int semi = sizeLine.indexOf(';');
                if (semi >= 0) {
                    sizeLine = sizeLine.substring(0, semi);
                }
                int size;
                try {
                    size = Integer.parseInt(sizeLine, 16);
                } catch (NumberFormatException e) {
                    return false;
                }
                pos = lineEnd + 2;
                if (size == 0) {
                    terminated = true;
                    return true;
                }
                if (pos + size + 2 > raw.length) {
                    return false;
                }
                if (raw[pos + size] != '\r' || raw[pos + size + 1] != '\n') {
                    mismatched++;
                }
                chunks++;
                payloadBytes += size;
                pos += size + 2;
            }
            return false;
        }

        /**
         * 查找子序列。
         *
         * @param hay    被查找数组
         * @param needle 目标子序列
         * @param from   起点
         * @return 下标,未找到返回 -1
         */
        private static int indexOf(byte[] hay, byte[] needle, int from) {
            outer:
            for (int i = from; i <= hay.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (hay[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }
}
