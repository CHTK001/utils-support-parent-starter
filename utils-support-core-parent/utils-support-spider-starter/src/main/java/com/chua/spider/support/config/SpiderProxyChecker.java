package com.chua.spider.support.config;

import com.chua.spider.support.config.model.SpiderProxy;
import com.chua.spider.support.config.model.SpiderProxyPool;
import com.chua.spider.support.config.store.SpiderProxyPoolStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理池节点连通性测试服务。
 *
 * <p>对代理池中每个节点通过 HTTP HEAD 请求验证可达性，
 * 返回每节点的连通性结果（耗时、状态码、错误信息）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@RequiredArgsConstructor
public class SpiderProxyChecker {

    /**
     * 测试超时（秒）
     */
    private static final int TEST_TIMEOUT_SECONDS = 5;

    /**
     * 探测目标 URL（仅测代理链路：能 TCP 握手即视为连通）
     */
    private static final String PROBE_URL = "http://www.gstatic.com/generate_204";

    /**
     * 代理池存储
     */
    private final SpiderProxyPoolStore poolStore;

    /**
     * 测试指定代理池的所有节点。
     *
     * @param poolCode 代理池编码
     * @return 每个节点测试结果，含 summary
     */
    public Map<String, Object> testPool(String poolCode) {
        SpiderProxyPool pool = poolStore.get(poolCode);
        if (pool == null) {
            return Map.of("error", "代理池不存在: " + poolCode);
        }
        List<Map<String, Object>> nodeResults = new ArrayList<>();
        int success = 0;
        for (SpiderProxy proxy : pool.getProxies()) {
            Map<String, Object> r = testOne(proxy);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                success++;
            }
            nodeResults.add(r);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("poolCode", poolCode);
        resp.put("total", pool.getProxies().size());
        resp.put("success", success);
        resp.put("nodes", nodeResults);
        return resp;
    }

    /**
     * 测试单个代理节点。
     *
     * @param proxy 代理节点
     * @return 测试结果 { ok, 主机, 端口, 状态, elapsedms, 错误 }
     */
    private Map<String, Object> testOne(SpiderProxy proxy) {
        long start = System.currentTimeMillis();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("host", proxy.getProxyHost());
        r.put("port", proxy.getProxyPort());
        r.put("protocol", proxy.getProxyProtocol());
        try {
            InetSocketAddress addr = new InetSocketAddress(proxy.getProxyHost(), proxy.getProxyPort());
            Proxy netProxy = new Proxy(Proxy.Type.HTTP, addr);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(TEST_TIMEOUT_SECONDS))
                    .proxy(new SingleProxySelector(netProxy))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(PROBE_URL))
                    .timeout(Duration.ofSeconds(TEST_TIMEOUT_SECONDS))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            long elapsed = System.currentTimeMillis() - start;
            int sc = resp.statusCode();
            r.put("ok", sc >= 200 && sc < 400);
            r.put("status", sc);
            r.put("elapsedMs", elapsed);
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            r.put("elapsedMs", System.currentTimeMillis() - start);
            log.warn("[ProxyTester] {}:{} 测试失败", proxy.getProxyHost(), proxy.getProxyPort(), e);
        }
        return r;
    }

    /**
      * 始终返回同一代理的 代理selector。
     *
     * <p>JDK 25 javac 对 {@code ProxySelector.of(Proxy)} 重载解析为
     * {@code of(InetSocketAddress)}，为此显式继承 ProxySelector 避免歧义。</p>
     */
    private static final class SingleProxySelector extends ProxySelector {

        /**
         * 唯一的代理
         */
        private final Proxy proxy;

        SingleProxySelector(Proxy proxy) {
            this.proxy = proxy;
        }

        @Override
        /** 选择 */
        public List<Proxy> select(URI uri) {
            return java.util.Collections.singletonList(proxy);
        }

        @Override
        /** 连接失败 */
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // no-op
        }
    }
}
