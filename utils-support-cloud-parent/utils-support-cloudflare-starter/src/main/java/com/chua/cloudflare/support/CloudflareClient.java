package com.chua.cloudflare.support;

import com.chua.common.support.exception.AuthenticationException;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Cloudflare API v4 客户端。
 *
 * <p>封装 Cloudflare REST API 公共入口，承载认证头注入、超时控制与错误解析。
   * D1 sqlite 访问路径：
 * {@code POST /accounts/{account_id}/d1/database/{db_id}/query}</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CloudflareClient {

    /**
     * 配置
     */
    private final CloudflareConfig config;

    /**
      * HTTP 客户端（复用 common-starter 的 HTTP客户端 抽象）
     */
    private final HttpClient httpClient;

    /**
     * 用配置构造客户端。
     *
     * @param config 配置
     */
    public CloudflareClient(CloudflareConfig config) {
        this(config, HttpClientFactory.getClient());
    }

    /**
     * 用配置和自定义 HTTP 客户端构造。
     *
     * @param config     配置
     * @param httpClient HTTP 客户端
     */
    public CloudflareClient(CloudflareConfig config, HttpClient httpClient) {
        if (config == null) {
            throw new IllegalArgumentException("CloudflareConfig must not be null");
        }
        if (config.getToken() == null || config.getToken().isEmpty()) {
            throw new AuthenticationException("Cloudflare token is missing");
        }
        this.config = config;
        this.httpClient = httpClient;
    }

    /**
     * 执行 Cloudflare API 调用，返回响应 {@code result} 字段。
     *
     * @param method  HTTP 方法
     * @param path    API 路径（不含 baseurl），如 {@code "/accounts/{aid}/d1/database/{标识}/查询"}
     * @param payload 请求体（可空），将被序列化为 JSON
     * @return Cloudflare 响应 {@code result} 字段
     */
    public Object call(HttpMethod method, String path, Object payload) {
        ClientRequest request = ClientRequest.of(config.getBaseUrl() + path, method);
        request.setHeaders(headers());
        if (payload != null) {
            request.setBody(Json.toJson(payload));
        }
        ClientResponse response = httpClient.execute(request);
        int status = response.getStatusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException("Cloudflare API " + method + " " + path
                    + " failed: status=" + status
                    + ", body=" + response.getBodyString());
        }
        String body = response.getBodyString();
        if (body == null || body.isEmpty()) {
            return null;
        }
        return parseResult(body);
    }

    /**
      * 获取 请求。
     *
     * @param path API 路径
     * @return 响应 结果 字段
     */
    public Object get(String path) {
        return call(HttpMethod.GET, path, null);
    }

    /**
     * POST 请求。
     *
     * @param path    API 路径
     * @param payload 请求体
     * @return 响应 结果 字段
     */
    public Object post(String path, Object payload) {
        return call(HttpMethod.POST, path, payload);
    }

    /**
      * 构建带 Bearer 令牌 的请求头。
     *
     * @return 头信息
     */
    private HttpHeader headers() {
        HttpHeader header = HttpHeader.create();
        header.add("Authorization", "Bearer " + config.getToken());
        header.add("Content-Type", "application/json");
        return header;
    }

    /**
     * 解析 Cloudflare API v4 响应，提取 {@code result} 字段。
     *
     * <p>响应格式：{@code {"success":true, "result":...}} 或失败时含 {@code errors}。</p>
     *
     * @param body 响应体 JSON 字符串
     * @return result 字段（对象/映射/列表 等）
     */
    @SuppressWarnings("unchecked")
    private Object parseResult(String body) {
        Map<String, Object> root = Json.fromJson(body, Map.class);
        if (root == null) {
            return null;
        }
        Object success = root.get("success");
        if (Boolean.FALSE.equals(success)) {
            throw new RuntimeException("Cloudflare API returned errors: " + root.get("errors"));
        }
        return root.get("result");
    }

    /**
     * 获取配置。
     *
     * @return 配置
     */
    public CloudflareConfig getConfig() {
        return config;
    }
}