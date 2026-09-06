package com.chua.remote.core;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

/**
 * 远控网关服务端。
 *
 * <p>基于 {@link RemoteTransport} 构建，监听被控端和控制端的 WebSocket 连接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteServer {

    /** 传输层 */
    private final RemoteTransport transport;

    /** 加密是否启用 */
    private final boolean encrypt;

    /**
     * 创建网关服务端。
     *
     * @param setting 服务配置（含加密配置）
     */
    public RemoteServer(ServerSetting setting) {
        this.transport = new RemoteTransport(setting);
        this.encrypt = setting.isEncrypt();
    }

    /**
     * 启动网关服务端。
     */
    public void start() {
        transport.on(MessageType.SIGNAL, frame -> {
            log.debug("收到信令帧: sessionId={}", frame.getSessionId());
        });
        transport.on(MessageType.DATA, frame -> {
            log.debug("收到数据帧: sessionId={}", frame.getSessionId());
        });
        transport.on(MessageType.CTRL, frame -> {
            log.debug("收到控制帧: sessionId={}", frame.getSessionId());
        });
        transport.start();
        log.info("远控网关服务端已启动, encrypt={}", encrypt);
    }

    /**
     * 停止网关服务端。
     */
    public void stop() {
        transport.stop();
        log.info("远控网关服务端已停止");
    }

    /**
     * 获取传输层。
     *
     * @return 传输层
     */
    public RemoteTransport getTransport() {
        return transport;
    }
}
