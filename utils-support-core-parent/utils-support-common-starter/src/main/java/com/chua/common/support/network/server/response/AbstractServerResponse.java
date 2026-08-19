package com.chua.common.support.network.server.response;

import com.chua.common.support.network.http.HttpHeader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 服务器响应抽象基类，提供 {@link ServerResponse} 常用方法的默认实现。
 *
 * <p>管理状态码、响应头、响应体、已提交/已终止等通用状态。
 * 子类只需实现协议特定的输出方法（如 {@link #getOutputStream()}、{@link #writeRaw(byte[])} 等）。
 *
 * <h2>状态管理</h2>
 * <ul>
 *   <li>{@link #committed} — 响应头是否已发送</li>
 *   <li>{@link #ended} — 是否已调用 {@link #end()} 终止</li>
 * </ul>
 * 过滤器链通过检查这两个状态决定是否继续执行。
 *
 * @since 2026/07/16
 */
public abstract class AbstractServerResponse implements ServerResponse {

    /** HTTP 状态码 */
    /** 状态代码 */
    protected int statusCode = 200;

    /** 响应头集合 */
    /** Headers */
    protected HttpHeader headers = HttpHeader.create();

    /**
     * 请求体
     */
    protected byte[] body;

    /** 是否已提交响应头 */
    /** Committed */
    protected boolean committed;

    /** 是否已调用 end() 终止 */
    /** Ended */
    protected boolean ended;

    @Override
    public ServerResponse setStatus(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    @Override
    public int getStatus() {
        return statusCode;
    }

    @Override
    public ServerResponse setHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public HttpHeader getHeaders() {
        return headers;
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
        this.body = body;
        return this;
    }

    @Override
    public ServerResponse setBody(String body) {
        this.body = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        return this;
    }

    @Override
    public byte[] getBody() {
        return body;
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
        // 默认空实现，子类按需覆盖
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
    public ServerResponse reset() {
        this.body = null;
        this.statusCode = 200;
        this.headers = HttpHeader.create();
        this.committed = false;
        this.ended = false;
        return this;
    }

    @Override
    public void end() {
        this.ended = true;
        this.committed = true;
    }

    /**
     * 子类实现：获取底层输出流。
     *
     * @return 输出流
     */
    @Override
    public abstract OutputStream getOutputStream();

    /**
     * 子类实现：直接写入原始字节数据。
     *
     * @param bytes 原始字节数组
     */
    @Override
    public abstract void writeRaw(byte[] bytes);
}
