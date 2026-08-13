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
     * 模式 SPI 标识：seed
     */
    private static final String MODE_SEED = "seed";

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

    /**
     * 返回模式 SPI 类型。
     *
     * @return 模式类型标识 seed
     */
    @Override
    public String type() {
        return MODE_SEED;
    }

    /**
     * 启动种子模式，注册配置的 seed 节点到本地缓存。
     *
     * @param discovery 服务发现实例
     */
    @Override
    public void start(ScatterGatherServiceDiscovery discovery) {
        discovery.registerSeedNodes();
    }

    /**
     * 解析远程节点列表，从 seed 地址列表解析节点。
     *
     * @param discovery 服务发现实例
     * @return 远程节点列表
     */
    @Override
    public List<ScatterGatherNode> resolveRemoteNodes(ScatterGatherServiceDiscovery discovery) {
        return discovery.resolveSeedNodes();
    }
}