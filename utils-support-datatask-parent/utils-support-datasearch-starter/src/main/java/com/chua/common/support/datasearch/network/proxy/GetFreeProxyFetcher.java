package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 getfreeproxy.com 获取免费代理。
 *
 * <p>解析页面表格中的 IP 和端口，每 10 分钟更新一次。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("getfreeproxy")
public class GetFreeProxyFetcher implements ProxyFetcher {

    /**
     * 免费代理列表 URL
     */
    private static final String URL = "https://getfreeproxy.com/";

    /**
     * 匹配代理 IP 和端口（支持 HTML 表格和 JSON 格式）
     */
    private static final Pattern PATTERN = Pattern.compile(
            "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})[\":\\s]*(\\d{2,5})");

    /**
     * 获取代理
    */
    @Override
    public List<String> fetchProxies() {
        List<String> proxies = Wraps.list();
        try {
            String html = HttpClientFactory.of(URL).get().getBodyString();
            Matcher matcher = PATTERN.matcher(html);
            while (matcher.find()) {
                proxies.add(matcher.group(1) + ":" + matcher.group(2));
            }
        } catch (Exception e) {
            log.debug("[datasearch] 抓取 getfreeproxy 免费代理失败，返回已收集结果: {}", e.getMessage());
        }
        return proxies;
    }

    /**
     * 获取源名称
    */
    @Override
    public String getSourceName() {
        return "getfreeproxy";
    }
}
