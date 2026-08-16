package com.chua.common.support.network.server.response;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.ServerSetting;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 服务器响应抽象接口。
 * <p>
 * 本接口定义与协议无关的服务器响应行为，支持同步和异步两种操作模式。
 * 所有 setter 方法返回 {@link ServerResponse} 自身，支持链式调用：
 * <pre>{@code
 * response.setStatus(200)
 *         .setContentType("application/json")
 *         .setBody("{\"ok\":true}")
 *         .end();
 * }</pre>
 * </p>
 * <p>
 * 当 {@link ServerSetting#isReactor()} 开启且服务器支持 Reactor 模式时，响应操作将变为异步非阻塞模式，
 * 并支持 SSE (Server-Sent Events) 流式写入。
 * 链式终止逻辑通过复合检查 {@link #isCommitted()} 和 {@link #isEnded()} 的状态来决定。
 * </p>
 * <h2>SSE 支持功能</h2>
 * <ul>
 *   <li>{@link #sse()}：设置 Content-Type 为 text/event-stream。</li>
 *   <li>{@link #sseEvent(String, String)}：写入一条完整的 SSE 事件帧。</li>
 *   <li>{@link #sseClose()}：发送 SSE 关闭帧并结束连接。</li>
 * </ul>
 *
 * @author CH
 * @version 2.0
 * @since 2026/07/16
 */
public interface ServerResponse {

    /**
     * 设置 HTTP 状态码。
     *
     * @param statusCode 状态码数值
     * @return 当前响应实例（链式调用）
     */
    ServerResponse setStatus(int statusCode);

    /**
     * 获取当前的 HTTP 状态码。
     *
     * @return 当前状态码
     */
    int getStatus();

    /**
     * 设置指定的响应头信息。
     *
     * @param name  响应头名称
     * @param value 响应头值
     * @return 当前响应实例（链式调用）
     */
    ServerResponse setHeader(String name, String value);

    /**
     * 获取指定名称的响应头值。
     *
     * @param name 响应头名称
     * @return 响应头值，若不存在则返回 null
     */
    String getHeader(String name);

    /**
     * 获取所有的响应头集合。
     *
     * @return HttpHeader 对象
     */
    HttpHeader getHeaders();

    /**
     * 设置响应的内容类型。
     *
     * @param contentType 内容类型字符串
     * @return 当前响应实例（链式调用）
     */
    ServerResponse setContentType(String contentType);

    /**
     * 获取当前的内容类型。
     *
     * @return 内容类型字符串
     */
    String getContentType();

    /**
     * 设置响应体为字节数组。
     *
     * @param body 响应体字节数组
     * @return 当前响应实例（链式调用）
     */
    ServerResponse setBody(byte[] body);

    /**
     * 设置响应体为字符串。
     *
     * @param body 响应体字符串
     * @return 当前响应实例（链式调用）
     */
    ServerResponse setBody(String body);

    /**
     * 获取当前的响应体字节数组。
     *
     * @return 响应体字节数组
     */
    byte[] getBody();

    /**
     * 获取用于直接写入数据的输出流。
     *
     * @return OutputStream 对象
     */
    OutputStream getOutputStream();

    /**
     * 发送重定向响应。
     *
     * @param location 重定向的目标位置 URL
     * @return 当前响应实例
     */
    ServerResponse sendRedirect(String location);

    /**
     * 发送错误响应。
     *
     * @param statusCode 错误状态码
     * @param message    错误描述信息
     * @return 当前响应实例
     */
    ServerResponse sendError(int statusCode, String message);

    /**
     * 刷新缓冲区，将数据立即写入网络。
     */
    void flush();

    /**
     * 判断响应是否已提交（即响应头是否已写入）。
     *
     * @return 如果已提交返回 true，否则返回 false
     */
    boolean isCommitted();

    /**
     * 判断响应是否已调用 end() 方法终止。
     *
     * @return 如果已终止返回 true，否则返回 false
     */
    boolean isEnded();

    /**
     * 设置返回的原始结果对象（未转换），由尾部 ResponseConverterFilter 统一转换。
     *
     * @param result bean 方法返回的原始对象
     * @return 当前响应实例（链式调用）
     */
    default ServerResponse setResult(Object result) {
        return this;
    }

    /**
     * 获取返回的原始结果对象。
     *
     * @return 原始结果对象，未设置返回 null
     */
    default Object getResult() {
        return null;
    }

    /**
     * 重置响应状态，清空所有设置以便重新使用。
     *
     * @return 当前响应实例
     */
    ServerResponse reset();

    // ==================== 终止 ====================

    /**
     * 终止过滤器链并发送当前响应内容。
     */
    void end();

    /**
     * 设置响应体字符串并终止响应。
     *
     * @param body 响应体字符串
     * @return 当前响应实例
     */
    default ServerResponse end(String body) {
        setBody(body);
        end();
        return this;
    }

    /**
     * 设置状态码和响应体字符串并终止响应。
     *
     * @param statusCode 状态码
     * @param body       响应体字符串
     * @return 当前响应实例
     */
    default ServerResponse end(int statusCode, String body) {
        setStatus(statusCode);
        setBody(body);
        end();
        return this;
    }

    /**
     * 设置响应体字节数组并终止响应。
     *
     * @param body 响应体字节数组
     * @return 当前响应实例
     */
    default ServerResponse end(byte[] body) {
        setBody(body);
        end();
        return this;
    }

    /**
     * 以 zero-copy(sendfile) 方式发送文件作为响应体,支持 Path/File 类型响应结果。
     *
     * <p>默认实现回退为读取文件字节后调用 {@link #setBody(byte[])} + {@link #end()};
     * 基于 NIO 的实现(如 {@code NioServerResponse})应覆写为 {@link java.nio.channels.FileChannel#transferTo}
     * 直接在内核态发送文件,避免用户态拷贝,显著降低大文件/大响应体的 CPU 占用。</p>
     *
     * @param file 响应文件
     * @return 当前响应实例
     */
    default ServerResponse sendFile(java.nio.file.Path file) {
        try {
            setBody(java.nio.file.Files.readAllBytes(file));
        } catch (java.io.IOException e) {
            setStatus(404);
            setBody("Not Found".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        end();
        return this;
    }

    // ==================== SSE 支持 ====================

    /**
     * 切换到 SSE (Server-Sent Events) 模式。
     * <p>
     * 此方法会自动设置 Content-Type 为 text/event-stream，
     * 并配置必要的缓存控制和连接保持头部。
     *
     * @return 当前响应实例（链式调用）
     */
    default ServerResponse sse() {
        setContentType("text/event-stream");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection", "keep-alive");
        return this;
    }

    /**
     * 写入一条 SSE 事件帧。
     * <p>
     * 根据 SSE 协议格式，分别写入 event 字段和 data 字段，最后以空行结束。
     * </p>
     *
     * @param event 事件名称，如果为 null 则不写入 event 字段
     * @param data  事件数据内容，如果为 null 则不写入 data 字段
     */
    default void sseEvent(String event, String data) {
        if (event != null) {
            writeRaw(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (data != null) {
            writeRaw(("data: " + data + "\n").getBytes(StandardCharsets.UTF_8));
        }
        writeRaw("\n".getBytes(StandardCharsets.UTF_8));
        flush();
    }

    /**
     * 发送 SSE 关闭帧。
     * <p>
     * 写入特定的关闭事件消息，刷新缓冲区，并终止响应。
     */
    default void sseClose() {
        writeRaw("event:close\ndata:closed\n\n".getBytes(StandardCharsets.UTF_8));
        flush();
        end();
    }

    /**
     * 直接写入原始字节数据到响应流中。
     * <p>
     * 此方法绕过 setBody 的提交逻辑，专门用于流式输出场景。
     * </p>
     *
     * @param bytes 需要写入的原始字节数组
     */
    void writeRaw(byte[] bytes);
}