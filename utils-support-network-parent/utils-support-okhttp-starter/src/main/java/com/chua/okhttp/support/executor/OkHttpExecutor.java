package com.chua.okhttp.support.executor;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.spi.HttpClientExecutor;
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

    /** 创建 OkHttpExecutor 实例 */
    public OkHttpExecutor() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    /** 获取Name */
    public String getName() {
        return "okhttp";
    }

    @Override
    /** 是否Available */
    public boolean isAvailable() {
        try {
            ReflectUtils.forName("okhttp3.OkHttpClient");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    /** 执行 */
    public ClientResponse execute(ClientRequest request) {
        try {
            Builder builder = new Request.Builder().url(request.getUrl());
            builder.method(request.getMethod().name(), null);

            if (request.getHeaders() != null) {
                for (Map.Entry<String, String> e : request.getHeaders().toMap().entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                }
            }

            RequestBody body = null;
            if (request.getBody() != null) {
                if (request.getBody() instanceof byte[] b) {
                    body = RequestBody.create(b, MediaType.parse("application/octet-stream"));
                } else {
                    String s = request.getBody().toString();
                    body = RequestBody.create(s, MediaType.parse("text/plain; charset=utf-8"));
                }
            }

            if (body == null && ("POST".equals(request.getMethod().name()) || "PUT".equals(request.getMethod().name()))) {
                body = RequestBody.create("", MediaType.parse("text/plain"));
            }

            OkHttpClient.Builder clientBuilder = client.newBuilder()
                    .connectTimeout(request.getConnectTimeout(), TimeUnit.MILLISECONDS)
                    .readTimeout(request.getReadTimeout(), TimeUnit.MILLISECONDS)
                    .followRedirects(request.isFollowRedirects())
                    .connectionPool(new okhttp3.ConnectionPool(
                            5, request.getKeepAliveTimeout(), TimeUnit.MILLISECONDS));

            // 配置 HTTP 代理
            if (request.getProxyHost() != null && !request.getProxyHost().isEmpty()) {
                clientBuilder.proxy(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(request.getProxyHost(), request.getProxyPort())));
            }

            OkHttpClient reqClient = clientBuilder.build();

            okhttp3.Request okReq = builder.build();
            try (Response okResp = reqClient.newCall(okReq).execute()) {
                return toClientResponse(okResp);
            }
        } catch (IOException e) {
            throw new RuntimeException("OkHttp执行失败", e);
        }
    }

    /** ToClientResponse */
    private ClientResponse toClientResponse(Response okResp) throws IOException {
        ClientResponse resp = new ClientResponse();
        resp.setStatusCode(okResp.code());
        try (ResponseBody rb = okResp.body()) {
            resp.setBody(rb != null ? rb.bytes() : new byte[0]);
        }
        okResp.headers().toMultimap().forEach((k, v) -> resp.getHeaders().add(k, String.join(", ", v)));
        return resp;
    }

    @Override
    /** 获取Order */
    public int getOrder() { return 0; }

    @Override
    /** 关闭 */
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}