package com.chua.gateway.server.bridge;

import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * RustDesk 协议桥接器（占位骨架）。
 *
 * <p>后续实现方向：
 *   <ul>
 *     <li>对接 RustDesk hbbs/hbbr 服务端（开源 ID 路由服务）</li>
 *     <li>实现 NAT 穿透后的 P2P 数据通道</li>
 *     <li>支持从 RustDesk 客户端 hbbs 提取 P2P 密钥交换材料</li>
 *   </ul>
 * </p>
 *
 * <p>本轮不实现具体协议，仅占位避免编译失败。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RustdeskBridge implements RemoteBridge {

    /**
     * 底层连接
     */
    private final Connection connection;

    /**
     * 是否已连接标志
     */
    private volatile boolean connected;

    /**
     * 创建 RustdeskBridge 实例
     * @param connection connection
     */
    public RustdeskBridge(Connection connection) {
        this.connection = connection;
    }

    @Override
    /** Connection */
    public Connection connection() {
        return connection;
    }

    @Override
    /** 连接 */
    public void connect() throws Exception {
        // 预留：后续接入 RustDesk hbbs/hbbr 协议
        log.warn("[gateway-server] RustdeskBridge 连接尚未实现，预留给后续 Phase E");
        connected = true;
    }

    @Override
    /** 断开 */
    public void disconnect() {
        connected = false;
    }

    @Override
    /** 是否Connected */
    public boolean isConnected() {
        return connected;
    }

    @Override
    /** 写入ToRemote */
    public void writeToRemote(byte[] bytes) throws IOException {
        throw new IOException("RustdeskBridge 协议尚未实现（Phase E 占位）");
    }

    @Override
    /** 读取FromRemote */
    public byte[] readFromRemote() throws IOException {
        throw new IOException("RustdeskBridge 协议尚未实现（Phase E 占位）");
    }
}
