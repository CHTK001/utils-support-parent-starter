package com.chua.common.support.datasearch.network.proxy;

import java.util.List;

/**
 * 代理获取器 SPI 接口。
 *
 * <p>定义从代理源获取代理列表的核心行为，供数据搜索等模块使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ProxyFetcher {

    /**
     * 获取代理列表。
     *
     * @return 代理地址列表，格式为 "主机:端口"
     */
    List<String> fetchProxies();

    /**
     * 获取代理源名称。
     *
     * @return 代理源名称
     */
    String getSourceName();
}
