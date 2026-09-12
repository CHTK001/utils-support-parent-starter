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

    /** Exchange */
    private final HttpExchange exchange;
    /** 状态代码 */
    private int statusCode = 200;
    /**
     * 请求体
     */
    private byte[] body;
    /** Ended */
    private boolean ended;
    /** Committed */
    private boolean committed;
    /** Sent */
    private boolean sent;
    /** SSE模式 */
    private boolean sseMode;
    /** SSE输出流 */
    private OutputStream sseOutputStream;
    /**
     * 结果
     */
    private Object result;
    /** 输出 */
    private ByteArrayOutputStream output;

    /**
     * 创建 HttpServerResponse 实例
     * @param exchange exchange
     */
    public HttpServerResponse(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    /** 设置Status */
    public ServerResponse setStatus(int code) {
        if (ended) {
            return this;
        }
        this.statusCode = code;
        return this;
    }

    @Override
    /** 获取Status */
    public int getStatus() {
        return statusCode;
    }

    @Override
    /** 设置Header */
    public ServerResponse setHeader(String name, String value) {
        if (ended) {
            return this;
        }
        exchange.getResponseHeaders().set(name, value);
        return this;
    }

    @Override
    /** 获取Header */
    public String getHeader(String name) {
        return exchange.getResponseHeaders().getFirst(name);
    }

    @Override
    /** 获取Headers */
    public HttpHeader getHeaders() {
        HttpHeader h = HttpHeader.create();
        exchange.getResponseHeaders().forEach((k, v) -> h.add(k, String.join(",", v)));
        return h;
    }

    @Override
    /** 设置ContentType */
    public ServerResponse setContentType(String ct) {
        return setHeader("Content-Type", ct);
    }

    @Override
    /** 获取ContentType */
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    /** 设置Body */
    public ServerResponse setBody(byte[] b) {
        if (ended) {
            return this;
        }
        this.body = b;
        return this;
    }

    @Override
    /** 设置Body */
    public ServerResponse setBody(String b) {
        if (ended) {
            return this;
        }
        this.body = b != null ? b.getBytes(StandardCharsets.UTF_8) : null;
        return this;
    }

    @Override
    /** 获取Body */
    public byte[] getBody() {
        return body;
    }

    @Override
    /** 获取OutputStream */
    public OutputStream getOutputStream() {
        if (output == null) {
            output = new ByteArrayOutputStream();
        }
        return output;
    }

    @Override
    /** 发送Redirect */
    public ServerResponse sendRedirect(String location) {
        setHeader("Location", location);
        setStatus(302);
        end();
        return this;
    }

    @Override
    /** 发送记录错误 */
    public ServerResponse sendError(int code, String msg) {
        setStatus(code);
        setBody(msg);
        end();
        return this;
    }

    @Override
    /** 刷新 */
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
    /** 是否Committed */
    public boolean isCommitted() {
        return committed;
    }

    @Override
    /** 是否Ended */
    public boolean isEnded() {
        return ended;
    }

    @Override
    /** 设置Result */
    public ServerResponse setResult(Object result) {
        this.result = result;
        return this;
    }

    @Override
    /** 获取Result */
    public Object getResult() {
        return result;
    }

    @Override
    /** 重置 */
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
    /** End */
    public void end() {
        ended = true;
    }

    @Override
    /** 写入Raw */
    public void writeRaw(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (sseMode) {
            try {
                if (sseOutputStream == null) {
                    throw new IllegalStateException("SSE not initialized, call sse() first");
                }
                sseOutputStream.write(bytes);
                sseOutputStream.flush();
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

    /** Complete */
    public void complete() {
        if (sent) {
            return;
        }
        sent = true;
        if (sseMode) {
            // SSE 流生命周期由 sseClose() 管理：JdkHttpServer 的 finally 必然调用 complete()，
            // 若在此关闭流会提前终止异步流式回调，与 NIO 实现语义保持一致。
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
                    // NOTHING
                }
            }
        }
    }

    /** 关闭SseStream */
    private void closeSseStream() {
        if (sseOutputStream != null) {
            try {
                sseOutputStream.close();
            } catch (IOException ignored) {
                // NOTHING
            }
            sseOutputStream = null;
        }
    }

    @Override
    /** Sse */
    public ServerResponse sse() {
        if (committed) {
            return this;
        }
        this.sseMode = true;
        setContentType("text/event-stream; charset=utf-8");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection", "keep-alive");
        exchange.getResponseHeaders().remove("Content-Length");
        try {
            exchange.sendResponseHeaders(200, 0);
            sseOutputStream = exchange.getResponseBody();
        } catch (IOException e) {
            throw new RuntimeException("SSE init failed", e);
        }
        committed = true;
        return this;
    }

    @Override
    /** Sse关闭 */
    public void sseClose() {
        if (sseMode && !ended) {
            ServerResponse.super.sseClose();
        }
        closeSseStream();
        this.sent = true;
    }
}