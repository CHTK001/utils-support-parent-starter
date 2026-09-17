package com.chua.common.support.scatter;

import lombok.Data;

/**
 * scatter 节点信息（对端地址 + 协议）。
 *
 * @author CH
 * @since 4.0.0.42
*/
@Data
public class ScatterNode {

    /** 节点标识 */
    private final String nodeId;
    /** 主机 */
    private final String host;
    /** 端口 */
    private final int port;
    /** 协议：tcp / udp */
    private final String protocol;
    /** 分组 */
    private final String groupId;
    /** 服务路径 */
    private final String servicePath;

    public ScatterNode(String nodeId, String host, int port, String protocol,
                       String groupId, String servicePath) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.protocol = protocol;
        this.groupId = groupId;
        this.servicePath = servicePath;
    }

    /**
    * 端点描述。
    *
    * @return 如 tcp://127.0.0.1:19000
    */
    public String getEndpoint() {
        return protocol + "://" + host + ":" + port;
    }

    @Override
    public String toString() {
        return getEndpoint();
    }
}
