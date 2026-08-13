package com.chua.common.support.scattergather;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * Seed 模式发现策略。
 * <p>启动时根据 seed 地址列表注册种子节点；本地无远程节点时，从 seed 地址解析远程节点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("seed")
public class SeedScatterGatherMode implements ScatterGatherMode {

    /**
     * 节点配置
     */
    private final ScatterGatherSetting setting;

    /**
     * 默认构造，使用默认配置。
     */
    public SeedScatterGatherMode() {
        this(new ScatterGatherSetting());
    }

    /**
     * 带配置构造。
     *
     * @param setting 节点配置
     */
    public SeedScatterGatherMode(ScatterGatherSetting setting) {
        this.setting = setting == null ? new ScatterGatherSetting() : setting;
    }

    @Override
    public String type() {
        return "seed";
    }

    @Override
    public void start(ScatterGatherServiceDiscovery discovery) {
        discovery.registerSeedNodes();
    }

    @Override
    public List<ScatterGatherNode> resolveRemoteNodes(ScatterGatherServiceDiscovery discovery) {
        return discovery.resolveSeedNodes();
    }
}