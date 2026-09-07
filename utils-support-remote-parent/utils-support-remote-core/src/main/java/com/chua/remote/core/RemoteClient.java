package com.chua.remote.core;

import com.chua.remote.core.transport.RemoteTransport;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RemoteClient {

    private final RemoteTransport transport;

    public RemoteClient(String serverUrl) {
        this.transport = new RemoteTransport(serverUrl);
    }

    public RemoteClient(String clientId, String serverUrl) {
        this.transport = new RemoteTransport(clientId, serverUrl);
    }

    public void connect() {
        transport.start();
        log.info("远控网关客户端已连接");
    }

    public void disconnect() {
        transport.stop();
        log.info("远控网关客户端已断开");
    }

    public RemoteTransport getTransport() {
        return transport;
    }
}
