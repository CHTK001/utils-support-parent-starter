package com.chua.common.support.scattergather;

import com.chua.common.support.network.discovery.Discovery;

import java.util.Map;
import java.util.Objects;

/**
 * Scatter-Gather 节点视图。
 * <p>从 Discovery 转换而来，提供节点访问端点等元数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ScatterGatherNode {

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
     * @param nodeId     节点ID
     * @param host       主机
     * @param port       端口
     * @param protocol   协议
     * @param servicePath 服务路径
     * @param metadata   元数据
     */
    public ScatterGatherNode(String nodeId, String host, int port, String protocol, String servicePath, Map<String, String> metadata) {
        this.nodeId = Objects.requireNonNull(nodeId, "节点ID不能为空");
        this.host = Objects.requireNonNull(host, "主机地址不能为空");
        this.port = port;
        this.protocol = protocol == null ? "tcp" : protocol;
        this.servicePath = servicePath;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 从 Discovery 创建节点。
     *
     * @param discovery 服务发现对象
     * @return 节点视图
     */
    public static ScatterGatherNode from(Discovery discovery) {
        String nodeId = discovery.getId();
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = discovery.getServerId();
        }
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = discovery.getHost() + ':' + discovery.getPort();
        }
        return new ScatterGatherNode(nodeId, discovery.getHost(), discovery.getPort(), discovery.getProtocol(), discovery.getUriSpec(), discovery.getMetadata());
    }

    /**
     * 获取节点ID。
     *
     * @return nodeId
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取主机地址。
     *
     * @return host
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取端口。
     *
     * @return port
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取协议。
     *
     * @return protocol
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 获取服务路径。
     *
     * @return servicePath
     */
    public String getServicePath() {
        return servicePath;
    }

    /**
     * 获取元数据。
     *
     * @return metadata
     */
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

    /**
     * 是否为TCP协议。
     *
     * @return true TCP
     */
    public boolean isTcp() {
        return "tcp".equalsIgnoreCase(protocol);
    }
}
