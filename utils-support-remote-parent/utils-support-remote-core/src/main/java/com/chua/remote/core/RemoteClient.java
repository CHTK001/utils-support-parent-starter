package com.chua.remote.core;

import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

/**
 * 远控网关客户端。
 *
 * <p>基于 {@link RemoteTransport} 构建，连接到远控网关服务端。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteClient {

    /** 传输层 */
    private final RemoteTransport transport;

    /**
     * 创建网关客户端。
     *
     * @param serverUrl 网关地址
     */
    public RemoteClient(String serverUrl) {
        this.transport = new RemoteTransport(serverUrl);
    }

    /**
     * 连接到网关。
     */
    public void connect() {
        transport.start();
        log.info("远控网关客户端已连接: serverUrl={}", transport.getFlow().getServer() != null ? "server" : "client");
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        transport.stop();
        log.info("远控网关客户端已断开");
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
