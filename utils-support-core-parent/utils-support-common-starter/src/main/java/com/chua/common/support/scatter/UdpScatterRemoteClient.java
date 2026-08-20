package com.chua.common.support.scatter;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * UDP scatter 远程客户端（无连接报文）。
 *
 * <p>发送请求报文 → 等待响应报文（带超时）。UDP 天然无连接，无长连接维护。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class UdpScatterRemoteClient implements ScatterRemoteClient {

    @Override
    public ScatterResult<Discovery> invoke(ScatterContext context, ScatterNode node, long timeoutMillis) {
        int requestId = Math.abs(context.getRequestId().hashCode());
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            ScatterFrame req = new ScatterFrame(ScatterProtocol.TYPE_REQ, requestId,
                    context.getPath(), new byte[0]);
            byte[] reqBytes = req.encode();
            InetSocketAddress target = new InetSocketAddress(node.getHost(), node.getPort());
            socket.send(new DatagramPacket(reqBytes, reqBytes.length, target));
            byte[] buffer = new byte[65536];
            DatagramPacket respPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(respPacket);
            byte[] respData = java.util.Arrays.copyOf(respPacket.getData(), respPacket.getLength());
            ScatterFrame resp = ScatterFrame.decode(respData);
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
        // 无状态，无需释放
    }
}
