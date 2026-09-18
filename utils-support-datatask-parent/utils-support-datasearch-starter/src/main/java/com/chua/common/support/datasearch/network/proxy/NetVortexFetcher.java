package com.chua.common.support.datasearch.network.proxy;

import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 从 net-vortex.com 获取免费代理。
*
* <p>解析页面表格中的 IP:PORT 格式，每小时更新一次。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("netvortex")
public class NetVortexFetcher implements ProxyFetcher {

    /**
    * 免费代理列表 URL
    */
    private static final String URL = "https://net-vortex.com/free-proxies";

    /**
    * 匹配 IP:端口 格式的正则
    */
    private static final Pattern PATTERN = Pattern.compile(
            "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):(\\d{2,5})");

    @Override
    /** 获取代理 */
    public List<String> fetchProxies() {
        List<String> proxies = new ArrayList<>();
        try {
            String html = HttpClientFactory.of(URL).get().getBodyString();
            Matcher matcher = PATTERN.matcher(html);
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
        return "netvortex";
    }
}
