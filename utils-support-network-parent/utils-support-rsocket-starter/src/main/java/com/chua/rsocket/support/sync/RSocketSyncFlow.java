package com.chua.rsocket.support.sync;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlow;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.server.SyncServer;

import java.util.*;

/**
 * RSocket 同步流程管理器。
 *
 * @author CH
 * @since 2026-07-25
 */
public class RSocketSyncFlow implements SyncFlow {

    private final RSocketSyncClient client;
    private final RSocketSyncServer server;
    private volatile boolean running = false;
    private final List<SyncFlowListener> listeners = new ArrayList<>();

    public RSocketSyncFlow(com.chua.common.support.network.server.ServerSetting setting, String serverUrl) {
        this.server = new RSocketSyncServer(setting);
        this.client = new RSocketSyncClient(serverUrl);
    }

    public RSocketSyncFlow(String serverUrl) {
        this.server = null;
        this.client = new RSocketSyncClient(serverUrl);
    }

    public RSocketSyncFlow(com.chua.common.support.network.server.ServerSetting setting) {
        this.server = new RSocketSyncServer(setting);
        this.client = null;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (server != null) {
            server.start();
        }
        if (client != null) {
            client.connect();
        }
        running = true;
        notifyListeners(SyncFlowListener::onStart);
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        if (client != null) {
            client.disconnect();
        }
        if (server != null) {
            server.stop();
        }
        running = false;
        notifyListeners(SyncFlowListener::onStop);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public SyncServer getServer() {
        return server;
    }

    @Override
    public SyncClient getClient() {
        return client;
    }

    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
        if (server != null) {
            server.addListener(new com.chua.common.support.network.server.SyncServerListener() {
                @Override
                public void onClientConnected(String clientId, Map<String, Object> metadata) {
                    listener.onClientConnected(clientId);
                }

                @Override
                public void onClientDisconnected(String clientId) {
                    listener.onClientDisconnected(clientId);
                }

                @Override
                public void onMessage(String clientId, String topic, Object message) {
                    listener.onMessage(topic, message);
                }

                @Override
                public void onError(String clientId, Throwable cause) {
                    listener.onError(clientId, cause);
                }
            });
        }
        if (client != null) {
            client.addListener(listener);
        }
    }

    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
        if (client != null) {
            client.removeListener(listener);
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void notifyListeners(java.util.function.Consumer<SyncFlowListener> action) {
        for (SyncFlowListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
