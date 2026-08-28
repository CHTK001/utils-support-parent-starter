package com.chua.common.support.datasearch;

import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.VideoProviderRegistry;
import com.chua.common.support.spi.ServiceProvider;

import java.util.*;
import java.util.concurrent.*;

/**
 * Pansou plugin test with blocked provider registry
 */
public class DataSearchProxyTest {

    private static final Set<String> PASS_PROVIDERS = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Pansou Blocked Provider Test ===");

        // 初始化已封禁列表（从 JSON）
        VideoProviderRegistry.initBlockedResources();
        System.out.println("Loaded blocked providers: " + VideoProviderRegistry.getBlockedNames().size());

        ServiceProvider<ResourceProvider> sp = ServiceProvider.of(ResourceProvider.class);
        java.util.Set<String> names = sp.getExtensions();
        System.out.println("Total providers: " + names.size());

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

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(videoNames.size());

        for (String name : videoNames) {
            final String n = name;
            executor.submit(() -> {
                try {
                    ResourceProvider p = sp.getExtension(n);
                    if (p == null) { latch.countDown(); return; }

                    // 检查是否被封
                    if (VideoProviderRegistry.isBlocked(n)) {
                        System.out.printf("[BLOCKED] %-14s (already marked)%n", n);
                        latch.countDown();
                        return;
                    }

                    VideoSearch search = new VideoSearch("电影");
                    var result = p.searchResource(search);

                    if (result != null && result.getData() != null
                        && result.getData().getData() != null
                        && !result.getData().getData().isEmpty()) {
                        PASS_PROVIDERS.add(n);
                        System.out.printf("[PASS] %-14s rows=%d%n", n, result.getData().getData().size());
                    } else {
                        String msg = result != null ? result.getMessage() : "";
                        if (msg != null && (msg.contains("403") || msg.contains("timeout"))) {
                            VideoProviderRegistry.block(n,
                                msg.contains("403") ? VideoProviderRegistry.BlockReason.BLOCKED
                                    : VideoProviderRegistry.BlockReason.TIMEOUT,
                                msg);
                            System.out.printf("[BLOCKED] %-14s (%s)%n", n,
                                msg.contains("403") ? "403" : "timeout");
                        } else {
                            // 持续返回空也标记
                            VideoProviderRegistry.block(n, VideoProviderRegistry.BlockReason.RATE_LIMITED);
                            System.out.printf("[EMPTY] %-14s -> marked RATE_LIMITED%n", n);
                        }
                    }
                } catch (Exception e) {
                    String emsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (emsg.contains("403") || emsg.contains("timeout")) {
                        VideoProviderRegistry.block(n,
                            emsg.contains("403") ? VideoProviderRegistry.BlockReason.BLOCKED
                                : VideoProviderRegistry.BlockReason.TIMEOUT,
                            e.getMessage());
                    }
                    System.out.printf("[ERROR] %-14s %s%n", n,
                        e.getMessage() != null ? e.getMessage().substring(0, Math.min(25, e.getMessage().length())) : "");
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(90, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("\n=== Summary ===");
        System.out.println("PASS:     " + PASS_PROVIDERS.size());
        System.out.println("BLOCKED:  " + VideoProviderRegistry.getBlockedNames().size());
        System.out.println("Total:    " + videoNames.size());

        System.out.println("\nWorking providers:");
        for (String n : PASS_PROVIDERS) System.out.printf("  %s%n", n);

        System.out.println("\nBlocked providers:");
        VideoProviderRegistry.getBlockedNames().stream().sorted()
            .forEach(n -> System.out.printf("  %s (%s)%n", n,
                VideoProviderRegistry.getBlockReason(n) != null ? VideoProviderRegistry.getBlockReason(n).label() : "unknown"));
    }
}
