package com.chua.common.support.network.sync.netty;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlow;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;

import java.util.*;

/**
 * Netty WebSocket 同步流程管理器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class NettyWebSocketSyncFlow implements SyncFlow {

    /**
     * 客户端
    */
    private final NettyWebSocketSyncClient client;
    /**
     * 服务器
    */
    private final NettyHttpSyncServer server;
    /**
     * running
    */
    private volatile boolean running = false;
    /**
     * 监听器
    */
    private final List<SyncFlowListener> listeners = Wraps.list();

    /**
     * 创建 nettywebSocket同步流 实例
     * @param setting setting
     * @param serverUrl 字符串
     */
    public NettyWebSocketSyncFlow(com.chua.common.support.network.server.ServerSetting setting, String serverUrl) {
        this.server = new NettyHttpSyncServer(setting);
        this.client = new NettyWebSocketSyncClient(serverUrl);
    }

    /**
     * 创建 nettywebSocket同步流 实例
     * @param serverUrl 服务端url
     */
    public NettyWebSocketSyncFlow(String serverUrl) {
        this.server = null;
        this.client = new NettyWebSocketSyncClient(serverUrl);
    }

    /**
     * 创建 nettywebSocket同步流 实例
     * @param setting setting
     */
    public NettyWebSocketSyncFlow(com.chua.common.support.network.server.ServerSetting setting) {
        this.server = new NettyHttpSyncServer(setting);
        this.client = null;
    }

    /**
     * 开始
    */
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

    /**
     * 停止
    */
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

    /**
     * 是否Running
    */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取服务端
    */
    @Override
    public SyncServer getServer() {
        return server;
    }

    /**
     * 获取客户端
    */
    @Override
    public SyncClient getClient() {
        return client;
    }

    /**
     * 添加监听器
    */
    @Override
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
        if (server != null) {
            server.addListener(new SyncServerListener() {
                /**
                 * on客户端连接
                */
                @Override
                public void onClientConnected(String clientId, Map<String, Object> metadata) {
                    listener.onClientConnected(clientId);
                }

                /**
                 * on客户端断开连接
                */
                @Override
                public void onClientDisconnected(String clientId) {
                    listener.onClientDisconnected(clientId);
                }

                /**
                 * on消息
                */
                @Override
                public void onMessage(String clientId, String topic, Object message) {
                    listener.onMessage(topic, message);
                }

                /**
                 * On记录错误
                */
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

    /**
     * 移除监听器
    */
    @Override
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
        if (client != null) {
            client.removeListener(listener);
        }
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
        stop();
    }

    /**
     * 通知监听器
     *
     * @param action 动作
     */
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
