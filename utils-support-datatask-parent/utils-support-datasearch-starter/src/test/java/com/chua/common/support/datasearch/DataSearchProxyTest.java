package com.chua.common.support.datasearch;

import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.*;
import java.util.concurrent.*;

/**
 * 批量测试所有 pansou 插件，标记被封站点
 */
public class DataSearchProxyTest {
    
    private static final Set<String> BLOCKED_PROVIDERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> PASS_PROVIDERS = ConcurrentHashMap.newKeySet();
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Pansou Plugin Test ===");
        
        // 获取所有 ResourceProvider
        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        java.util.Set<String> names = sp.getExtensions();
        System.out.println("Total: " + names.size());
        
        // 过滤视频类 provider
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
                || lower.contains("kks") || lower.contains("kuai") || lower.contains("mayipan")
                || lower.contains("pancheck") || lower.contains("panduoduo") || lower.contains("panhome")
                || lower.contains("panmao") || lower.contains("panshen") || lower.contains("panxiansen")
                || lower.contains("panyou") || lower.contains("panyunso") || lower.contains("quarksoo")
                || lower.contains("quarktv") || lower.contains("qupansou") || lower.contains("souci")
                || lower.contains("soupan") || lower.contains("sousou") || lower.contains("u3c3")
                || lower.contains("wypanso") || lower.contains("xunlei") || lower.contains("ypfxw")
                || lower.contains("yuhuage") || lower.contains("yunpan") || lower.contains("yunso")
                || lower.contains("zhihuipan")) {
                videoNames.add(name);
            }
        }
        System.out.println("Video providers: " + videoNames.size());
        
        // 并发测试
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (String name : videoNames) {
            ResourceProvider p = sp.getExtension(name);
            if (p == null) continue;
            
            futures.add(executor.submit(() -> {
                try {
                    VideoSearch search = new VideoSearch("电影");
                    var result = p.searchResource(search);
                    
                    if (result != null && result.getData() != null 
                        && result.getData().getData() != null 
                        && !result.getData().getData().isEmpty()) {
                        System.out.printf("[PASS] %-14s rows=%d%n", name, 
                            result.getData().getData().size());
                        PASS_PROVIDERS.add(name);
                        return true;
                    } else {
                        // 检查是否被封
                        String msg = result != null ? result.getMessage() : "";
                        if (msg != null && (msg.contains("403") || msg.contains("block") 
                            || msg.contains("forbidden"))) {
                            System.out.printf("[BLOCKED] %-14s%n", name);
                            BLOCKED_PROVIDERS.add(name);
                        } else {
                            System.out.printf("[EMPTY] %-14s rows=0%n", name);
                        }
                        return false;
                    }
                } catch (Exception e) {
                    System.out.printf("[ERROR] %-14s %s%n", name, 
                        e.getMessage() != null ? e.getMessage().substring(0, Math.min(30, e.getMessage().length())) : "");
                    return false;
                }
            }));
        }
        
        // 等待所有任务完成
        executor.shutdown();
        executor.awaitTermination(120, TimeUnit.SECONDS);
        
        // 统计结果
        int passCount = 0;
        int emptyCount = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get()) passCount++;
                else emptyCount++;
            } catch (Exception e) { emptyCount++; }
        }
        
        System.out.println("\n=== Summary ===");
        System.out.println("PASS: " + passCount);
        System.out.println("EMPTY: " + (videoNames.size() - passCount - BLOCKED_PROVIDERS.size()));
        System.out.println("BLOCKED: " + BLOCKED_PROVIDERS.size());
        
        if (!BLOCKED_PROVIDERS.isEmpty()) {
            System.out.println("\nBlocked providers:");
            BLOCKED_PROVIDERS.forEach(System.out::println);
        }
    }
}
