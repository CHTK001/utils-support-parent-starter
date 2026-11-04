package com.chua.common.support.apm.model;

import java.util.List;

/**
 * 服务拓扑（SkyWalking Topology 页的数据面）。
 *
 * @param nodes 节点
 * @param calls 边
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlTopology(List<OtnlTopologyNode> nodes, List<OtnlTopologyCall> calls) {

    /**
     * 空拓扑
     *
     * @return 拓扑
     */
    public static OtnlTopology empty() {
        return new OtnlTopology(List.of(), List.of());
    }

    /**
     * 拓扑节点
     *
     * @param id          节点 ID
     * @param name        节点名
     * @param type        节点类型（service / database / cache / queue / gateway …）
     * @param real        是否为真实服务（false 表示外部依赖等虚拟节点）
     * @param isSelf      是否为「当前服务」
     * @param layer       分层（可空）
     * @author CH
     * @since 4.0.0.51
     */
    public record OtnlTopologyNode(String id,
                                   String name,
                                   String type,
                                   boolean real,
                                   boolean isSelf,
                                   String layer) {
    }

    /**
     * 拓扑边（一次调用关系）
     *
     * @param id          边 ID
     * @param source      源节点 ID
     * @param target      目标节点 ID
     * @param detectPoint 探测点（client / server / proxy）
     * @param calls       窗口内调用量（不支持时 -1）
     * @param errorRate   错误率百分比（不支持时 -1）
     * @param avgMs       平均耗时（不支持时 -1）
     * @author CH
     * @since 4.0.0.51
     */
    public record OtnlTopologyCall(String id,
                                   String source,
                                   String target,
                                   String detectPoint,
                                   long calls,
                                   double errorRate,
                                   double avgMs) {
    }
}
