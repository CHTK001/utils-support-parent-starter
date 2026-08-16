package com.chua.httpclient.support.executor;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.spi.HttpClientExecutor;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Apache HttpClient5 的 HTTP 客户端执行器
 *
 * @author CH
 * @since 4.0.0.42
*/
@Spi("httpclient5")
@ConditionalOnClass("org.apache.hc.client5.http.classic.methods.HttpGet")
public class HttpClient5Executor implements HttpClientExecutor {

    private CloseableHttpClient client;

    public HttpClient5Executor() {
        this.client = HttpClients.createDefault();
    }

    @Override
    public String getName() {
        return "httpclient5";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.apache.hc.client5.http.classic.methods.HttpGet");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private org.apache.hc.client5.http.classic.methods.HttpUriRequestBase toRequest(ClientRequest request) {
        String method = request.getMethod().name();
        HttpUriRequestBase req = switch (method) {
            case "POST" -> new HttpPost(request.getUrl());
            case "PUT" -> new HttpPut(request.getUrl());
            case "DELETE" -> new HttpDelete(request.getUrl());
            case "HEAD" -> new HttpHead(request.getUrl());
            case "OPTIONS" -> new HttpOptions(request.getUrl());
            case "PATCH" -> new HttpPatch(request.getUrl());
            default -> new HttpGet(request.getUrl());
        };

        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> e : request.getHeaders().toMap().entrySet()) {
                req.setHeader(e.getKey(), e.getValue());
            }
        }

        if (request.getBody() != null) {
            if (request.getBody() instanceof byte[] b) {
                req.setEntity(new ByteArrayEntity(b, ContentType.DEFAULT_BINARY));
            } else {
                req.setEntity(new StringEntity(request.getBody().toString(), ContentType.DEFAULT_TEXT));
            }
        }

        RequestConfig.Builder configBuilder = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(request.getConnectTimeout(), TimeUnit.MILLISECONDS))
                .setResponseTimeout(Timeout.of(request.getReadTimeout(), TimeUnit.MILLISECONDS))
                .setRedirectsEnabled(request.isFollowRedirects());

        // 设置连接保活超时（毫秒转为秒）
        if (request.getKeepAliveTimeout() > 0) {
            configBuilder.setDefaultKeepAlive(request.getKeepAliveTimeout(), TimeUnit.MILLISECONDS);
        }

        // 配置 HTTP 代理
        if (request.getProxyHost() != null && !request.getProxyHost().isEmpty()) {
            configBuilder.setProxy(new HttpHost(request.getProxyHost(), request.getProxyPort()));
        }

        RequestConfig config = configBuilder.build();
        req.setConfig(config);

        return req;
    }

    @Override
    public ClientResponse execute(ClientRequest request) {
        try {
            if (client == null) {
                this.client = HttpClients.createDefault();
            }
            HttpUriRequestBase req = toRequest(request);
            try (CloseableHttpResponse resp = client.execute(req)) {
                return toClientResponse(resp);
            }
        } catch (Exception e) {
            throw new RuntimeException("HttpClient5执行失败", e);
        }
    }

    private ClientResponse toClientResponse(CloseableHttpResponse resp) throws Exception {
        ClientResponse cr = new ClientResponse();
        cr.setStatusCode(resp.getCode());
        if (resp.getEntity() != null) {
            cr.setBody(resp.getEntity().getContent().readAllBytes());
        }
        for (org.apache.hc.core5.http.Header h : resp.getHeaders()) {
            cr.getHeaders().add(h.getName(), h.getValue());
        }
        return cr;
    }

    @Override
    public int getOrder() { return 1; }

    @Override
    public void close() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }
}