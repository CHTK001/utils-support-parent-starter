package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.tcp.TcpClient;
import com.chua.common.support.network.tcp.JdkTcpClient;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * TCP scatter 远程客户端（短连接）。
 *
 * <p>通过 {@link TcpClient#call(String, int, byte[])} 发请求帧收响应帧，
 * 一请求一响应；连接生命周期由 TcpClient 实现管理（复用连接池/用完归还），
 * 无每对节点常驻长连接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TcpScatterRemoteClient implements ScatterRemoteClient {

    private final TcpClient client;

    public TcpScatterRemoteClient() {
        this(new JdkTcpClient(4, 5000, 10000));
    }

    public TcpScatterRemoteClient(TcpClient client) {
        this.client = client;
    }

    @Override
    public ScatterResult<Discovery> invoke(ScatterContext context, ScatterNode node, long timeoutMillis) {
        int requestId = Math.abs(context.getRequestId().hashCode());
        try {
            ScatterFrame req = new ScatterFrame(ScatterProtocol.TYPE_REQ, requestId,
                    context.getPath(), new byte[0]);
            byte[] response = client.call(node.getHost(), node.getPort(), req.encode());
            if (response == null || response.length == 0) {
                return ScatterResult.timeout(node.getNodeId(), "空响应: " + node.getEndpoint());
            }
            ScatterFrame resp = ScatterFrame.decode(response);
            if (resp.getType() == ScatterProtocol.TYPE_RESP && resp.getPayload().length > 0) {
                String json = new String(resp.getPayload(), StandardCharsets.UTF_8);
                Discovery data = com.chua.common.support.lang.json.Json.fromJson(json, Discovery.class);
                return ScatterResult.success(node.getNodeId(), data);
            }
            return ScatterResult.failure(node.getNodeId(), "响应类型异常: " + resp.getType());
        } catch (Exception e) {
            return ScatterResult.timeout(node.getNodeId(), "请求失败: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}
