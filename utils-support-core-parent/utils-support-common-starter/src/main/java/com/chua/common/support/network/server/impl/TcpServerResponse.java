// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.nio.NioServerResponse;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import lombok.ToString;

/**
 * TCP 帧式协议的 {@link ServerResponse} 实现。
 *
 * <p>与 HTTP 服务端响应不同，本实现不直接向网络写出数据，而是将所有写入汇总缓存，
 * 通过 {@link #getReadyBytes()} 返回完整的 HTTP 响应报文字节，
 * 由调用方（如 {@link JdkTcpServer}）负责加上 4 字节长度头后写出。</p>
 *
 * <p>内部委托 {@link NioServerResponse} 构建 HTTP 响应头：
 * 设置 {@link NioServerResponse#setAsyncWriter(ByteArrayAsyncWriter)} 后，
 * {@code complete()} 把 header + body 完整字节送入 {@link #ready} 缓冲。
 * 对外报文与旧版完全一致，且额外携带一条 {@code Connection: close}：
 * TCP 帧模式中 1 帧 = 1 请求/响应，不允许连接复用。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * TcpServerResponse response = new TcpServerResponse();
 * response.setStatus(200)
 *        .setContentType("text/plain")
 *        .setBody("hello tcp");
 * response.end();
 * byte[] frame = response.getReadyBytes();  // 获取完整响应报文（不含长度头，由上层补）
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@ToString
public class TcpServerResponse implements ServerResponse,
        NioServerResponse.ByteArrayAsyncWriter,
        java.util.function.BiConsumer<java.nio.ByteBuffer, java.nio.ByteBuffer> {

    /**
     * 完整 HTTP 响应报文缓冲（状态行 + 所有头 + 空行 + 响应体）
     */
    private final ByteArrayOutputStream ready = new ByteArrayOutputStream();
    /**
     * 是否已终止（end() 之后不可再写入）
     */
    private volatile boolean ended;
    /**
     * NIO 响应层：channel 传 null（TCP 无通道概念），只走 asyncWriter 字节收集路径。
     */
    private final NioServerResponse nioResponse = new NioServerResponse(null);

    public TcpServerResponse() {
        // complete() 把 header + body 完整字节交付给本监听者
        this.nioResponse.setAsyncWriter(this);
    }

    /**
     * 接收 NIO 层 {@code complete()} 交付的 header 与 body 字节，
     * 按响应顺序追加至 {@link #ready}，它们是 TCP 帧的唯一有效内容。
     *
     * @param header 已编码的响应头（状态行 + 空行)
     * @param body   响应体，可能为空数组
     */
    @Override
    public void write(byte[] header, byte[] body) {
        if (header != null && header.length > 0) {
            ready.write(header, 0, header.length);
        }
        if (body != null && body.length > 0) {
            ready.write(body, 0, body.length);
        }
    }

    /**
     * 响应层桥接：把 NIO 层 {@code complete()} 按 {@code ByteBuffer.wrap} 送出的行/体
     * 转换回字节数组，交给 {@link #write(byte[], byte[])} 追加至 {@link #ready}。
     *
     * @param header 已编码的响应头
     * @param body   响应体
     */
    @Override
    public void accept(java.nio.ByteBuffer header, java.nio.ByteBuffer body) {
        write(toBytes(header), toBytes(body));
    }

    /**
     * 从 Buffer 中取出实际字节.
     *
     * @param buffer 缓冲
     * @return 字节数组
     */
    private static byte[] toBytes(java.nio.ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return new byte[0];
        }
        if (buffer.hasArray()) {
            return java.util.Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    @Override
    public ServerResponse setStatus(int code) {
        if (ended) {
            return this;
        }
        nioResponse.setStatus(code);
        return this;
    }

    @Override
    public int getStatus() {
        return nioResponse.getStatus();
    }

    @Override
    public ServerResponse setHeader(String name, String value) {
        if (ended) {
            return this;
        }
        nioResponse.setHeader(name, value);
        return this;
    }

    @Override
    public String getHeader(String name) {
        return nioResponse.getHeader(name);
    }

    @Override
    public HttpHeader getHeaders() {
        return nioResponse.getHeaders();
    }

    @Override
    public ServerResponse setContentType(String ct) {
        return setHeader("Content-Type", ct);
    }

    @Override
    public String getContentType() {
        return nioResponse.getContentType();
    }

    @Override
    public ServerResponse setBody(byte[] b) {
        if (ended) {
            return this;
        }
        nioResponse.setBody(b);
        return this;
    }

    @Override
    public ServerResponse setBody(String b) {
        if (ended) {
            return this;
        }
        nioResponse.setBody(b);
        return this;
    }

    @Override
    public byte[] getBody() {
        return nioResponse.getBody();
    }

    @Override
    public OutputStream getOutputStream() {
        return nioResponse.getOutputStream();
    }

    @Override
    public ServerResponse sendRedirect(String location) {
        setHeader("Location", location);
        setStatus(302);
        end();
        return this;
    }

    @Override
    public ServerResponse sendError(int code, String msg) {
        setStatus(code);
        setBody(msg);
        end();
        return this;
    }

    @Override
    public void flush() {
        // TCP 帧模式下无持久连接，flush 无实际意义
    }

    @Override
    public boolean isCommitted() {
        return nioResponse.isCommitted();
    }

    @Override
    public boolean isEnded() {
        return ended;
    }

    @Override
    public ServerResponse setResult(Object result) {
        nioResponse.setResult(result);
        return this;
    }

    @Override
    public Object getResult() {
        return nioResponse.getResult();
    }

    @Override
    public ServerResponse reset() {
        if (ended) {
            return this;
        }
        nioResponse.reset();
        ready.reset();
        ended = false;
        return this;
    }

    @Override
    public void end() {
        if (ended) {
            return;
        }
        // TCP 是 1 帧 = 1 请求/响应：无论请求是否显式要求 close，都必须回 Connection: close，
        // 否则响应将宣称连接可复用，而 TCP 层读完帧后直接关连接，会破坏对端连接模型
        nioResponse.setConnectionClose(true);
        // 标记 NIO 层已终止：complete() 构建 header + body 交付给 ready
        nioResponse.end();
        nioResponse.complete();
        ended = true;
    }

    @Override
    public void writeRaw(byte[] bytes) {
        if (ended) {
            return;
        }
        // 将原始字节合入帧体：byte[] 为 null 或空时的返回由 NIO 层处理
        nioResponse.writeRaw(bytes);
    }

    @Override
    public ServerResponse sse() {
        // TCP 是 1 帧 = 1 请求/响应：不支持 SSE 流式写出，只约定 Content-Type + 必须 close
        setContentType("text/event-stream; charset=utf-8");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection", "close");
        return this;
    }

    @Override
    public void sseEvent(String event, String data) {
        // 无 DT 实流缓冲区，直接写原始帧到帧体。
        // "data: " / "\n" 前后缀是热路径常量，避免每个分片重复 getBytes 分配。
        writeRaw(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        writeRaw(DATA_PREFIX_BYTES);
        writeRaw((data + "\n").getBytes(StandardCharsets.UTF_8));
        writeRaw(EVENT_END_BYTES);
    }

    @Override
    public void sseClose() {
        writeRaw(SSE_CLOSE_BYTES);
    }

    /**
     * SSE 分片固定前缀："data: "（只读常量，热路径复用）
     */
    private static final byte[] DATA_PREFIX_BYTES = "data: ".getBytes(StandardCharsets.UTF_8);

    /**
     * SSE 分片固定结束：空行（只读常量，热路径复用）
     */
    private static final byte[] EVENT_END_BYTES = "\n".getBytes(StandardCharsets.UTF_8);

    /**
     * SSE 关闭帧（只读常量，热路径复用）
     */
    private static final byte[] SSE_CLOSE_BYTES =
            "event:close\ndata:closed\n\n".getBytes(StandardCharsets.UTF_8);

    /**
     * 构造并返回完整的 HTTP 响应报文字节（含状态行、所有头、空行和响应体）。
     *
     * <p>调用方应将该返回值直接作为 TCP 帧体，由上层加上 4 字节长度头写出。</p>
     *
     * @return 完整的 HTTP 响应报文字节
     */
    public byte[] getReadyBytes() {
        if (!ended) {
            end();
        }
        return ready.toByteArray();
    }
}
