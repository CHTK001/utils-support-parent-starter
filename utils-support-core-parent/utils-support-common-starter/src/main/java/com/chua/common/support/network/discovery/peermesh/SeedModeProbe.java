package com.chua.common.support.network.discovery.peermesh;

import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.peermesh.MessageProtocol;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
* SEED 模式探针：连接种子节点列表。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class SeedModeProbe implements ProbeStrategy {

    /** Connect_timeout_ms */
    private static final int CONNECT_TIMEOUT_MS = 500;

    /** 配置 */
    private final MeshConfig config;
    /** 本地服务器ID */
    private final String localServerId;
    /** 本地主机 */
    private final String localHost;
    /** 本地端口 */
    private final int localPort;
    /** Discovered */
    private final List<NodeTable.NodeEntry> discovered = new ArrayList<>();
    /** stopped */
    private volatile boolean stopped;

    /**
    * 构造函数。
    *
    * @param config 配置
    * @param localServerId 本地 serverId
    * @param localHost 本地主机 IP
    * @param localPort 本地端口
    */
    public SeedModeProbe(MeshConfig config, String localServerId, String localHost, int localPort) {
        this.config = config;
        this.localServerId = localServerId;
        this.localHost = localHost;
        this.localPort = localPort;
    }

    @Override
    /** 开始 */
    public void start() throws Exception {
        List<String> seeds = config.getSeeds();
        if (seeds == null || seeds.isEmpty()) {
            return;
        }
        for (String seed : seeds) {
            if (stopped) {
                break;
            }
            probeSeed(seed);
        }
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        stopped = true;
    }

    @Override
    /** 获取DiscoveredNodes */
    public List<NodeTable.NodeEntry> getDiscoveredNodes() {
        return Collections.unmodifiableList(discovered);
    }

    /**
    * 探测种子节点。
    *
    * @param seed 种子地址，格式 host:port
    */
    private void probeSeed(String seed) {
        String[] parts = seed.split(":");
        if (parts.length != 2) {
            log.warn("种子地址格式错误，跳过: {}", seed);
            return;
        }
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            log.warn("种子端口解析失败，跳过: {}", seed);
            return;
        }
        String serverId = host + ":" + port;
        if (serverId.equals(localServerId)) {
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            Discovery local = Discovery.builder()
                    .serverId(localServerId)
                    .host(localHost)
                    .port(localPort)
                    .protocol(config.getMode())
                    .build();
            NodeTable.NodeEntry myEntry = new NodeTable.NodeEntry(local, System.currentTimeMillis(), 0);
            MessageProtocol.PeerMeshMessage newPeer = new MessageProtocol.PeerMeshMessage(
                    MessageProtocol.TYPE_NEW_PEER, Json.toJson(myEntry));
            MessageProtocol.write(out, newPeer);
            out.flush();
            log.debug("已向种子节点发送 NEW_PEER: {}", host + ":" + port);

            MessageProtocol.PeerMeshMessage response;
            try {
                response = MessageProtocol.read(in);
            } catch (java.io.EOFException e) {
                response = null;
            }
            NodeTable.NodeEntry seedEntry = null;
            if (response != null && response.type() == MessageProtocol.TYPE_NEW_PEER) {
                seedEntry = Json.fromJson(response.payload(), NodeTable.NodeEntry.class);
            }
            if (seedEntry != null && seedEntry.getDiscovery() != null) {
                discovered.add(new NodeTable.NodeEntry(seedEntry.getDiscovery(),
                        System.currentTimeMillis(), seedEntry.getEpoch()));
                log.debug("已从种子响应中解析真实节点: serverId={}", seedEntry.getDiscovery().getServerId());
            } else {
                log.warn("未能从种子节点 {} 解析 NEW_PEER 响应，回退到 host:port 构造", host + ":" + port);
                String fakeId = host + ":" + port;
                Discovery fallback = Discovery.builder()
                        .serverId(fakeId)
                        .host(host)
                        .port(port)
                        .protocol(config.getMode())
                        .build();
                discovered.add(new NodeTable.NodeEntry(fallback, System.currentTimeMillis(), 0));
            }
            in.close();
            out.close();
            socket.close();
        } catch (Exception e) {
            log.debug("种子节点连接失败: {}", seed, e);
        }
    }
}
