package com.chua.common.support.datasearch;

import com.chua.common.support.datasearch.network.proxy.ProxyFetcherFlow;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用代理直接访问站点，验证哪些域名可达
 */
public class DataSearchProxyTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Pansou Site Probe ===");

        List<String> proxies = ProxyFetcherFlow.of().fetchAll();
        System.out.println("Proxies: " + proxies.size());

        // 目标站点列表
        String[][] sites = {
            {"ddys.pro", "https://ddys.pro/?s=电影"},
            {"libvio.app", "https://libvio.app/search?keyword=电影"},
            {"4khdr.cn", "https://www.4khdr.cn/search.php"},
            {"91panta.cn", "https://www.91panta.cn/"},
            {"hunhepan.com", "https://hunhepan.com/"},
            {"jikepan.xyz", "https://jikepan.xyz/"},
            {"pan666.net", "https://pan666.net/"},
            {"pansearch.me", "https://api.pansearch.me/search?q=测试"},
            {"panta.vip", "https://www.panta.vip/"},
            {"sousou.com", "https://www.sousou.com/"},
            {"quarktv.com", "https://www.quarktv.com/"},
            {"zhihuipan.com", "https://zhihuipan.com/"},
            {"aikanzy8.com", "https://www.aikanzy8.com/"},
            {"duoduo.cc", "https://www.duoduo.cc/"},
            {"labi.me", "https://www.labi.me/"},
            {"hdr4k.cn", "https://www.4khdr.cn/"},
            {"xunlei.com", "https://www.xunlei.com/"},
            {"yunso.com", "https://www.yunso.com/"},
        };

        // 尝试直连
        HttpClient directClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        System.out.println("\n--- Direct Connection ---");
        for (String[] site : sites) {
            probe(directClient, site[0], site[1]);
        }

        // 尝试代理
        if (!proxies.isEmpty()) {
            String proxy = proxies.get(0);
            String[] parts = proxy.split(":");
            HttpClient proxyClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(ProxySelector.of(new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))))
                    .build();

            System.out.println("\n--- Via Proxy " + proxy + " ---");
            for (String[] site : sites) {
                probe(proxyClient, site[0], site[1]);
            }
        }

        System.out.println("\n=== Summary ===");
        System.out.println("Reachable: " + pass);
        System.out.println("Unreachable: " + fail);
    }

    private static void probe(HttpClient client, String name, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            int len = resp.body() != null ? resp.body().length() : 0;
            if (code >= 200 && code < 400 && len > 100) {
                System.out.printf("[OK]   %-16s %d (%d bytes)%n", name, code, len);
                pass++;
            } else {
                System.out.printf("[SKIP] %-16s %d (%d bytes)%n", name, code, len);
                fail++;
            }
        } catch (Exception e) {
            System.out.printf("[FAIL] %-16s %s%n", name, e.getClass().getSimpleName() + ": " + e.getMessage().substring(0, Math.min(50, e.getMessage().length())));
            fail++;
        }
    }
}
