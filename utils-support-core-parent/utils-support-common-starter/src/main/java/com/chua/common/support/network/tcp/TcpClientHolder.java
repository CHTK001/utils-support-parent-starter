package com.chua.common.support.network.tcp;

import com.chua.common.support.scatter.ScatterNode;

/**
* TcpClient 短连接调用封装（静态工具）。
*
* <p>每次调用新建短连接（经由 TcpClient.call，实现内连接池复用/用完归还），
* 一请求一响应，消除每对节点常驻长连接。适用于 scatter 的 PUSH 扩散/选举广播等
* 无返回值或无需解析响应的单向帧场景。</p>
*
* @author CH
* @since 4.0.0.42
 */
public final class TcpClientHolder {

    private TcpClientHolder() {
    }

    /**
    * 向节点发送帧（短连接）。
    *
    * @param node    目标节点
    * @param payload 帧字节
    * @return 响应字节（可能为 null）
    * @throws Exception 调用异常
     */
    public static byte[] call(ScatterNode node, byte[] payload) throws Exception {
        try (TcpClient client = new JdkTcpClient(4, 5000, 10000)) {
            return client.call(node.getHost(), node.getPort(), payload);
        }
    }
}
