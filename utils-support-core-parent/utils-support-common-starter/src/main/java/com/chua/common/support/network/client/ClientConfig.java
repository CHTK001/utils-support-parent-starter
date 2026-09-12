package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP 客户端预配置构建器，由 {@link HttpClient} 的便利方法创建。
 *
 * <p>本类用于在指定 HTTP 方法和 URL 之前，预先设置请求头、超时、缓存、重试等通用配置。
 * 通过 {@link HttpClient#json()}、{@link HttpClient#auth(String)} 等便利方法创建后，
 * 链式设置更多配置，最后调用 {@code get/post/put/delete} 等方法指定 URL 并返回 {@link RequestSpec}。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * HttpClient client = HttpClientFactory.getClient();
 *
 * // JSON 请求
 * ClientResponse resp = client.json()
 *     .post("http://api.example.com/users")
 *     .body("{\"name\":\"test\"}")
 *     .execute();
 *
 * // 带认证的请求
 * ClientResponse resp = client.auth("jwt-token")
 *     .accept("application/json")
 *     .get("http://api.example.com/users")
 *     .cache(60000)
 *     .execute();
 *
 * // 表单提交
 * ClientResponse resp = client.form()
 *     .post("http://api.example.com/login")
 *     .body("username=admin&password=123")
 *     .execute();
 *
 * // Basic 认证 + JSON
 * ClientResponse resp = client.authBasic("admin", "secret")
 *     .json()
 *     .post("http://api.example.com/data")
 *     .body(jsonPayload)
 *     .execute();
 *
 * // 全链路配置
 * ClientResponse resp = client.json()
 *     .auth("token")
 *     .timeout(5000)
 *     .retry(3)
 *     .cache(30000)
 *     .get("http://api.example.com/config")
 *     .execute();
 * }</pre>
 *
 * @author CH
 * @see HttpClient
 * @see RequestSpec
 * @since 4.0
 */
public class ClientConfig {

    /**
     * 绑定的 HTTP 客户端
     */
    private final HttpClient client;

    /**
     * 预配置的请求头
     */
    private final HttpHeader headers = HttpHeader.create();

    /**
     * 预配置的连接超时（毫秒），-1 表示未设置
     */
    private long connectTimeout = -1;

    /**
     * 预配置的读取超时（毫秒），-1 表示未设置
     */
    private long readTimeout = -1;

    /**
     * 预配置的写入超时（毫秒），-1 表示未设置
     */
    private long writeTimeout = -1;

    /**
     * 预配置的缓存有效期（毫秒），-1 表示未设置
     */
    private long cacheTtl = -1;

    /**
     * 预配置的重试次数，-1 表示未设置
     */
    private int maxRetries = -1;

    /**
     * 预配置的 HTTP 版本
     */
    private HttpVersion version;

    /**
     * 创建绑定到指定客户端的预配置构建器。
     *
     * @param client 绑定的 HTTP 客户端
     */
    ClientConfig(HttpClient client) {
        this.client = client;
    }

    // ==================== 请求头快捷方法 ====================

    /**
     * 快捷设置 Content-Type 为 {@code application/json}。
     *
     * @return 当前实例（链式调用）
     */
    public ClientConfig json() {
        this.headers.add("Content-Type", "application/json");
        return this;
    }

    /**
     * 快捷设置 Content-Type 为 {@code application/x-www-form-urlencoded}。
     *
     * @return 当前实例（链式调用）
     */
    public ClientConfig form() {
        this.headers.add("Content-Type", "application/x-www-form-urlencoded");
        return this;
    }

    /**
     * 设置 Content-Type 请求头。
     *
     * @param mimeType MIME 类型
     * @return 当前实例（链式调用）
     */
    public ClientConfig contentType(String mimeType) {
        this.headers.add("Content-Type", mimeType);
        return this;
    }

    /**
     * 设置 Authorization 为 Bearer Token。
     *
     * <p>等效于 {@code header("Authorization", "Bearer " + token)}。</p>
     *
     * @param token Bearer Token 字符串
     * @return 当前实例（链式调用）
     */
    public ClientConfig auth(String token) {
        this.headers.add("Authorization", "Bearer " + token);
        return this;
    }

    /**
     * 设置 Authorization 为 Bearer Token（auth 的别名）。
     *
     * @param token Bearer Token 字符串
     * @return 当前实例（链式调用）
     */
    public ClientConfig authorization(String token) {
        return auth(token);
    }

    /**
     * 设置 Authorization 为 HTTP Basic 认证。
     *
     * @param username 认证用户名
     * @param password 认证密码
     * @return 当前实例（链式调用）
     */
    public ClientConfig authBasic(String username, String password) {
        String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.headers.add("Authorization", "Basic " + encoded);
        return this;
    }

    /**
     * 添加单个请求头。
     *
     * @param name  请求头名称
     * @param value 请求头值
     * @return 当前实例（链式调用）
     */
    public ClientConfig header(String name, String value) {
        this.headers.add(name, value);
        return this;
    }

    /**
     * 批量添加请求头。
     *
     * @param headers 请求头 Map
     * @return 当前实例（链式调用）
     */
    public ClientConfig headers(Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(this.headers::add);
        }
        return this;
    }

    /**
     * 设置 Accept 请求头。
     *
     * @param mimeType 期望的 MIME 类型
     * @return 当前实例（链式调用）
     */
    public ClientConfig accept(String mimeType) {
        this.headers.add("Accept", mimeType);
        return this;
    }

    /**
     * 设置 User-Agent 请求头。
     *
     * @param userAgent User-Agent 字符串
     * @return 当前实例（链式调用）
     */
    public ClientConfig userAgent(String userAgent) {
        this.headers.add("User-Agent", userAgent);
        return this;
    }

    /**
     * 添加 Cookie 请求头。
     *
     * @param name  Cookie 名称
     * @param value Cookie 值
     * @return 当前实例（链式调用）
     */
    public ClientConfig cookie(String name, String value) {
        String existing = this.headers.get("Cookie");
        String cookieEntry = name + "=" + value;
        if (existing != null && !existing.isEmpty()) {
            this.headers.add("Cookie", existing + "; " + cookieEntry);
        } else {
            this.headers.add("Cookie", cookieEntry);
        }
        return this;
    }

    /**
     * 设置 Cache-Control 为 {@code no-cache}。
     *
     * @return 当前实例（链式调用）
     */
    public ClientConfig noCache() {
        this.headers.add("Cache-Control", "no-cache");
        return this;
    }

    /**
     * 设置 Referer 请求头。
     *
     * @param referer 来源页面 URL
     * @return 当前实例（链式调用）
     */
    public ClientConfig referer(String referer) {
        this.headers.add("Referer", referer);
        return this;
    }

    // ==================== 通用配置方法 ====================

    /**
     * 同时设置连接超时和读取超时。
     *
     * @param timeoutMs 超时时间（毫秒）
     * @return 当前实例（链式调用）
     */
    public ClientConfig timeout(long timeoutMs) {
        this.connectTimeout = timeoutMs;
        this.readTimeout = timeoutMs;
        return this;
    }

    /**
     * 设置连接超时时间。
     *
     * @param timeout 连接超时（毫秒）
     * @return 当前实例（链式调用）
     */
    public ClientConfig connectTimeout(long timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    /**
     * 设置读取超时时间。
     *
     * @param timeout 读取超时（毫秒）
     * @return 当前实例（链式调用）
     */
    public ClientConfig readTimeout(long timeout) {
        this.readTimeout = timeout;
        return this;
    }

    /**
     * 设置写入超时时间。
     *
     * @param timeout 写入超时（毫秒）
     * @return 当前实例（链式调用）
     */
    public ClientConfig writeTimeout(long timeout) {
        this.writeTimeout = timeout;
        return this;
    }

    /**
     * 设置响应缓存有效期。
     *
     * @param ttlMs 缓存有效期（毫秒）
     * @return 当前实例（链式调用）
     */
    public ClientConfig cache(long ttlMs) {
        this.cacheTtl = ttlMs;
        return this;
    }

    /**
     * 设置最大重试次数。
     *
     * @param retries 最大重试次数
     * @return 当前实例（链式调用）
     */
    public ClientConfig retry(int retries) {
        this.maxRetries = retries;
        return this;
    }

    /**
     * 设置 HTTP 协议版本。
     *
     * @param version HTTP 版本
     * @return 当前实例（链式调用）
     */
    public ClientConfig version(HttpVersion version) {
        this.version = version;
        return this;
    }

    // ==================== HTTP 方法入口（创建 RequestSpec） ====================

    /**
     * 创建 GET 请求的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param url 请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec get(String url) {
        return applyTo(new RequestSpec(client, url, HttpMethod.GET));
    }

    /**
     * 创建 POST 请求的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param url 请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec post(String url) {
        return applyTo(new RequestSpec(client, url, HttpMethod.POST));
    }

    /**
     * 创建 PUT 请求的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param url 请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec put(String url) {
        return applyTo(new RequestSpec(client, url, HttpMethod.PUT));
    }

    /**
     * 创建 DELETE 请求的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param url 请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec delete(String url) {
        return applyTo(new RequestSpec(client, url, HttpMethod.DELETE));
    }

    /**
     * 创建 PATCH 请求的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param url 请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec patch(String url) {
        return applyTo(new RequestSpec(client, url, HttpMethod.PATCH));
    }

    /**
     * 创建 HEAD 请求的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param url 请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec head(String url) {
        return applyTo(new RequestSpec(client, url, HttpMethod.HEAD));
    }

    /**
     * 创建 OPTIONS 请求的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param url 请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec options(String url) {
        return applyTo(new RequestSpec(client, url, HttpMethod.OPTIONS));
    }

    /**
     * 创建指定 HTTP 方法的 {@link RequestSpec}，自动应用所有预配置。
     *
     * @param method HTTP 方法
     * @param url    请求 URL
     * @return 预配置好的 RequestSpec
     */
    public RequestSpec method(HttpMethod method, String url) {
        return applyTo(new RequestSpec(client, url, method));
    }

    // ==================== 内部方法 ====================

    /**
     * 将预配置应用到 {@link RequestSpec}。
     *
     * @param spec 目标 RequestSpec
     * @return 应用预配置后的 RequestSpec
     */
    private RequestSpec applyTo(RequestSpec spec) {
        // 应用预配置的请求头
        for (Map.Entry<String, String> entry : headers.toMap().entrySet()) {
            spec.header(entry.getKey(), entry.getValue());
        }
        // 应用预配置的超时
        if (connectTimeout >= 0) {
            spec.connectTimeout(connectTimeout);
        }
        if (readTimeout >= 0) {
            spec.readTimeout(readTimeout);
        }
        if (writeTimeout >= 0) {
            spec.writeTimeout(writeTimeout);
        }
        // 应用预配置的缓存
        if (cacheTtl >= 0) {
            spec.cache(cacheTtl);
        }
        // 应用预配置的重试
        if (maxRetries >= 0) {
            spec.retry(maxRetries);
        }
        // 应用预配置的 HTTP 版本
        if (version != null) {
            spec.version(version);
        }
        return spec;
    }
}
