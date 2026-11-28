package com.chua.datasource.support.middleware.zookeeper;

/**
 * 集群单节点的四字命令（{@code mntr}）原始回包。
 *
 * <p>只做协议采集，不挑选具体指标：展示哪些字段由宿主决定，
 * 这样服务端放开或收紧 {@code 4lw.commands.whitelist} 时不需要改实现。</p>
 *
 * @param host       节点地址 {@code host:port}
 * @param rawMetrics {@code mntr} 回包全文；采集失败时为空串
 * @param error      采集失败原因；成功时为 {@code null}
 * @author CH
 * @since 4.0.0.51
 */
public record ZookeeperClusterNode(String host, String rawMetrics, String error) {

    /**
     * 规整空值。
     */
    public ZookeeperClusterNode {
        rawMetrics = rawMetrics == null ? "" : rawMetrics;
    }
}
