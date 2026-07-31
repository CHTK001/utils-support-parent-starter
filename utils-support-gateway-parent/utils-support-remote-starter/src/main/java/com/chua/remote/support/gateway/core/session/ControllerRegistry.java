package com.chua.remote.support.gateway.core.session;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 控制端连接注册表。
 * <p>跟踪所有通过 DWS（桌面 WebSocket）端口连接到网关的控制端（浏览器客户端）。
 * 提供注册、注销、活性扫描、强制踢出等功能，用于管理远程桌面控制场景下的前端连接。</p>
 *
 * @author CH
 */
@Slf4j
public class ControllerRegistry {

    /** 控制端 ID -> 控制端条目 的映射 */
    private final Map<String, ControllerEntry> controllers = new ConcurrentHashMap<>();

    /**
     * 注册一个控制端连接，默认类型为 "web"。
     *
     * @param channel 控制端的 Netty 通道
     */
    public void register(Channel channel) {
        register(channel, "web");
    }

    /**
     * 注册一个控制端连接。
     * <p>使用通道的短标识作为控制端 ID，提取远程 IP 地址一并存储。</p>
     *
     * @param channel 控制端的 Netty 通道
     * @param type    控制端类型（如 "web" / "app"）
     */
    public void register(Channel channel, String type) {
        String id = channel.id().asShortText();
        String ip = "unknown";
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            ip = addr.getHostString();
        }
        controllers.put(id, new ControllerEntry(id, ip, Instant.now(), true, channel, type));
        log.debug("[ControllerRegistry] 注册控制端: {} ip={} type={}", id, ip, type);
    }

    /**
     * 注销一个控制端连接。
     *
     * @param channel 控制端的 Netty 通道
     */
    public void unregister(Channel channel) {
        String id = channel.id().asShortText();
        ControllerEntry removed = controllers.remove(id);
        if (removed != null) {
            log.debug("[ControllerRegistry] 注销控制端: {} ip={}", id, removed.ipAddress);
        }
    }

    /**
     * 清理所有不活跃（通道已关闭）的控制端连接。
     * <p>周期性扫描注册表，移除那些 Netty 通道已处于非活跃状态的条目。</p>
     *
     * @return 本次清理的数量
     */
    public int sweep() {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, ControllerEntry> entry : controllers.entrySet()) {
            Channel ch = entry.getValue().channel;
            if (ch == null || !ch.isActive()) {
                stale.add(entry.getKey());
            }
        }
        for (String id : stale) {
            ControllerEntry removed = controllers.remove(id);
            if (removed != null) {
                log.info("[ControllerRegistry] 清理失效控制端: {} ip={}", id, removed.ipAddress);
            }
        }
        if (stale.size() > 0) {
            log.info("[ControllerRegistry] 已清理 {} 个失效控制端, 剩余 {}", stale.size(), controllers.size());
        }
        return stale.size();
    }

    /**
     * 踢出指定控制端连接。
     * <p>从注册表中移除并关闭对应的 Netty 通道。</p>
     *
     * @param id 控制端 ID
     * @return 是否成功踢出
     */
    public boolean kickController(String id) {
        ControllerEntry entry = controllers.remove(id);
        if (entry != null) {
            Channel ch = entry.channel;
            if (ch != null && ch.isActive()) {
                ch.close();
            }
            log.info("[ControllerRegistry] 踢出控制端: {} ip={}", id, entry.ipAddress);
            return true;
        }
        return false;
    }

    /**
     * 获取所有控制端信息（只读快照）。
     *
     * @return 控制端信息列表
     */
    public List<ControllerInfo> allControllers() {
        List<ControllerInfo> result = new ArrayList<>();
        for (ControllerEntry entry : controllers.values()) {
            result.add(new ControllerInfo(entry.id, entry.ipAddress, entry.connectedAt, entry.active.get(), entry.type));
        }
        return result;
    }

    /**
     * 获取当前控制端连接数。
     *
     * @return 已注册的控制端数量
     */
    public int count() {
        return controllers.size();
    }

    /**
     * 内部条目，持有完整的 Channel 引用用于活性检测。
     * <p>不对外暴露，通过 {@link ControllerInfo} 提供只读信息。</p>
     */
    private static class ControllerEntry {
        /** 控制端唯一标识（通道短 ID） */
        final String id;
        /** 远程 IP 地址 */
        final String ipAddress;
        /** 连接建立时间 */
        final Instant connectedAt;
        /** 活跃状态标志 */
        final AtomicBoolean active;
        /** Netty 通道引用 */
        final Channel channel;
        /** 控制端类型（web / app 等） */
        final String type;

        ControllerEntry(String id, String ipAddress, Instant connectedAt, boolean active, Channel channel, String type) {
            this.id = id;
            this.ipAddress = ipAddress;
            this.connectedAt = connectedAt;
            this.active = new AtomicBoolean(active);
            this.channel = channel;
            this.type = type != null ? type : "web";
        }
    }

    /**
     * 控制端只读信息记录。
     *
     * @param id          控制端 ID
     * @param ipAddress   远程 IP 地址
     * @param connectedAt 连接建立时间
     * @param active      是否活跃
     * @param type        控制端类型
     */
    public record ControllerInfo(String id, String ipAddress, Instant connectedAt, boolean active, String type) {}
}
