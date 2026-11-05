// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.ssh.support.server;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import com.chua.common.support.collection.wrap.Wraps;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.ToString;

/**
 * SSH 命令响应，{@link ServerResponse} 实现。
 * <p>将命令输出写入 SSH 客户端的输出流。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@ToString
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
    private final Map<String, String> headers = Wraps.linkedMap();

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

    /**
     * 设置状态
    */
    @Override
    public ServerResponse setStatus(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    /**
     * 获取状态
    */
    @Override
    public int getStatus() {
        return statusCode;
    }

    /**
     * 设置头部
    */
    @Override
    public ServerResponse setHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /**
     * 获取头部
    */
    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    /**
     * 获取头部
    */
    @Override
    public HttpHeader getHeaders() {
        HttpHeader result = new HttpHeader();
        for (var entry : headers.entrySet()) {
            result.add(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * 设置内容类型
    */
    @Override
    public ServerResponse setContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    /**
     * 获取内容类型
    */
    @Override
    public String getContentType() {
        return contentType;
    }

    /**
     * 设置主体
    */
    @Override
    public ServerResponse setBody(byte[] body) {
        try {
            bodyBuffer.reset();
            bodyBuffer.write(body);
        } catch (Exception e) {
            log.warn("设置响应体异常", e);
        }
        return this;
    }

    /**
     * 设置主体
    */
    @Override
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

    /**
     * 获取主体
    */
    @Override
    public byte[] getBody() {
        return bodyBuffer.toByteArray();
    }

    /**
     * 获取输出流
    */
    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * 发送Redirect
    */
    @Override
    public ServerResponse sendRedirect(String location) {
        setBody("Redirect: " + location);
        return this;
    }

    /**
     * 发送记录错误
    */
    @Override
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

    /**
     * 刷新
    */
    @Override
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

    /**
     * 是否Committed
    */
    @Override
    public boolean isCommitted() {
        return ended.get();
    }

    /**
     * 是否结束
    */
    @Override
    public boolean isEnded() {
        return ended.get();
    }

    /**
     * 设置结果
    */
    @Override
    public ServerResponse setResult(Object result) {
        this.result = result;
        return this;
    }

    /**
     * 获取结果
    */
    @Override
    public Object getResult() {
        return result;
    }

    /**
     * 重置
    */
    @Override
    public ServerResponse reset() {
        bodyBuffer.reset();
        statusCode = 200;
        contentType = "text/plain";
        headers.clear();
        result = null;
        ended.set(false);
        return this;
    }

    /**
     * 结束
    */
    @Override
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

    /**
     * 写入Raw
    */
    @Override
    public void writeRaw(byte[] bytes) {
        try {
            outputStream.write(bytes);
        } catch (Exception e) {
            log.warn("写入原始数据异常", e);
        }
    }
}