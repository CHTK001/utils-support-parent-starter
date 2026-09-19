package com.chua.common.support.network.server.nio;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于 NIO {@link SocketChannel} 的 {@link ServerResponse} 实现。
 *
 * <p>采用双模式：
 * <ul>
 *   <li><b>缓冲模式</b>（默认）：所有写入先缓存在内存中，通过 {@link #complete()} 统一
 *       构建 HTTP/1.1 响应报文并写入 channel。支持 keep-alive 连接复用。</li>
 *   <li><b>流式模式</b>（SSE）：调用 {@link #sse()} 后立即写入 HTTP 头，
 *       后续使用 chunked transfer encoding 实时写入数据帧。</li>
 * </ul>
 *
 * <p>性能关键路径：header 构建走零分配快路径（echo 场景复用预拼字节模板，
 * 避免每次 StringBuilder 分配与 US_ASCII 编码），对纯回显小响应吞吐有显著提升。</p>
 *
 * @author CH
 * @since 2026/08/12
 */
public class NioServerResponse implements ServerResponse {

    /**
     * Crlf
    */
    private static final byte[] CRLF = {'\r', '\n'};
    /**
     * Colon_sp
    */
    private static final byte[] COLON_SP = {':', ' '};
    /**
     * Zero_chunk
    */
    private static final byte[] ZERO_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    // ==================== Header 零分配快路径模板 ====================
    // echo 场景固定结构:HTTP/1.1 200 OK + Content-Type + Content-Length + Connection,
    // 预拼前缀+长度数字+后缀,避免每请求 StringBuilder 分配与 US_ASCII 编码拷贝。
    private static final byte[] STATUS_200_PREFIX =
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Length: ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] KEEPALIVE_SUFFIX =
            "\r\nConnection: keep-alive\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    // 单字节数字 ASCII 表
    private static final byte[] DIGITS = {'0','1','2','3','4','5','6','7','8','9'};
    // 完整预拼 echo header:状态行 + 固定头 + keepalive + 空行(动态长度后续追加 Content-Length)
    // 由 buildEchoHeaderFast() 在首次调用时按 content-length 缓存
    private static final java.util.Map<Integer, byte[]> ECHO_HEADER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 通道
    */
    private final SocketChannel channel;
    /**
     * 状态代码
    */
    private int statusCode = 200;
    /**
     * headers
    */
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /**
     * 请求体
    */
    private byte[] body;
    /**
     * Ended
    */
    private boolean ended;
    /**
     * Committed
    */
    private boolean committed;
    /**
     * Sent
    */
    private boolean sent;
    /**
     * SSE模式
    */
    private boolean sseMode;
    /**
     * 通道closed
    */
    private boolean channelClosed;
    /**
     * 结果
    */
    private Object result;
    /**
     * RAW输出
    */
    private ByteArrayOutputStream rawOutput;

    /**
     * 异步写出回调(真响应式):事件循环设置后,complete() 不再直接写 channel,
     * 而是把响应头/体字节交给回调,由事件循环通过 OP_WRITE 驱动写出。
     */
    private java.util.function.BiConsumer<ByteBuffer, ByteBuffer> asyncWriter;

    /**
     * 创建 NioServerResponse 实例
     * @param channel channel
     */
    public NioServerResponse(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * 设置异步写出回调(由事件循环注入)。
     *
     * <p>设置后 {@link #complete()} 不再直接写 channel,而是把响应头/体字节
     * 交给回调,由传输层(AIO 完成回调 / NIO OP_WRITE)驱动写出。
     * 供跨传输复用(AIO/IOCP 等 Proactor 实现)。</p>
     *
     * @param asyncWriter 异步写出回调,参数依次为响应头、响应体(体可为 null)
     */
    public void setAsyncWriter(java.util.function.BiConsumer<ByteBuffer, ByteBuffer> asyncWriter) {
        this.asyncWriter = asyncWriter;
    }

    // ─── ServerResponse 接口实现 ─────────────────────────────

    @Override
    /**
     * 设置Status
    */
    public ServerResponse setStatus(int statusCode) {
        if (ended) {
            return this;
        }
        this.statusCode = statusCode;
        return this;
    }

    @Override
    /**
     * 获取Status
    */
    public int getStatus() {
        return statusCode;
    }

    @Override
    /**
     * 设置Header
    */
    public ServerResponse setHeader(String name, String value) {
        if (ended) {
            return this;
        }
        headers.put(name, value);
        return this;
    }

    @Override
    /**
     * 获取Header
    */
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    /**
     * 获取Headers
    */
    public HttpHeader getHeaders() {
        HttpHeader h = HttpHeader.create();
        headers.forEach(h::add);
        return h;
    }

    @Override
    /**
     * 设置ContentType
    */
    public ServerResponse setContentType(String contentType) {
        return setHeader("Content-Type", contentType);
    }

    @Override
    /**
     * 获取ContentType
    */
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    /**
     * 设置Body
    */
    public ServerResponse setBody(byte[] body) {
        if (ended) {
            return this;
        }
        this.body = body;
        return this;
    }

    @Override
    /**
     * 设置Body
    */
    public ServerResponse setBody(String body) {
        if (ended) {
            return this;
        }
        this.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        return this;
    }

    @Override
    /**
     * 获取Body
    */
    public byte[] getBody() {
        return body;
    }

    @Override
    /**
     * 获取OutputStream
    */
    public OutputStream getOutputStream() {
        if (rawOutput == null) {
            rawOutput = new ByteArrayOutputStream();
        }
        return rawOutput;
    }

    @Override
    /**
     * 发送Redirect
    */
    public ServerResponse sendRedirect(String location) {
        setHeader("Location", location);
        setStatus(302);
        end();
        return this;
    }

    @Override
    /**
     * 发送记录错误
    */
    public ServerResponse sendError(int statusCode, String message) {
        setStatus(statusCode);
        setBody(message);
        end();
        return this;
    }

    @Override
    /**
     * 刷新
    */
    public void flush() {
        if (sseMode) {
            // SSE 模式下数据已直接写入 channel
        }
    }

    @Override
    /**
     * 是否Committed
    */
    public boolean isCommitted() {
        return committed;
    }

    @Override
    /**
     * 是否Ended
    */
    public boolean isEnded() {
        return ended;
    }

    @Override
    /**
     * 设置Result
    */
    public ServerResponse setResult(Object result) {
        this.result = result;
        return this;
    }

    @Override
    /**
     * 获取Result
    */
    public Object getResult() {
        return result;
    }

    @Override
    /**
     * 重置
    */
    public ServerResponse reset() {
        if (ended) {
            return this;
        }
        statusCode = 200;
        headers.clear();
        body = null;
        result = null;
        if (rawOutput != null) {
            rawOutput.reset();
        }
        return this;
    }

    @Override
    /**
     * End
    */
    public void end() {
        ended = true;
    }

    @Override
    /**
     * 写入Raw
    */
    public void writeRaw(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (sseMode) {
            writeToChannel(bytes);
            return;
        }
        if (ended) {
            return;
        }
        if (rawOutput == null) {
            rawOutput = new ByteArrayOutputStream();
        }
        rawOutput.writeBytes(bytes);
    }

    // ─── SSE ──────────────────────────────────────────────

    @Override
    /**
     * Sse
    */
    public ServerResponse sse() {
        if (committed) {
            return this;
        }
        this.sseMode = true;
        setContentType("text/event-stream; charset=utf-8");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection", "keep-alive");
        setHeader("Transfer-Encoding", "chunked");
        committed = true;
        // 立即写入 HTTP 响应头
        writeHeaders(statusCode);
        return this;
    }

    @Override
    /**
     * SseEvent
    */
    public void sseEvent(String event, String data) {
        if (!sseMode) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (event != null) {
            sb.append("event: ").append(event).append('\n');
        }
        if (data != null) {
            sb.append("data: ").append(data).append('\n');
        }
        sb.append('\n');
        byte[] frameBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        // chunked frame: hex-size CRLF data CRLF
        writeToChannel((Integer.toHexString(frameBytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        writeToChannel(frameBytes);
        writeToChannel(CRLF);
    }

    @Override
    /**
     * Sse关闭
    */
    public void sseClose() {
        if (sseMode && !ended) {
            writeToChannel(ZERO_CHUNK);
            ended = true;
        }
        channelClosed = true;
    }

    // ─── 内部方法 ─────────────────────────────────────────

    /**
     * 完成响应：将缓冲区内容写入到 channel。
     * <p>SSE 模式下只关闭 channel；缓冲模式下构建完整 HTTP/1.1 响应报文。</p>
     */
    public void complete() {
        if (sent) {
            return;
        }
        sent = true;
        if (sseMode) {
            // SSE 模式：channel 由 sseClose 关闭
            return;
        }
        if (!ended) {
            ended = true;
        }
        committed = true;
        try {
            byte[] data = resolveBody();
            byte[] headerBytes = buildHttpHeaders(data.length);
            if (asyncWriter != null) {
                // 真响应式:字节交给事件循环 OP_WRITE 异步写出
                asyncWriter.accept(ByteBuffer.wrap(headerBytes), ByteBuffer.wrap(data));
            } else {
                // gather write：将 header 和 body 一次性写出
                writeToChannel(ByteBuffer.wrap(headerBytes), ByteBuffer.wrap(data));
            }
        } catch (Exception e) {
            // 静默处理写入失败（连接可能已被客户端关闭）
        }
    }

    /**
     * zero-copy(sendfile) 发送文件:响应头走普通写入,文件体通过
     * {@link java.nio.channels.FileChannel#transferTo} 在内核态直接发送到 SocketChannel,
     * 避免文件内容经过用户态缓冲拷贝,大文件/大响应体场景显著降低 CPU 占用、提高并发吞吐。
     */
    @Override
    public ServerResponse sendFile(java.nio.file.Path file) {
        if (sent || ended) {
            return this;
        }
        sent = true;
        ended = true;
        committed = true;
        try (java.nio.channels.FileChannel fileChannel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ)) {
            long fileSize = fileChannel.size();
            if (!headers.containsKey("Content-Length")) {
                headers.put("Content-Length", String.valueOf(fileSize));
            }
            if (!headers.containsKey("Content-Type")) {
                headers.put("Content-Type", "application/octet-stream");
            }
            // 响应头先写
            writeToChannel(buildHttpHeaders((int) fileSize));
            // 文件体 zero-copy 发送
            long position = 0;
            while (position < fileSize) {
                long transferred = fileChannel.transferTo(position, fileSize - position, channel);
                if (transferred <= 0) {
                    break;
                }
                position += transferred;
            }
        } catch (java.io.IOException e) {
            channelClosed = true;
        }
        return this;
    }

    /**
     * 构建 HTTP 响应头字节（状态行 + 自动头 + 用户头 + 空行）。
     * <p>零分配快路径：echo 场景（status=200 + 无用户头）直接复用预拼字节模板，
     * 只在数字部分按 body 长度动态填充。带用户头或非默认状态才走慢路径。</p>
     * @param bodyLength 请求体长度，不允许为 null
     * @return 结果值
     */
    private byte[] buildHttpHeaders(int bodyLength) {
        // 快路径:status=200 + 无用户自定义 header + keep-alive,即 echo 场景
        if (statusCode == 200 && headers.isEmpty()) {
            return buildEchoHeader(bodyLength);
        }
        // 慢路径:自定义 header 或非 200,降级到通用构建
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reasonPhrase(statusCode)).append("\r\n");
        if (!headers.containsKey("Content-Type")) {
            sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        }
        if (!headers.containsKey("Content-Length")) {
            sb.append("Content-Length: ").append(bodyLength).append("\r\n");
        }
        if (!headers.containsKey("Connection")) {
            sb.append("Connection: keep-alive\r\n");
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * echo header 零分配构建:状态行 + Content-Type + Content-Length(<len>) + Connection: keep-alive + 空行。
     * 首次按 body 长度缓存到 {@link #ECHO_HEADER_CACHE},后续同长度直接返回。
     * @param bodyLength 请求体长度，不允许为 null
     * @return 结果值
     */
    private static byte[] buildEchoHeader(int bodyLength) {
        byte[] cached = ECHO_HEADER_CACHE.get(bodyLength);
        if (cached != null) {
            return cached;
        }
        // 数字长度字符串(无符号)
        String lenStr = String.valueOf(bodyLength);
        byte[] lenBytes = lenStr.getBytes(StandardCharsets.US_ASCII);
        // 总长度:前缀 + 数字 + 后缀
        byte[] result = new byte[STATUS_200_PREFIX.length + lenBytes.length + KEEPALIVE_SUFFIX.length];
        int pos = 0;
        System.arraycopy(STATUS_200_PREFIX, 0, result, pos, STATUS_200_PREFIX.length);
        pos += STATUS_200_PREFIX.length;
        System.arraycopy(lenBytes, 0, result, pos, lenBytes.length);
        pos += lenBytes.length;
        System.arraycopy(KEEPALIVE_SUFFIX, 0, result, pos, KEEPALIVE_SUFFIX.length);
        // putIfAbsent 防竞态;但首次构建后所有相同长度请求都直接拿到 cached
        byte[] prev = ECHO_HEADER_CACHE.putIfAbsent(bodyLength, result);
        return prev != null ? prev : result;
    }

    /**
     * 判断 channel 是否已关闭（SSE close 后）。
     * @return 是否成功（true 表示成功）
     */
    public boolean isChannelClosed() {
        return channelClosed;
    }

    /**
     * 解析Body
     * @return 结果值
     */
    private byte[] resolveBody() {
        if (body != null) {
            return body;
        }
        if (rawOutput != null && rawOutput.size() > 0) {
            byte[] result = rawOutput.toByteArray();
            rawOutput.reset();
            return result;
        }
        return EMPTY_BYTES;
    }
    /**
     * 空字节数组常量
    */
    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * 写入Headers
     * @param status 状态，不允许为 null
     */
    private void writeHeaders(int status) {
        try {
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(256);
            headerBuf.write(("HTTP/1.1 " + status + " " + reasonPhrase(status) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            for (Map.Entry<String, String> e : headers.entrySet()) {
                headerBuf.write(e.getKey().getBytes(StandardCharsets.US_ASCII));
                headerBuf.write(COLON_SP);
                headerBuf.write(e.getValue().getBytes(StandardCharsets.US_ASCII));
                headerBuf.write(CRLF);
            }
            headerBuf.write(CRLF);
            writeToChannel(headerBuf.toByteArray());
        } catch (IOException e) {
            channelClosed = true;
        }
    }

    /**
     * 流式直写钩子(非 SocketChannel 传输复用,如 AIO Proactor):
     * 设置后 {@code writeToChannel} 经由此回调输出(SSE/直写路径),
     * 优先级高于 channel 直写。
     */
    private java.util.function.Consumer<byte[]> streamWriter;

    /**
     * 设置流式直写钩子(AIO 等传输实现调用)。
     *
     * @param writer 字节消费器
     */
    public void setStreamWriter(java.util.function.Consumer<byte[]> writer) {
        this.streamWriter = writer;
    }

    /**
     * 写入ToChannel
     * @param data 数据，不允许为 null
     */
    private void writeToChannel(byte[] data) {
        // 流式钩子优先(AIO 复用):TLS 感知的阻塞写出
        if (streamWriter != null) {
            try {
                streamWriter.accept(data);
            } catch (Exception e) {
                channelClosed = true;
            }
            return;
        }
        // 无通道场景(AIO 复用,channel 为 null):直接写通道不可用,标记关闭并静默返回,
        // 正常响应路径由 asyncWriter 接管,此处仅兜底 SSE 等直写调用
        if (channelClosed || channel == null) {
            channelClosed = true;
            return;
        }
        try {
            ByteBuffer bb = ByteBuffer.wrap(data);
            while (bb.hasRemaining()) {
                int written = channel.write(bb);
                if (written < 0) {
                    channelClosed = true;
                    return;
                }
            }
        } catch (IOException e) {
            channelClosed = true;
        }
    }

    /**
     * gather write：将多个 ByteBuffer 一次性写出（zero copy，避免中间拼接）。
     * 使用 varargs 重载直接调用 channel.write(ByteBuffer[])，减少包装开销。
     * @param headerBuf 请求头Buf，不允许为 null
     * @param bodyBuf 请求体Buf，不允许为 null
     */
    private void writeToChannel(ByteBuffer headerBuf, ByteBuffer bodyBuf) {
        // 无通道场景(AIO 复用):同 writeToChannel(byte[]) 的兜底保护
        if (channelClosed || channel == null) {
            channelClosed = true;
            return;
        }
        try {
            ByteBuffer[] buffers = {headerBuf, bodyBuf};
            while (headerBuf.hasRemaining() || bodyBuf.hasRemaining()) {
                long written = channel.write(buffers);
                if (written < 0) {
                    channelClosed = true;
                    return;
                }
            }
        } catch (IOException e) {
            channelClosed = true;
        }
    }

    /**
     * ReasonPhrase
     * @param code 编码，不允许为 null
     * @return 结果字符串
     */
    private static String reasonPhrase(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }
}
