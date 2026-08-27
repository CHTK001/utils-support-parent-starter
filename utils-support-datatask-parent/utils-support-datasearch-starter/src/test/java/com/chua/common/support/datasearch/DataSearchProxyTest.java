package com.chua.common.support.datasearch;

import com.chua.common.support.datasearch.network.proxy.ProxyFetcherFlow;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.net.http.HttpClient;
import java.util.List;

/**
 * 使用代理的 pansou 插件批量测试
 */
public class DataSearchProxyTest {
    
    private static int failures = 0;
    private static int passCount = 0;
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Pansou Proxy Test ===");
        
        // 获取代理
        List<String> proxies = ProxyFetcherFlow.of().fetchAll();
        System.out.println("Got " + proxies.size() + " proxies");
        if (proxies.isEmpty()) {
            System.out.println("No proxies available, using direct connection");
        } else {
            System.out.println("Sample: " + proxies.get(0));
        }
        
        // 构建带代理的 HttpClient
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.ALWAYS);
        
        if (!proxies.isEmpty()) {
            String proxy = proxies.get(0);
            String[] parts = proxy.split(":");
            clientBuilder.proxy(java.net.ProxySelector.of(
                new java.net.InetSocketAddress(parts[0], Integer.parseInt(parts[1]))
            ));
        }
        
        HttpClient client = clientBuilder.build();
        
        // 获取所有 ResourceProvider
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        java.util.Set<String> names = sp.getExtensions();
        System.out.println("\nTotal providers: " + names.size());
        
        // 测试
        for (String name : names) {
            try {
                ResourceProvider p = sp.getExtension(name);
                if (p == null) continue;
                
                VideoSearch search = new VideoSearch("测试");
                var result = p.searchResource(search);
                
                if (result != null && result.getData() != null && result.getData().getData() != null) {
                    int count = result.getData().getData().size();
                    if (count > 0) {
                        System.out.printf("[PASS] %-14s rows=%d%n", name, count);
                        passCount++;
                    } else {
                        System.out.printf("[EMPTY] %-14s rows=0%n", name);
                        failures++;
                    }
                } else {
                    System.out.printf("[FAIL] %-14s null result%n", name);
                    failures++;
                }
            } catch (Exception e) {
                System.out.printf("[ERROR] %-14s %s%n", name, e.getMessage().substring(0, Math.min(60, e.getMessage().length())));
                failures++;
            }
        }
        
        System.out.println("\n=== Summary ===");
        System.out.println("PASS: " + passCount);
        System.out.println("FAIL/EMPTY: " + failures);
        System.out.println("Total: " + (passCount + failures));
        System.exit(failures == 0 ? 0 : 1);
    }
}
