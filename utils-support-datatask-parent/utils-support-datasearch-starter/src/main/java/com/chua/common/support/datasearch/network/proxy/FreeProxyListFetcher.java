package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 free-代理-列表.net 获取免费代理。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("free-proxy-list")
public class FreeProxyListFetcher implements ProxyFetcher {

    /**
     * 免费代理列表 URL
     */
    private static final String URL = "https://free-proxy-list.net/";

    /**
     * IP 和端口正则
     */
    private static final Pattern PROXY_PATTERN = Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})</td><td>(\\d{2,5})");

    @Override
    /** 获取代理 */
    public List<String> fetchProxies() {
        List<String> proxies = new ArrayList<>();
        try {
        String html = HttpClientFactory.of(URL).get().getBodyString();
            Matcher matcher = PROXY_PATTERN.matcher(html);
            while (matcher.find()) {
                proxies.add(matcher.group(1) + ":" + matcher.group(2));
            }
        } catch (Exception ignored) {
        }
        return proxies;
    }

    @Override
    /** 获取源名称 */
    public String getSourceName() {
        return "free-proxy-list";
    }
}
