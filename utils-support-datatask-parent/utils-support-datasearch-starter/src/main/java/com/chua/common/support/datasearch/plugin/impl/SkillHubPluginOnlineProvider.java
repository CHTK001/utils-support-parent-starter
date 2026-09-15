package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.datasearch.plugin.spi.PluginOnlineProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SkillHub 插件市场在线提供者。
 *
 * <p>SkillHub 插件市场（{@code https://skillhub.cn/plugins}）当前无公开搜索 API，
 * 提供市场源描述与安装占位。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("skillhub-plugins")
public class SkillHubPluginOnlineProvider implements PluginOnlineProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(SkillHubPluginOnlineProvider.class);

    /** 市场主页 */
    private static final String MARKET_URL = "https://skillhub.cn/plugins";

    @Override
    public String name() {
        return "skillhub-plugins";
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
        log.info("SkillHub 插件安装请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }

    @Override
    public boolean uninstall(String clientId, String pluginId) {
        log.info("SkillHub 插件卸载请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }
}