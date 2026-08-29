package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 获取代理 provider 验证:SPI 发现(核心) + 多源聚合抓取(带超时兜底)。
 *
 * <p>代理源站点为免费公开站，网络慢/不可达时 fetchAll 以 120s 超时兜底，
 * 不影响 SPI 注册验证。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ProxyFetcherFlowTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        // 1) SPI 发现(无网络,核心验证)
        Map<String, ProxyFetcher> fetchers = ServiceProvider.of(ProxyFetcher.class).list();
        System.out.println("代理源数量: " + (fetchers == null ? 0 : fetchers.size()));
        if (fetchers == null || fetchers.isEmpty()) {
            System.out.println("SPI 注册失败: 未发现任何代理源");
            return;
        }
        fetchers.forEach((name, f) ->
                System.out.println("  源[" + name + "] 实际名称=" + f.getSourceName()
                        + " 源名匹配=" + name.equals(f.getSourceName())));

        // 2) 多源聚合抓取(真实网络,120s 超时兜底)
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<List<String>> future = executor.submit(() -> ProxyFetcherFlow.of().fetchAll());
        try {
            List<String> all = future.get(120, TimeUnit.SECONDS);
            System.out.println("fetchAll 聚合代理数: " + all.size());
            all.stream().limit(5).forEach(p ->
                    System.out.println("  样本: " + p + " 格式合法=" + isValidProxy(p)));
        } catch (Exception e) {
            System.out.println("fetchAll 超时/失败(免费代理源网络慢): " + e.getClass().getSimpleName());
            future.cancel(true);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 校验代理格式 host:port。
     *
     * @param proxy 代理地址
     * @return 是否合法
     */
    private static boolean isValidProxy(String proxy) {
        if (proxy == null || proxy.isBlank() || !proxy.contains(":")) {
            return false;
        }
        String port = proxy.substring(proxy.lastIndexOf(':') + 1);
        try {
            int p = Integer.parseInt(port);
            return p > 0 && p < 65536;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
