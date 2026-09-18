package com.chua.datalake.support.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
* 数据湖-启动 提供的查询 API 客户端。
*
* <p>默认调用 {server}/query?sdl=...；后续可加更多端点。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DatalakeHttpClient {
    /**
    * 默认查询路径
    */
    private static final String DEFAULT_QUERY_PATH = "/query";

    /**
    * 请求超时（秒）
    */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /**
    * 远程服务地址
    */
    private final String baseUrl;

    /**
    * HTTP客户端 实例
    */
    private final HttpClient client;

    /**
    * 构造
    *
    * @param baseUrl 数据湖-启动 提供的 API 服务地址（如 http://localhost:8700）
    */
    public DatalakeHttpClient(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            this.baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        } else {
            this.baseUrl = baseUrl;
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();
    }

    /**
    * 发送查询请求
    *
    * @param sdl 查询 SQL
    * @return 响应体字符串
    * @throws Exception 异常
    */
    public String query(String sdl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + DEFAULT_QUERY_PATH))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(sdl))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
    * 关闭资源
    */
    public void close() {
 // Java.net.http.HTTP客户端 不需要关闭
    }
}
