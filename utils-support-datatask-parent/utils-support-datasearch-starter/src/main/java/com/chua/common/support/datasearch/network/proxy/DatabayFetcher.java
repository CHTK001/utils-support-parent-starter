package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 从 Databay API 获取免费代理。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("databay")
public class DatabayFetcher implements ProxyFetcher {

    /**
     * Databay API URL
     */
    private static final String URL = "https://databay.com/api/v1/proxy-list"
            + "?protocol=http&anonymity=elite&format=json&limit=20";

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
        } catch (Exception e) {
            log.debug("[datasearch] 抓取 Databay 免费代理失败，返回已收集结果: {}", e.getMessage());
        }
        return proxies;
    }

    /**
     * 获取源名称
    */
    @Override
    public String getSourceName() {
        return "databay";
    }
}
