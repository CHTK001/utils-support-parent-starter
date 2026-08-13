package com.chua.common.support.scattergather;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * Auto 模式发现策略。
 * <p>启动时基于本地已知节点启动定时自动检索任务；远程节点列表来自本地缓存中已发现的节点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("auto")
public class AutoScatterGatherMode implements ScatterGatherMode {

    /**
     * 节点配置
     */
    private final ScatterGatherSetting setting;

    /**
     * 默认构造，使用默认配置。
     */
    public AutoScatterGatherMode() {
        this(new ScatterGatherSetting());
    }

    /**
     * 带配置构造。
     *
     * @param setting 节点配置
     */
    public AutoScatterGatherMode(ScatterGatherSetting setting) {
        this.setting = setting == null ? new ScatterGatherSetting() : setting;
    }

    @Override
    public String type() {
        return "auto";
    }

    @Override
    public void start(ScatterGatherServiceDiscovery discovery) {
        discovery.startAutoDiscovery();
    }

    @Override
    public List<ScatterGatherNode> resolveRemoteNodes(ScatterGatherServiceDiscovery discovery) {
        return discovery.resolveCachedRemoteNodes();
    }
}