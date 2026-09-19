package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.datasearch.plugin.spi.PluginOnlineProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClawHub 插件市场在线提供者。
 *
 * <p>ClawHub 插件市场（{@code https://clawhub.ai/plugins}）当前无公开搜索 API，
 * 提供市场源描述与安装占位。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("clawhub-plugins")
public class ClawHubPluginOnlineProvider implements PluginOnlineProvider {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(ClawHubPluginOnlineProvider.class);

    /**
     * 市场主页
    */
    private static final String MARKET_URL = "https://clawhub.ai/plugins";

    @Override
    public String name() {
        return "clawhub-plugins";
    }

    /**
     * 获取市场主页地址。
     *
     * @return 市场主页
     */
    public String url() {
        return MARKET_URL;
    }

    @Override
    public boolean install(String clientId, String pluginId) {
        log.info("ClawHub 插件安装请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }

    @Override
    public boolean uninstall(String clientId, String pluginId) {
        log.info("ClawHub 插件卸载请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }
}
