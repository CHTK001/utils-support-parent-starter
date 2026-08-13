package com.chua.common.support.scattergather;

import java.util.List;

/**
 * Scatter-Gather 发现模式 SPI。
 * <p>按 tcpMode 区分节点发现策略：seed（种子地址）、auto（网卡/自动检索）、bootstrap（引导节点 + 一次性 hash 交换）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScatterGatherMode {

    /**
     * 模式类型。
     *
     * @return seed / auto / bootstrap
     */
    String type();

    /**
     * 启动模式逻辑。
     * <p>由 {@link ScatterGatherServiceDiscovery#start()} 委托调用，负责注册种子节点、启动自动检索或执行引导节点 hash 交换。</p>
     *
     * @param discovery 服务发现实例
     * @throws Exception 启动异常
     */
    void start(ScatterGatherServiceDiscovery discovery) throws Exception;

    /**
     * 解析远程节点列表。
     * <p>由发现流程在需要远程查询时调用，用于补齐远程节点集合。</p>
     *
     * @param discovery 服务发现实例
     * @return 远程节点列表，无节点时返回空列表
     */
    List<ScatterGatherNode> resolveRemoteNodes(ScatterGatherServiceDiscovery discovery);
}