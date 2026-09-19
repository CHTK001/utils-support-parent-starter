package com.chua.common.support.network.discovery.peermesh;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 启动时立即执行一次全量巡检。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BootstrapProbe {

    /** 配置 */
    private final MeshConfig config;
    /** 节点表 */
    private final NodeTable nodeTable;
    /** Selector */
    private final InterfaceSelector selector;
    /** 本地服务器ID */
    private final String localServerId;
    /** 本地端口 */
    private final int localPort;
    /** Diskstore */
    private final DiskStore diskStore;
    /** 本地主机 */
    private final String localHost;

    /**
     * 创建 BootstrapProbe 实例
     * @param config config
     * @param nodeTable nodeTable
     * @param selector selector
     * @param localServerId localServerId
     * @param localPort localPort
     * @param diskStore diskStore
     * @param localHost localHost
     */
    public BootstrapProbe(MeshConfig config, NodeTable nodeTable, InterfaceSelector selector,
                          String localServerId, int localPort, DiskStore diskStore,
                          String localHost) {
        this.config = config;
        this.nodeTable = nodeTable;
        this.selector = selector;
        this.localServerId = localServerId;
        this.localPort = localPort;
        this.diskStore = diskStore;
        this.localHost = localHost;
    }

    /**
     * 执行启动巡检。
     */
    public void run() throws Exception {
        ProbeStrategy strategy = createStrategy();
        if (strategy == null) {
            return;
        }
        strategy.start();
        List<NodeTable.NodeEntry> entries = strategy.getDiscoveredNodes();
        long now = System.currentTimeMillis();
        for (NodeTable.NodeEntry entry : entries) {
            if (!localServerId.equals(entry.getDiscovery().getServerId())) {
                nodeTable.upsert(entry.getDiscovery().getServerId(), entry.getDiscovery(), entry.getEpoch(), now);
            }
        }
        if (diskStore != null) {
            diskStore.save(new ArrayList<>(nodeTable.getAllEntries().values()));
        }
    }

    /**
     * 根据配置创建对应的探针策略。
     *
     * @return ProbeStrategy 实例
     */
    private ProbeStrategy createStrategy() {
        // UDP 模式统一使用 UDP 广播探针
        if ("udp".equalsIgnoreCase(config.getMode())) {
            return new UdpModeProbe(config, localServerId);
        }
        String discovery = config.getDiscovery();
        if (discovery == null || discovery.isBlank()) {
            discovery = "c-seed";
        }
        switch (discovery.toLowerCase()) {
            case "c":
                return new CModeProbe(config, localServerId);
            case "seed":
                return new SeedModeProbe(config, localServerId, localHost, localPort);
            case "c-seed":
                List<NodeTable.NodeEntry> all = new ArrayList<>();
                CModeProbe cMode = new CModeProbe(config, localServerId);
                try {
                    cMode.start();
                } catch (Exception e) {
                    log.warn("C 模式巡检失败: {}", e.getMessage());
                }
                all.addAll(cMode.getDiscoveredNodes());
                SeedModeProbe seedMode = new SeedModeProbe(config, localServerId, localHost, localPort);
                try {
                    seedMode.start();
                } catch (Exception e) {
                    log.warn("SEED 模式巡检失败: {}", e.getMessage());
                }
                all.addAll(seedMode.getDiscoveredNodes());
                return new ProbeStrategy() {
                    @Override
                    /** 开始 */
                    public void start() {
                    }

                    @Override
                    /** 停止 */
                    public void stop() {
                    }

                    @Override
                    /** 获取DiscoveredNodes */
                    public List<NodeTable.NodeEntry> getDiscoveredNodes() {
                        return Collections.unmodifiableList(all);
                    }
                };
            default:
                log.warn("未知 discovery 模式，将跳过巡检: {}", discovery);
                return null;
        }
    }
}
