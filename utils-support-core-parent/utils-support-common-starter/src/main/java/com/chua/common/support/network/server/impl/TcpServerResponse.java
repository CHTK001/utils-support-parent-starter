package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
* TCP 帧式协议的 {@link ServerResponse} 实现。
*
* <p>与 {@link HttpServerResponse} 不同，本实现不直接向网络写出数据，
* 而是将所有写入缓存在内存中，通过 {@link #getReadyBytes()} 返回完整的 HTTP 响应报文字节，
* 由调用方（如 {@link JdkTcpServer}）负责加上长度头后写出。</p>
*
* <p>使用方式：</p>
* <pre>{@code
* TcpServerResponse response = new TcpServerResponse();
* response.setStatus(200)
*        .setContentType("text/plain")
*        .setBody("hello tcp");
* response.end();
* byte[] frame = response.getReadyBytes();  // 获取完整响应报文（含长度头）
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public class TcpServerResponse implements ServerResponse {

    /** 状态码，默认 200 */
    private int statusCode = 200;
    /** 响应头（大小写不敏感） */
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** 响应体 */
    private byte[] body;
    /** 是否已终止 */
    private boolean ended;
    /** 输出缓冲（OutputStream 写入路径用） */
    private ByteArrayOutputStream output;
    /** SSE 模式开关 */
    private boolean sseMode;
    /** 原始结果对象 */
    private Object result;

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
        headers.forEach((k, v) -> h.add(k, v));
        return h;
    }

    @Override
    public ServerResponse setContentType(String ct) {
        return setHeader("Content-Type", ct);
    }

    @Override
    public String getContentType() {
        return headers.get("Content-Type");
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
        this.body = b != null ? b.getBytes(StandardCharsets.UTF_8) : new byte[0];
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
        // TCP 帧模式下无持久连接，flush 无实际意义
    }

    @Override
    public boolean isCommitted() {
        return false;
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
        body = null;
        result = null;
        headers.clear();
        if (output != null) {
            output.reset();
        }
        sseMode = false;
        return this;
    }

    @Override
    public void end() {
        // 将 OutputStream 缓冲区内容合并到 body
        if (output != null && output.size() > 0) {
            if (body != null && body.length > 0) {
                ByteArrayOutputStream merged = new ByteArrayOutputStream(body.length + output.size());
                try {
                    merged.write(body);
                    output.writeTo(merged);
                    body = merged.toByteArray();
                } catch (IOException e) {
                    body = output.toByteArray();
                }
            } else {
                body = output.toByteArray();
            }
            output = null;
        }
        ended = true;
    }

    @Override
    public void writeRaw(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (output == null) {
            output = new ByteArrayOutputStream();
        }
        try {
            output.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException("writeRaw failed", e);
        }
    }

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
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            // 状态行
            String reason = getReasonPhrase(statusCode);
            buf.write(("HTTP/1.1 " + statusCode + " " + reason + "\r\n").getBytes(StandardCharsets.US_ASCII));

            // 响应头
            // Content-Length 优先使用显式设置的值，否则由 body 长度推导
            String explicitCl = headers.get("Content-Length");
            long contentLength = body != null ? body.length : 0;
            if (explicitCl == null) {
                headers.put("Content-Length", String.valueOf(contentLength));
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                buf.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }

            // 空行
            buf.write("\r\n".getBytes(StandardCharsets.US_ASCII));

            // 响应体
            if (body != null && body.length > 0) {
                buf.write(body);
            }
        } catch (IOException e) {
            throw new RuntimeException("构建 TCP 响应报文失败", e);
        }
        return buf.toByteArray();
    }

    /**
    * 获取 HTTP 标准状态码对应的标准原因短语。
    *
    * @param code 状态码
    * @return 原因短语
     */
    private static String getReasonPhrase(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            default: return "Unknown";
        }
    }
}
