package com.chua.common.support.taskdistribution.node;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 节点元数据。
 *
 * <p>描述节点的能力、地址、权重和归属中间件信息，用于节点注册和能力匹配。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class NodeMeta {

    /**
    * 节点唯一标识
    */
    private String nodeId;

    /**
    * 能力标签（{"cap": "cpu", "群体": "prod"}）
    */
    @Builder.Default
    private Map<String, String> tags = new HashMap<>(); // 标签

    /**
    * 节点权重（负载均衡 权重 策略使用）
    */
    @Builder.Default
    /** 权重 */
    private int weight = 1;

    /**
    * 主机地址
    */
    private String host;

    /**
    * 端口号
    */
    private int port;

    /**
    * 归属中间件节点 标识（Home 节点独享派发）
    */
    private String homeMiddlewareId;

    /**
    * 最后心跳时间（毫秒时间戳）
    */
    private long lastHeartbeat;

    /**
    * 是否在线
    */
    @Builder.Default
    /** Online */
    private boolean online = true;
}
