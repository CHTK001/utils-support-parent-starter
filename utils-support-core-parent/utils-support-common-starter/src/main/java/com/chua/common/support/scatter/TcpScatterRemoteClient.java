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
 * 一请求一响应；默认每次 invoke 新建短连接用完即关（与服务端 ScatterTcpNodeServer
 * 一请求一断的短连接语义匹配），不持有跨调用的长连接/连接池——消除
 * 复用已关闭连接导致的"连接被中止"失败。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TcpScatterRemoteClient implements ScatterRemoteClient {

    /** 注入的客户端（SPI/自定义场景）；null 时每次 invoke 新建短连接 */
    private final TcpClient client;

    public TcpScatterRemoteClient() {
        this(null);
    }

    public TcpScatterRemoteClient(TcpClient client) {
        this.client = client;
    }

    @Override
    public ScatterResult<java.util.List<Discovery>> invoke(ScatterContext context, ScatterNode node, long timeoutMillis) {
        int requestId = Math.abs(context.getRequestId().hashCode());
        TcpClient use = client;
        TcpClient shortLived = null;
        if (use == null) {
            // 短连接：每次新建、用完即关（连接生命周期由本方法保证）
            shortLived = new JdkTcpClient(1, 5000, 10000);
            use = shortLived;
        }
        try {
            ScatterFrame req = new ScatterFrame(ScatterProtocol.TYPE_REQ, requestId,
                    context.getPath(), new byte[0]);
            byte[] response = use.call(node.getHost(), node.getPort(), req.encode());
            if (response == null || response.length == 0) {
                return ScatterResult.timeout(node.getNodeId(), "空响应: " + node.getEndpoint());
            }
            ScatterFrame resp = ScatterFrame.decode(response);
            if (resp.getType() == ScatterProtocol.TYPE_RESP && resp.getPayload().length > 0) {
                String json = new String(resp.getPayload(), StandardCharsets.UTF_8);
                java.util.List<Discovery> data =
                        com.chua.common.support.lang.json.Json.fromJsonToList(json, Discovery.class);
                return ScatterResult.success(node.getNodeId(), data);
            }
            return ScatterResult.failure(node.getNodeId(), "响应类型异常: " + resp.getType());
        } catch (Exception e) {
            return ScatterResult.timeout(node.getNodeId(), "请求失败: " + e.getMessage());
        } finally {
            if (shortLived != null) {
                try {
                    shortLived.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
