package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 ProxyScrape API 获取免费代理。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("proxy-scrape")
public class ProxyScrapeFetcher implements ProxyFetcher {

    /**
     * ProxyScrape API URL（HTTP Elite 代理）
     */
    private static final String URL = "https://api.proxyscrape.com/v4/free-proxy-list/get"
            + "?request=displayproxies&protocol=http&anonymity=elite&proxy_format=protocolipport&format=json";

    @Override
    /** FetchProxies */
    public List<String> fetchProxies() {
        List<String> proxies = new ArrayList<>();
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
        } catch (Exception ignored) {
        }
        return proxies;
    }

    @Override
    /** 获取SourceName */
    public String getSourceName() {
        return "proxy-scrape";
    }
}