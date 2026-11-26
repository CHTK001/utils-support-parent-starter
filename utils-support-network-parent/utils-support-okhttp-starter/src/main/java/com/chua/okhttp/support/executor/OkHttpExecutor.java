// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.okhttp.support.executor;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import okhttp3.*;
import okhttp3.Request.Builder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 OkHttp3 的 HTTP 客户端执行器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("okhttp")
@ConditionalOnClass("okhttp3.OkHttpClient")
public class OkHttpExecutor implements HttpClientExecutor {

    /**
     * 客户端实例
     */
    private final OkHttpClient client;

    /**
     * 共享连接池：执行器内所有请求复用同一池。
     *
     * <p>旧实现在每次请求时 {@code new ConnectionPool(5, keepAlive, ...)}，
     * 连接无法跨请求复用，每次都要重新 TCP/TLS 握手，高 QPS 下开销显著。
     * 改为执行器级共享池（OkHttp 默认 5 个空闲连接、5 分钟保活）后，
     * {@code client.newBuilder()} 派生的每请求客户端会自动继承该池。</p>
     *
     * <p>注意：连接池为执行器级共享后，请求级 {@code keepAliveTimeout}
     * 不再驱动 OkHttp 连接池的保活时长。</p>
     */
    private final ConnectionPool connectionPool = new ConnectionPool();

    /**
     * 创建 okhttp执行器 实例
    */
    public OkHttpExecutor() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .connectionPool(connectionPool)
                .build();
    }

    /**
     * 获取名称
    */
    @Override
    public String getName() {
        return "okhttp";
    }

    /**
     * 是否可用
    */
    @Override
    public boolean isAvailable() {
        return ReflectUtils.forName("okhttp3.OkHttpClient") != null;
    }

    /**
     * 执行
    */
    @Override
    public ClientResponse execute(ClientRequest request) {
        try {
            okhttp3.Request okReq = toRequest(request);
            OkHttpClient reqClient = resolveClient(request);
            try (Response okResp = reqClient.newCall(okReq).execute()) {
                return toClientResponse(okResp);
            }
        } catch (IOException e) {
            throw new RuntimeException("OkHttp执行失败", e);
        }
    }

    /**
     * 在设置 HTTP 方法前完成请求体构建，并保留调用方声明的媒体类型。
     *
     * @param request 请求参数
     * @return 可交给 OkHttp 发送的请求
     */
    private Request toRequest(ClientRequest request) {
        Builder builder = new Request.Builder().url(request.getUrl());
        String contentType = null;
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().toMap().entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
                if ("Content-Type".equalsIgnoreCase(entry.getKey())) {
                    contentType = entry.getValue();
                }
            }
        }
        MediaType mediaType = MediaType.parse(contentType != null ? contentType
                : request.getBody() instanceof byte[] ? "application/octet-stream" : "text/plain; charset=utf-8");
        RequestBody body = null;
        if (request.getBody() instanceof byte[] bytes) {
            body = RequestBody.create(bytes, mediaType);
        } else if (request.getBody() != null) {
            body = RequestBody.create(request.getBody().toString(), mediaType);
        }
        String method = request.getMethod().name();
        if (body == null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "PROPPATCH".equals(method))) {
            body = RequestBody.create(new byte[0], mediaType);
        }
        return builder.method(method, body).build();
    }

    /**
     * 派生请求级配置，同时沿用执行器的连接池与调度器。
     *
     * @param request 请求参数
     * @return 具有请求级超时与代理配置的客户端
     */
    private OkHttpClient resolveClient(ClientRequest request) {
        OkHttpClient.Builder builder = client.newBuilder()
                .connectTimeout(request.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(request.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(request.getWriteTimeout(), TimeUnit.MILLISECONDS)
                .followRedirects(request.isFollowRedirects());
        if (request.getProxyHost() != null && !request.getProxyHost().isEmpty()) {
            builder.proxy(new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(request.getProxyHost(), request.getProxyPort())));
        }
        return builder.build();
    }

    /**
     * 转为客户端响应
     * @param okResp okresp
     * @return 转为客户端响应的结果
     * @throws IOException 异常
    */
    private ClientResponse toClientResponse(Response okResp) throws IOException {
        ClientResponse resp = new ClientResponse();
        resp.setStatusCode(okResp.code());
        try (ResponseBody rb = okResp.body()) {
            resp.setBody(rb != null ? rb.bytes() : new byte[0]);
        }
        okResp.headers().toMultimap().forEach((k, v) -> resp.getHeaders().add(k, String.join(", ", v)));
        return resp;
    }

    /**
     * 获取订单
    */
    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
