package com.chua.webrtc.config;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.webrtc.server.WebRtcServer;

/**
 * WebRtc 服务器自动配置（Properties 驱动）。
 *
 * <p>向身边容器注册 {@link WebRtcServer} Bean，并通过属性控制：</p>
 * <ul>
 *   <li>{@code utils.webrtc.server.port} — 信令端口（默认 10000）</li>
 *   <li>{@code utils.webrtc.server.protocol} — 协议类型（默认 WS）</li>
 *   <li>{@code utils.webrtc.server.auto-start} — 是否自动启动（默认 true）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class WebRtcAutoConfiguration {

    /**
     * 序列化前缀
     */
    public static final String PREFIX = "utils.webrtc";

    /**
     * 创建 WebRtc 服务器 Bean。
     *
     * @return WebRtcServer
     */
    public WebRtcServer webRtcServer(WebRtcProperties properties) {
        ServerSetting setting = ServerSetting.builder()
                .port(properties.getServer().getPort())
                .protocol(properties.getServer().getProtocol())
                .build();
        if (properties.getServer().isAutoStart()) {
            properties.getServer().setAutoStart(false);
            WebRtcServer server = new WebRtcServer(setting);
            server.start();
            return server;
        }
        return new WebRtcServer(setting);
    }

}
