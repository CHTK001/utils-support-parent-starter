package com.chua.common.support.datasearch;

import com.chua.common.support.datasearch.network.proxy.ProxyFetcherFlow;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.spi.ServiceProvider;

import java.net.http.HttpClient;
import java.util.*;

/**
 * 使用代理的 pansou 插件批量测试 + 被封站点标记
 */
public class DataSearchProxyTest {
    
    private static int pass = 0, empty = 0, fail = 0, blocked = 0;
    private static final List<String> BLOCKED_PROVIDERS = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Pansou Proxy Test ===");
        
        // 获取代理
        List<String> proxies = ProxyFetcherFlow.of().fetchAll();
        System.out.println("Got " + proxies.size() + " proxies from all sources");
        
        // 获取所有 ResourceProvider
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        java.util.Set<String> names = sp.getExtensions();
        System.out.println("Total providers: " + names.size());
        
        // 只测试视频类 provider
        List<String> videoNames = new ArrayList<>();
        for (String name : names) {
            String lower = name.toLowerCase();
            if (lower.contains("bili") || lower.contains("douban") || lower.contains("wanou") 
                || lower.contains("wuji") || lower.contains("quark") || lower.contains("czzy")
                || lower.contains("pan") || lower.contains("jike") || lower.contains("hunhe")
                || lower.contains("shandian") || lower.contains("labi") || lower.contains("duoduo")
                || lower.contains("miliao") || lower.contains("yinhua") || lower.contains("dyyj")
                || lower.contains("ddys") || lower.contains("libvio") || lower.contains("panta")
                || lower.contains("pan666") || lower.contains("hdr4k") || lower.contains("aikanzy")
                || lower.contains("ash") || lower.contains("bixin") || lower.contains("cyg")
                || lower.contains("daishudj") || lower.contains("duanjuw") || lower.contains("erxiao")
                || lower.contains("feikuai") || lower.contains("fox4k") || lower.contains("gaoqing888")
                || lower.contains("gying") || lower.contains("haisou") || lower.contains("hdmoli")
                || lower.contains("ikantv") || lower.contains("jutoushe") || lower.contains("jisu")
                || lower.contains("kks") || lower.contains("kuai") || lower.contains("libvio")
                || lower.contains("mayipan") || lower.contains("pancheck") || lower.contains("panduoduo")
                || lower.contains("panhome") || lower.contains("panmao") || lower.contains("panshen")
                || lower.contains("panxiansen") || lower.contains("panyou") || lower.contains("panyunso")
                || lower.contains("quarksoo") || lower.contains("quarktv") || lower.contains("qupansou")
                || lower.contains("souci") || lower.contains("soupan") || lower.contains("sousou")
                || lower.contains("u3c3") || lower.contains("wypanso") || lower.contains("xunlei")
                || lower.contains("ypfxw") || lower.contains("yuhuage") || lower.contains("yunpan")
                || lower.contains("yunso") || lower.contains("zhihuipan")) {
                videoNames.add(name);
            }
        }
        System.out.println("Video providers: " + videoNames.size());
        
        // 测试每个 provider (先直连，再用代理)
        for (String name : videoNames) {
            try {
                ResourceProvider p = sp.getExtension(name);
                if (p == null) continue;
                
                // 直连测试
                boolean directOk = testProvider(name, p, null);
                
                // 代理测试
                boolean proxyOk = false;
                String proxyUsed = "";
                if (!directOk && !proxies.isEmpty()) {
                    for (String proxy : proxies.subList(0, Math.min(5, proxies.size()))) {
                        if (testProviderWithProxy(name, p, proxy)) {
                            proxyOk = true;
                            proxyUsed = proxy;
                            break;
                        }
                    }
                }
                
                if (directOk || proxyOk) {
                    System.out.printf("[PASS] %-14s (direct=%s, proxy=%s)%n", name, directOk, proxyOk ? proxyUsed : "");
                    pass++;
                } else {
                    System.out.printf("[BLOCKED] %-14s%n", name);
                    BLOCKED_PROVIDERS.add(name);
                    blocked++;
                }
            } catch (Exception e) {
                System.out.printf("[ERROR] %-14s %s%n", name, e.getMessage().substring(0, Math.min(40, e.getMessage().length())));
                fail++;
            }
        }
        
        System.out.println("\n=== Summary ===");
        System.out.println("PASS: " + pass);
        System.out.println("BLOCKED: " + blocked);
        System.out.println("FAIL: " + fail);
        System.out.println("\nBlocked providers: " + BLOCKED_PROVIDERS);
    }
    
    private static boolean testProvider(String name, ResourceProvider p, String proxy) {
        try {
            VideoSearch search = new VideoSearch("电影");
            var result = p.searchResource(search);
            
            if (result != null && result.getData() != null && result.getData().getData() != null) {
                int count = result.getData().getData().size();
                if (count > 0) {
                    return true;
                }
            }
            // 检查是否被封 (403 或错误消息包含 block/ban)
            if (result != null && result.getMessage() != null) {
                String msg = result.getMessage().toLowerCase();
                if (msg.contains("403") || msg.contains("block") || msg.contains("ban") || msg.contains("forbidden")) {
                    return false; // 被封
                }
            }
            return false;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("403") || msg.contains("timeout")) {
                return false;
            }
            return false;
        }
    }
    
    private static boolean testProviderWithProxy(String name, ResourceProvider p, String proxy) {
        try {
            // 使用反射或修改 provider 的 HttpClient
            // 这里简化处理，直接调用 provider
            VideoSearch search = new VideoSearch("电影");
            
            // 创建带代理的客户端
            HttpClient proxyClient = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(java.net.ProxySelector.of(
                        new java.net.InetSocketAddress(
                            proxy.split(":")[0], 
                            Integer.parseInt(proxy.split(":")[1])
                        )
                    ))
                    .build();
            
            // 替换 provider 的 client (如果支持)
            // 这里直接返回 false 因为大部分 provider 不支持外部注入 client
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
