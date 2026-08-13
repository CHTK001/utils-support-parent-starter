package com.chua.common.support.scattergather;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bootstrap 模式发现策略。
 * <p>以配置的引导节点为入口，启动时注册引导节点并执行一次性 hash 交换，
 * 用于各节点互认；本地无远程节点时回落到引导节点列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("bootstrap")
public class BootstrapScatterGatherMode implements ScatterGatherMode {

    /**
     * 模式 SPI 标识：bootstrap
     */
    private static final String SCATTER_GATHER_MODE_BOOTSTRAP = "bootstrap";

    /**
     * 默认传输协议：tcp
     */
    private static final String PROTOCOL_TCP = "tcp";

    /**
     * 节点配置
     */
    private final ScatterGatherSetting setting;

    /**
     * 引导节点列表
     */
    private final List<ScatterGatherNode> bootstrapNodes = new ArrayList<>();

    /**
     * 默认构造，使用默认配置。
     */
    public BootstrapScatterGatherMode() {
        this(new ScatterGatherSetting());
    }

    /**
     * 带配置构造。
     *
     * @param setting 节点配置
     */
    public BootstrapScatterGatherMode(ScatterGatherSetting setting) {
        this.setting = setting == null ? new ScatterGatherSetting() : setting;
    }

    /**
     * 返回模式 SPI 类型。
     *
     * @return 模式类型标识 bootstrap
     */
    @Override
    public String type() {
        return SCATTER_GATHER_MODE_BOOTSTRAP;
    }

    /**
     * 启动引导模式，注册引导节点并执行一次性 hash 交换。
     *
     * @param discovery 服务发现实例
     */
    @Override
    public void start(ScatterGatherServiceDiscovery discovery) {
        String address = setting.getBootstrapNode();
        if (StringUtils.isBlank(address)) {
            log.warn("bootstrap 模式未配置 bootstrapNode，跳过引导节点注册");
            return;
        }
        SeedAddress seed = SeedAddress.parse(address);
        if (seed == null) {
            log.warn("bootstrap 引导节点地址无效: {}", address);
            return;
        }
        int port = seed.effectivePort(setting.getDefaultPort());
        ScatterGatherNode node = new ScatterGatherNode(seed.nodeId(setting.getDefaultPort()),
                seed.getHost(), port, PROTOCOL_TCP, setting.getServicePath(), Map.of());
        bootstrapNodes.add(node);
        discovery.registerBootstrapNode(node);
        discovery.exchangeBootstrapHash(node);
    }

    /**
     * 解析远程节点列表，本地无远程节点时回落为引导节点列表。
     *
     * @param discovery 服务发现实例
     * @return 远程节点列表
     */
    @Override
    public List<ScatterGatherNode> resolveRemoteNodes(ScatterGatherServiceDiscovery discovery) {
        List<ScatterGatherNode> nodes = discovery.resolveCachedRemoteNodes();
        if (nodes.isEmpty() && !bootstrapNodes.isEmpty()) {
            nodes.addAll(bootstrapNodes);
        }
        return nodes;
    }
}