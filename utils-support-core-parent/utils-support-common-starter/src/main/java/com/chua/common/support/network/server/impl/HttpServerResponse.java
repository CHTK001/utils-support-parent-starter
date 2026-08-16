package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.response.ServerResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 基于 JDK {@link HttpExchange} 的 {@link ServerResponse} 实现。
 *
 * <p>采用缓冲响应模式：所有写入先缓存在内存中，过滤器链完成后统一
 * 通过 {@link #complete()} 发送到客户端。{@code committed} 仅表示
 * 物理数据已发送，{@code ended} 表示逻辑处理已完成。</p>
 *
 * <p>SSE 模式下使用 {@link #sseMode} 标志位，调用 {@link #sse()} 后即提交响应头
 * （chunked transfer），后续 {@link #sseEvent(String, String)} 直接写真实流。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
public class HttpServerResponse implements ServerResponse {

    private final HttpExchange exchange;
    private int statusCode = 200;
    /**
     * 请求体
     */
    private byte[] body;
    private boolean ended;
    private boolean committed;
    private boolean sent;
    private boolean sseMode;
    private OutputStream sseOutputStream;
    /**
     * 结果
     */
    private Object result;
    private ByteArrayOutputStream output;

    public HttpServerResponse(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public ServerResponse setStatus(int code) {
        if (ended) {
            return this;
        }
        this.statusCode = code;
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
        exchange.getResponseHeaders().set(name, value);
        return this;
    }

    @Override
    public String getHeader(String name) {
        return exchange.getResponseHeaders().getFirst(name);
    }

    @Override
    public HttpHeader getHeaders() {
        HttpHeader h = HttpHeader.create();
        exchange.getResponseHeaders().forEach((k, v) -> h.add(k, String.join(",", v)));
        return h;
    }

    @Override
    public ServerResponse setContentType(String ct) {
        return setHeader("Content-Type", ct);
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public ServerResponse setBody(byte[] b) {
        if (ended) {
            return this;
        }
        this.body = b;
        return this;
    }

    @Override
    public ServerResponse setBody(String b) {
        if (ended) {
            return this;
        }
        this.body = b != null ? b.getBytes(StandardCharsets.UTF_8) : null;
        return this;
    }

    @Override
    public byte[] getBody() {
        return body;
    }

    @Override
    public OutputStream getOutputStream() {
        if (output == null) {
            output = new ByteArrayOutputStream();
        }
        return output;
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
        if (sseMode && sseOutputStream != null) {
            try {
                sseOutputStream.flush();
            } catch (IOException e) {
                throw new RuntimeException("SSE flush failed", e);
            }
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
        body = null;
        result = null;
        statusCode = 200;
        output.reset();
        exchange.getResponseHeaders().clear();
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
            try {
                if (sseOutputStream == null) {
                    // JDK 25 的 HttpServer:sendResponseHeaders(code, -1) 会创建 0 长度
                    // FixedLengthOutputStream,流式写即抛 stream closed;
                    // 传 0 才能正确按流式(无固定长度)写出 SSE 事件帧。
                    exchange.sendResponseHeaders(statusCode, 0);
                    sseOutputStream = exchange.getResponseBody();
                }
                sseOutputStream.write(bytes);
            } catch (IOException e) {
                throw new RuntimeException("SSE write failed", e);
            }
            return;
        }
        if (ended) {
            return;
        }
        if (output == null) {
            output = new ByteArrayOutputStream();
        }
        output.writeBytes(bytes);
    }

    public void complete() {
        if (sent) {
            return;
        }
        sent = true;
        if (sseMode) {
            // SSE 流生命周期完全由 sseClose() 管理：handleBlocking 的 finally 可能先于
            // 异步流式回调执行 end()（ended=true），若在此关闭流会导致回调写流报 stream closed。
            return;
        }
        if (!ended) {
            ended = true;
        }
        committed = true;
        OutputStream responseBody = null;
        try {
            byte[] data;
            if (body != null) {
                data = body;
            } else if (output != null && output.size() > 0) {
                data = output.toByteArray();
            } else {
                data = new byte[0];
            }
            if (data.length > 0) {
                exchange.sendResponseHeaders(statusCode, data.length);
                responseBody = exchange.getResponseBody();
                responseBody.write(data);
            } else {
                // 空 body 响应(302/201/204 等):JDK HttpServer 在 sendResponseHeaders(code, 0) 后
                // 若不获取并关闭响应体流,响应不会发送终止信号,客户端会一直挂起直到超时。
                // 204/304 按规范用 0(无 body),其余用 -1(chunked)并立即关闭流以终止响应。
                long len = (statusCode == 204 || statusCode == 304) ? 0 : -1;
                exchange.sendResponseHeaders(statusCode, len);
                responseBody = exchange.getResponseBody();
            }
        } catch (IOException e) {
            throw new RuntimeException("response complete failed", e);
        } finally {
            if (responseBody != null) {
                try {
                    responseBody.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void closeSseStream() {
        if (sseOutputStream != null) {
            try {
                sseOutputStream.close();
            } catch (IOException ignored) {
            }
            sseOutputStream = null;
        }
    }

    @Override
    public ServerResponse sse() {
        if (committed) {
            return this;
        }
        this.sseMode = true;
        setContentType("text/event-stream; charset=utf-8");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection", "keep-alive");
        committed = true;
        return this;
    }

    @Override
    public void sseClose() {
        if (sseMode && !ended) {
            ServerResponse.super.sseClose();
        }
        closeSseStream();
        this.sent = true;
    }
}