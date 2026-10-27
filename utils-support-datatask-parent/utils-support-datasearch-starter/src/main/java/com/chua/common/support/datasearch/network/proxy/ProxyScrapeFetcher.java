package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 从 代理scrape API 获取免费代理。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("proxy-scrape")
public class ProxyScrapeFetcher implements ProxyFetcher {

    /**
     * 代理scrape API URL（HTTP Elite 代理）
     */
    private static final String URL = "https://api.proxyscrape.com/v4/free-proxy-list/get"
            + "?request=displayproxies&protocol=http&anonymity=elite&proxy_format=protocolipport&format=json";

    /**
     * 获取代理
    */
    @Override
    public List<String> fetchProxies() {
        List<String> proxies = Wraps.list();
        try {
            String json = HttpClientFactory.of(URL).get().getBodyString();
            Map<String, Object> map = Json.fromJson(json, Map.class);
            if (map == null) {
                return proxies;
            }
            List<?> list = (List<?>) map.get("proxies");
            if (list == null) {
                return proxies;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> proxy) {
                    String ip = (String) proxy.get("ip");
                    Object portObj = proxy.get("port");
                    String port = portObj != null ? portObj.toString() : "";
                    if (StringUtils.isNotEmpty(ip)) {
                        proxies.add(ip + ":" + port);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[datasearch] 抓取 proxy-scrape 免费代理失败，返回已收集结果: {}", e.getMessage());
        }
        return proxies;
    }

    /**
     * 获取源名称
    */
    @Override
    public String getSourceName() {
        return "proxy-scrape";
    }
}
