package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;

import java.util.Map;
import java.util.Objects;

/**
 * Scatter 对等节点视图。
 * <p>从 {@link Discovery} 转换而来，提供节点访问端点与动态权重等元数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ScatterNode {

    /**
     * 节点ID
     */
    private final String nodeId;

    /**
     * 主机地址
     */
    private final String host;

    /**
     * 端口
     */
    private final int port;

    /**
     * 协议
     */
    private final String protocol;

    /**
     * 业务分组
     */
    private final String groupId;

    /**
     * 服务路径
     */
    private final String servicePath;

    /**
     * 元数据
     */
    private final Map<String, String> metadata;

    /**
     * 构造节点视图。
     *
     * @param nodeId      节点ID
     * @param host        主机
     * @param port        端口
     * @param protocol    协议
     * @param groupId     业务分组
     * @param servicePath 服务路径
     * @param metadata    元数据
     */
    public ScatterNode(String nodeId, String host, int port, String protocol, String groupId,
                       String servicePath, Map<String, String> metadata) {
        this.nodeId = Objects.requireNonNull(nodeId, "节点ID不能为空");
        this.host = Objects.requireNonNull(host, "主机地址不能为空");
        this.port = port;
        this.protocol = protocol == null ? "tcp" : protocol;
        this.groupId = groupId == null ? "default" : groupId;
        this.servicePath = servicePath;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 从 Discovery 创建节点。
     *
     * @param discovery 服务发现对象
     * @return 节点视图
     */
    public static ScatterNode from(Discovery discovery) {
        String nodeId = discovery.getId();
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = discovery.getServerId();
        }
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = discovery.getHost() + ':' + discovery.getPort();
        }
        return new ScatterNode(nodeId, discovery.getHost(), discovery.getPort(), discovery.getProtocol(),
                discovery.getScatterId(), discovery.getUriSpec(), discovery.getMetadata());
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getServicePath() {
        return servicePath;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * 获取端点地址。
     *
     * @return protocol://host:port
     */
    public String getEndpoint() {
        return protocol + "://" + host + ':' + port;
    }
}
