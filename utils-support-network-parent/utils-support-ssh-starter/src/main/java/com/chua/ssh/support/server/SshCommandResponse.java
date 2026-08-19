package com.chua.ssh.support.server;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSH 命令响应，{@link ServerResponse} 实现。
 * <p>将命令输出写入 SSH 客户端的输出流。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SshCommandResponse implements ServerResponse {

    /**
     * SSH 客户端输出流
     */
    private final OutputStream outputStream;

    /**
     * 响应体缓冲区
     */
    private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

    /**
     * 响应是否已结束标记
     */
    private final AtomicBoolean ended = new AtomicBoolean(false);

    /**
     * 状态码
     */
    private int statusCode = 200;

    /**
     * 内容类型
     */
    private String contentType = "text/plain";

    /**
     * 响应头映射
     */
    private final LinkedHashMap<String, String> headers = new LinkedHashMap<>();

    /**
     * 原始结果对象
     */
    private volatile Object result;

    /**
     * 构造 SSH 命令响应。
     *
     * @param outputStream 客户端输出流
     */
    public SshCommandResponse(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    /** 设置Status */
    public ServerResponse setStatus(int statusCode) {
        this.statusCode = statusCode;
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
        headers.put(name, value);
        return this;
    }

    @Override
    /** 获取Header */
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    /** 获取Headers */
    public HttpHeader getHeaders() {
        HttpHeader result = new HttpHeader();
        for (var entry : headers.entrySet()) {
            result.add(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @Override
    /** 设置ContentType */
    public ServerResponse setContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    @Override
    /** 获取ContentType */
    public String getContentType() {
        return contentType;
    }

    @Override
    /** 设置Body */
    public ServerResponse setBody(byte[] body) {
        try {
            bodyBuffer.reset();
            bodyBuffer.write(body);
        } catch (Exception e) {
            log.warn("设置响应体异常", e);
        }
        return this;
    }

    @Override
    /** 设置Body */
    public ServerResponse setBody(String body) {
        try {
            bodyBuffer.reset();
            byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            bodyBuffer.write(bytes);
        } catch (Exception e) {
            log.warn("设置响应体异常", e);
        }
        return this;
    }

    @Override
    /** 获取Body */
    public byte[] getBody() {
        return bodyBuffer.toByteArray();
    }

    @Override
    /** 获取OutputStream */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    /** 发送Redirect */
    public ServerResponse sendRedirect(String location) {
        setBody("Redirect: " + location);
        return this;
    }

    @Override
    /** 发送记录错误 */
    public ServerResponse sendError(int statusCode, String message) {
        this.statusCode = statusCode;
        String errorMsg = "ERROR (" + statusCode + "): " + message;
        setBody(errorMsg);
        try {
            writeRaw((errorMsg + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("发送错误消息异常", e);
        }
        return this;
    }

    @Override
    /** 刷新 */
    public void flush() {
        try {
            byte[] data = bodyBuffer.toByteArray();
            if (data.length > 0) {
                outputStream.write(data);
                outputStream.flush();
                bodyBuffer.reset();
            }
        } catch (Exception e) {
            log.warn("刷新输出流异常", e);
        }
    }

    @Override
    /** 是否Committed */
    public boolean isCommitted() {
        return ended.get();
    }

    @Override
    /** 是否Ended */
    public boolean isEnded() {
        return ended.get();
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
        bodyBuffer.reset();
        statusCode = 200;
        contentType = "text/plain";
        headers.clear();
        result = null;
        ended.set(false);
        return this;
    }

    @Override
    /** End */
    public void end() {
        if (ended.compareAndSet(false, true)) {
            flush();
            try {
                outputStream.write('\n');
                outputStream.flush();
            } catch (Exception e) {
                log.warn("结束响应异常", e);
            }
        }
    }

    @Override
    /** 写入Raw */
    public void writeRaw(byte[] bytes) {
        try {
            outputStream.write(bytes);
        } catch (Exception e) {
            log.warn("写入原始数据异常", e);
        }
    }
}
