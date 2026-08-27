package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.network.proxy.ProxyFetcherFlow;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 带代理的 HttpClient 工具
 */
public class ProxyHttpClient {

    private static volatile HttpClient client;

    public static HttpClient get() {
        if (client == null) {
            synchronized (ProxyHttpClient.class) {
                if (client == null) {
                    HttpClient.Builder builder = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .followRedirects(HttpClient.Redirect.NORMAL);
                    try {
                        String proxy = ProxyFetcherFlow.of().roundRobin().fetchOne();
                        if (proxy != null && proxy.contains(":")) {
                            String[] parts = proxy.split(":");
                            builder.proxy(ProxySelector.of(new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))));
                        }
                    } catch (Exception ignored) {}
                    client = builder.build();
                }
            }
        }
        return client;
    }
}
