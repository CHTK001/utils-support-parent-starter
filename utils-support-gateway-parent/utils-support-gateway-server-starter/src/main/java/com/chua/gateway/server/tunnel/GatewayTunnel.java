package com.chua.gateway.server.tunnel;

import com.chua.common.support.network.tunnel.Tunnel;
import com.chua.common.support.network.tunnel.TunnelInfo;
import com.chua.common.support.network.tunnel.TunnelStatus;
import com.chua.gateway.server.bridge.RemoteBridge;
import com.chua.gateway.server.store.Connection;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 远控 Tunnel 抽象（实现 common-starter {@link Tunnel} 接口）。
 *
 * <p>Tunnel 是 controller ↔ server ↔ 被控主机 三段链路的逻辑抽象。
 * 一个 Tunnel 实例对应一个被控主机的远控会话，包含：</p>
 * <ul>
 *   <li>id（UUID）— 浏览器用此拼接 wsUrl</li>
 *   <li>connection — 远端主机信息</li>
 *   <li>bridge — 协议相关的远程连接器（VNC/SSH/RDP）</li>
 *   <li>infoCallback — 状态变更回调（用于监控）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GatewayTunnel implements Tunnel {

    /**
     * Tunnel ID
     */
    private final String id;

    /**
     * 远端连接信息
     */
    private final Connection connection;

    /**
     * 协议桥接器（主进程内或经 guacd 子进程）
     */
    private final RemoteBridge bridge;

    /**
     * 状态变更回调
     */
    private final List<Consumer<TunnelInfo>> callbacks = new CopyOnWriteArrayList<>();

    /**
     * Tunnel 当前状态
     */
    private volatile TunnelStatus status = TunnelStatus.CLOSED;

    /**
     * GatewayTunnel 不可直接 new（必须通过 {@link #of(String, Connection, RemoteBridge)} 静态工厂）。
     */
    private GatewayTunnel(String id, Connection connection, RemoteBridge bridge) {
        this.id = id;
        this.connection = connection;
        this.bridge = bridge;
    }

    /**
     * 静态工厂：创建 GatewayTunnel。
     *
     * @param id         Tunnel ID
     * @param connection 远端连接信息
     * @param bridge     协议桥接器
     * @return 新实例
     */
    public static GatewayTunnel of(String id, Connection connection, RemoteBridge bridge) {
        return new GatewayTunnel(id, connection, bridge);
    }

    /**
     * Tunnel ID
     */
    public String id() {
        return id;
    }

    /**
     * 底层连接信息
     */
    public Connection connection() {
        return connection;
    }

    /**
     * 协议桥接器
     */
    public RemoteBridge bridge() {
        return bridge;
    }

    @Override
    /** 打开 */
    public int open() {
        if (bridge == null) {
            updateStatus(TunnelStatus.OPEN);
            log.info("[gateway-server] Tunnel 暂存（无桥接器）: id={} target={}:{}",
                    id, connection.host(), connection.port());
            return 0;
        }
        try {
            bridge.connect();
            updateStatus(TunnelStatus.OPEN);
            log.info("[gateway-server] Tunnel 启动: id={} protocol={} target={}:{}",
                    id, connection.protocol(), connection.host(), connection.port());
            return 0;
        } catch (Exception e) {
            log.error("[gateway-server] Tunnel 启动失败: id={} err={}", id, e.getMessage());
            updateStatus(TunnelStatus.CLOSED);
            throw new RuntimeException("Tunnel 启动失败", e);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (bridge == null) {
            updateStatus(TunnelStatus.CLOSED);
            return;
        }
        try {
            bridge.disconnect();
            log.info("[gateway-server] Tunnel 关闭: id={}", id);
        } catch (Exception e) {
            log.warn("[gateway-server] Tunnel 关闭异常: id={} err={}", id, e.getMessage());
        } finally {
            updateStatus(TunnelStatus.CLOSED);
        }
    }

    @Override
    /** 是否打开 */
    public boolean isOpen() {
        return status == TunnelStatus.OPEN;
    }

    @Override
    /** 获取Info */
    public TunnelInfo getInfo() {
        return TunnelInfo.of(
                0,
                status,
                null,
                null,
                connection.host(),
                connection.port());
    }

    @Override
    /** OnInfo */
    public void onInfo(Consumer<TunnelInfo> callback) {
        if (callback != null) {
            callbacks.add(callback);
        }
    }

    /** 更新Status */
    private void updateStatus(TunnelStatus newStatus) {
        TunnelStatus old = this.status;
        if (old == newStatus) {
            return;
        }
        this.status = newStatus;
        TunnelInfo info = getInfo();
        for (Consumer<TunnelInfo> cb : callbacks) {
            try {
                cb.accept(info);
            } catch (Exception e) {
                log.warn("[gateway-server] Tunnel 状态回调失败: {}", e.getMessage());
            }
        }
    }
}
