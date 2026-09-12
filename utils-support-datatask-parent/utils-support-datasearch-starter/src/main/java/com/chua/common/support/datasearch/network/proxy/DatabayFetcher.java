package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
* 从 Databay API 获取免费代理。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("databay")
public class DatabayFetcher implements ProxyFetcher {

    /**
    * Databay API URL
     */
    private static final String URL = "https://databay.com/api/v1/proxy-list"
            + "?protocol=http&anonymity=elite&format=json&limit=20";

    @Override
    /** 获取代理 */
    public List<String> fetchProxies() {
        List<String> proxies = new ArrayList<>();
        try {
            String json = HttpClientFactory.of(URL).get().getBodyString();
            Map<String, Object> map = Json.fromJson(json, Map.class);
            if (map == null) {
                return proxies;
            }
            List<?> list = (List<?>) map.get("data");
            if (list != null) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> proxy) {
                        String ip = (String) proxy.get("ip");
                        Object portObj = proxy.get("port");
                        if (ip != null && portObj != null) {
                            proxies.add(ip + ":" + portObj);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return proxies;
    }

    @Override
    /** 获取源名称 */
    public String getSourceName() {
        return "databay";
    }
}