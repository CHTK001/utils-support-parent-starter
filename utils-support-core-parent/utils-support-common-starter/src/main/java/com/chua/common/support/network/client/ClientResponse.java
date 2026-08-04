package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullUnmarked;

/**
 * HTTP 客户端响应封装，包含状态码、响应头和响应体。
 *
 * <p>由底层 {@code HttpClientExecutor} 在执行完 HTTP 请求后创建并返回。
 * 提供了便捷方法用于获取响应体字符串、判断请求是否成功、获取指定响应头等。
 *
 * <p><b>典型用法：</b>
 * <pre>{@code
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .path("/users")
 *     .get();
 *
 * if (resp.isSuccess()) {
 *     String json = resp.getBodyString();
 *     System.out.println(json);
 * } else {
 *     System.err.println("请求失败，状态码: " + resp.getStatusCode());
 * }
 * }</pre>
 *
 * <p><b>字段说明：</b>
 * <ul>
 *   <li>{@link #statusCode} — HTTP 状态码，如 200、404、500 等</li>
 *   <li>{@link #headers} — 响应头集合，可通过 {@link #getHeader(String)} 按名获取</li>
 *   <li>{@link #body} — 响应体字节数组，通过 {@link #getBodyString()} 获取 UTF-8 字符串</li>
 *   <li>{@link #message} — 响应状态消息，如 "OK"、"Not Found" 等</li>
 * </ul>
 *
 * @author CH
 * @see HttpClientBuilder
 * @see com.chua.common.support.network.client.spi.HttpClientExecutor
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class ClientResponse {

    /**
     * HTTP 状态码。
     *
     * <p>标准状态码分类：
     * <ul>
     *   <li>1xx — 信息响应（如 100 Continue）</li>
     *   <li>2xx — 成功响应（如 200 OK、201 Created）</li>
     *   <li>3xx — 重定向（如 301 Moved Permanently、302 Found）</li>
     *   <li>4xx — 客户端错误（如 400 Bad Request、401 Unauthorized、404 Not Found）</li>
     *   <li>5xx — 服务端错误（如 500 Internal Server Error、502 Bad Gateway）</li>
     * </ul>
     *
     * @see HttpStatus
     */
    private int statusCode;

    /**
     * 响应头集合。
     *
     * <p>包含服务端返回的所有 HTTP 响应头，基于 {@link java.util.LinkedHashMap} 保持原始顺序。
     * 可通过 {@link #getHeader(String)} 按名称获取单个响应头值，
     * 或通过 {@link #getHeaders()} 获取整个集合。
     * 默认值为空请求头（{@code HttpHeader.create()}）。
     */
    private HttpHeader headers = HttpHeader.create();

    /**
     * 响应体字节数组。
     *
     * <p>存储服务端返回的原始二进制响应内容。支持以下场景：
     * <ul>
     *   <li>文本响应（JSON/XML/HTML） — 通过 {@link #getBodyString()} 转为 UTF-8 字符串</li>
     *   <li>二进制响应（图片/文件） — 直接使用字节数组进行后续处理</li>
     *   <li>空响应 — 值为 null，{@link #getBodyString()} 返回空字符串 {@code ""}</li>
     * </ul>
     */
    private byte[] body;

    /**
     * HTTP 响应状态消息。
     *
     * <p>随状态码返回的简短描述文本，例如：
     * <ul>
     *   <li>200 → {@code "OK"}</li>
     *   <li>404 → {@code "Not Found"}</li>
     *   <li>500 → {@code "Internal Server Error"}</li>
     * </ul>
     * 可能为空字符串或 null，取决于底层执行器的实现。
     */
    private String message;

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码，如 200、404、500
     */
    public int getStatusCode() { return statusCode; }

    /**
     * 设置 HTTP 状态码。
     *
     * @param statusCode HTTP 状态码
     */
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    /**
     * 获取所有响应头。
     *
     * @return 响应头集合，不会返回 null
     */
    public HttpHeader getHeaders() { return headers; }

    /**
     * 设置响应头集合。
     *
     * @param headers 响应头集合，传入 null 会使用空请求头
     */
    public void setHeaders(HttpHeader headers) {
        this.headers = headers != null ? headers : HttpHeader.create();
    }

    /**
     * 获取原始响应体字节数组。
     *
     * <p>直接返回服务端响应的原始二进制数据。如需获取字符串形式，请使用 {@link #getBodyString()}。
     *
     * @return 响应体字节数组，可能为 null（表示无响应体）
     */
    public byte[] getBody() { return body; }

    /**
     * 设置响应体字节数组。
     *
     * @param body 响应体字节数组
     */
    public void setBody(byte[] body) { this.body = body; }

    /**
     * 获取 HTTP 响应状态消息。
     *
     * <p>例如状态码 200 对应的消息为 {@code "OK"}。
     *
     * @return 响应状态消息字符串，可能为 null 或空字符串
     */
    public String getMessage() { return message; }

    /**
     * 设置 HTTP 响应状态消息。
     *
     * @param message 响应状态消息，如 {@code "OK"}、{@code "Not Found"}
     */
    public void setMessage(String message) { this.message = message; }

    /**
     * 获取响应体字符串（UTF-8 编码）。
     *
     * <p>将 {@link #body} 字节数组按 UTF-8 字符集解码为字符串。
     * 适用于 JSON、XML、HTML 等文本类型的响应。
     *
     * <p><b>注意：</b>如果响应体包含非 UTF-8 编码的文本（如 GBK 编码），
     * 此方法会导致乱码，请直接使用 {@link #getBody()} 自行指定字符集解码。
     *
     * @return 响应体字符串，空响应或无响应体返回空字符串 {@code ""}
     */
    public String getBodyString() {
        return body != null ? new String(body, StandardCharsets.UTF_8) : "";
    }

    /**
     * 判断请求是否成功（状态码为 2xx）。
     *
     * <p>2xx 状态码包括：
     * <ul>
     *   <li>200 — OK（成功）</li>
     *   <li>201 — Created（已创建）</li>
     *   <li>204 — No Content（无内容）</li>
     *   <li>206 — Partial Content（部分内容）</li>
     *   <li>其他 2xx 状态码</li>
     * </ul>
     *
     * <p>此方法委托给 {@link HttpStatus#isSuccess(int)} 进行判断。
     *
     * @return 状态码在 200-299 范围内返回 true，否则返回 false
     */
    public boolean isSuccess() {
        return HttpStatus.isSuccess(statusCode);
    }

    /**
     * 获取指定名称的响应头值。
     *
     * <p>响应头名称不区分大小写（具体行为取决于 {@link HttpHeader#get(String)} 的实现）。
     *
     * <p><b>使用示例：</b>
     * <pre>{@code
     * String contentType = resp.getHeader("Content-Type");
     * String auth = resp.getHeader("Authorization");
     * }</pre>
     *
     * @param name 响应头名称，如 {@code "Content-Type"}、{@code "Set-Cookie"}
     * @return 响应头值，不存在返回 null
     */
    public String getHeader(String name) {
        return headers.get(name);
    }
}
