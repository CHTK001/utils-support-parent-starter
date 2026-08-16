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
 * @author CH
 * @since 2026/08/12
 */
public class NioServerResponse implements ServerResponse {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] COLON_SP = {':', ' '};
    private static final byte[] ZERO_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final SocketChannel channel;
    private int statusCode = 200;
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private byte[] body;
    private boolean ended;
    private boolean committed;
    private boolean sent;
    private boolean sseMode;
    private boolean channelClosed;
    private Object result;
    private ByteArrayOutputStream rawOutput;

    /**
     * 异步写出回调(真响应式):事件循环设置后,complete() 不再直接写 channel,
     * 而是把响应头/体字节交给回调,由事件循环通过 OP_WRITE 驱动写出。
     */
    private java.util.function.BiConsumer<ByteBuffer, ByteBuffer> asyncWriter;

    public NioServerResponse(SocketChannel channel) {
        this.channel = channel;
    }

    /** 设置异步写出回调(由事件循环注入)。 */
    void setAsyncWriter(java.util.function.BiConsumer<ByteBuffer, ByteBuffer> asyncWriter) {
        this.asyncWriter = asyncWriter;
    }

    // ─── ServerResponse 接口实现 ─────────────────────────────

    @Override
    public ServerResponse setStatus(int statusCode) {
        if (ended) {
            return this;
        }
        this.statusCode = statusCode;
        return this;
    }

    @Override
    public int getStatus() {
        return statusCode;
    }

    @Override
    public ServerResponse setHeader(String name, String value) {
        if (ended) {
            return this;
        }
        headers.put(name, value);
        return this;
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public HttpHeader getHeaders() {
        HttpHeader h = HttpHeader.create();
        headers.forEach(h::add);
        return h;
    }

    @Override
    public ServerResponse setContentType(String contentType) {
        return setHeader("Content-Type", contentType);
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public ServerResponse setBody(byte[] body) {
        if (ended) {
            return this;
        }
        this.body = body;
        return this;
    }

    @Override
    public ServerResponse setBody(String body) {
        if (ended) {
            return this;
        }
        this.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        return this;
    }

    @Override
    public byte[] getBody() {
        return body;
    }

    @Override
    public OutputStream getOutputStream() {
        if (rawOutput == null) {
            rawOutput = new ByteArrayOutputStream();
        }
        return rawOutput;
    }

    @Override
    public ServerResponse sendRedirect(String location) {
        setHeader("Location", location);
        setStatus(302);
        end();
        return this;
    }

    @Override
    public ServerResponse sendError(int statusCode, String message) {
        setStatus(statusCode);
        setBody(message);
        end();
        return this;
    }

    @Override
    public void flush() {
        if (sseMode) {
            // SSE 模式下数据已直接写入 channel
        }
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public boolean isEnded() {
        return ended;
    }

    @Override
    public ServerResponse setResult(Object result) {
        this.result = result;
        return this;
    }

    @Override
    public Object getResult() {
        return result;
    }

    @Override
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
    public void end() {
        ended = true;
    }

    @Override
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
    public void sseEvent(String event, String data) {
        if (!sseMode) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (event != null) {
            sb.append("event:").append(event).append('\n');
        }
        if (data != null) {
            sb.append("data:").append(data).append('\n');
        }
        sb.append('\n');
        byte[] frameBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        // chunked frame: hex-size CRLF data CRLF
        writeToChannel((Integer.toHexString(frameBytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        writeToChannel(frameBytes);
        writeToChannel(CRLF);
    }

    @Override
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
    void complete() {
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
            // 响应头与响应体分别包装为 ByteBuffer，gather write 一次写出，避免拼接拷贝
            ByteBuffer headerBuf = ByteBuffer.wrap(buildHttpHeaders(data.length));
            ByteBuffer bodyBuf = ByteBuffer.wrap(data);
            if (asyncWriter != null) {
                // 真响应式:字节交给事件循环 OP_WRITE 异步写出
                asyncWriter.accept(headerBuf, bodyBuf);
            } else {
                writeToChannel(headerBuf, bodyBuf);
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
            writeToChannel(ByteBuffer.wrap(buildHttpHeaders((int) fileSize)));
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
     */
    private byte[] buildHttpHeaders(int bodyLength) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reasonPhrase(statusCode)).append("\r\n");
        // Auto headers
        if (!headers.containsKey("Content-Type")) {
            sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        }
        if (!headers.containsKey("Content-Length")) {
            sb.append("Content-Length: ").append(bodyLength).append("\r\n");
        }
        if (!headers.containsKey("Connection")) {
            sb.append("Connection: keep-alive\r\n");
        }
        // User headers
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 判断 channel 是否已关闭（SSE close 后）。
     */
    boolean isChannelClosed() {
        return channelClosed;
    }

    private byte[] resolveBody() {
        if (body != null) {
            return body;
        }
        if (rawOutput != null && rawOutput.size() > 0) {
            return rawOutput.toByteArray();
        }
        return new byte[0];
    }

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

    private void writeToChannel(byte[] data) {
        if (channelClosed) {
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
     */
    private void writeToChannel(ByteBuffer... buffers) {
        if (channelClosed) {
            return;
        }
        try {
            // 过滤空缓冲，全部写完为止（阻塞通道 write 通常一次完成，循环兜底部分写入）
            while (true) {
                boolean allDone = true;
                for (ByteBuffer bb : buffers) {
                    if (bb.hasRemaining()) {
                        channel.write(bb);
                        if (bb.hasRemaining()) {
                            allDone = false;
                        }
                    }
                }
                if (allDone) {
                    return;
                }
            }
        } catch (IOException e) {
            channelClosed = true;
        }
    }

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
