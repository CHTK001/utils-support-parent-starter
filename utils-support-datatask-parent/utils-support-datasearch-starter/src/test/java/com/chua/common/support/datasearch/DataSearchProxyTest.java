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
    
    private static int pass = 0, empty = 0, fail = 0;
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Pansou Proxy Test ===");
        
        // 获取代理
        List<String> proxies = ProxyFetcherFlow.of().fetchAll();
        System.out.println("Got " + proxies.size() + " proxies");
        
        if (proxies.isEmpty()) {
            System.out.println("No proxies available, using direct connection");
        } else {
            System.out.println("Using first proxy: " + proxies.get(0));
        }
        
        // 获取所有 ResourceProvider
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        java.util.Set<String> names = sp.getExtensions();
        System.out.println("Total providers: " + names.size());
        
        // 测试前20个 provider
        int count = 0;
        for (String name : names) {
            if (count++ >= 30) break;
            try {
                ResourceProvider p = sp.getExtension(name);
                if (p == null) continue;
                
                VideoSearch search = new VideoSearch("电影");
                var result = p.searchResource(search);
                
                if (result != null && result.getData() != null && result.getData().getData() != null) {
                    int r = result.getData().getData().size();
                    if (r > 0) {
                        System.out.printf("[PASS] %-14s rows=%d%n", name, r);
                        pass++;
                    } else {
                        System.out.printf("[EMPTY] %-14s rows=0%n", name);
                        empty++;
                    }
                } else {
                    System.out.printf("[FAIL] %-14s null result%n", name);
                    fail++;
                }
            } catch (Exception e) {
                System.out.printf("[ERROR] %-14s %s%n", name, 
                    e.getMessage().substring(0, Math.min(50, e.getMessage().length())));
                fail++;
            }
        }
        
        System.out.println("\n=== Summary ===");
        System.out.println("PASS: " + pass);
        System.out.println("EMPTY: " + empty);
        System.out.println("FAIL/ERROR: " + fail);
    }
}
