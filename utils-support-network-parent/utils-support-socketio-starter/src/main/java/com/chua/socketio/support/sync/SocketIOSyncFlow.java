package com.chua.socketio.support.sync;

import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlow;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;

import java.util.*;

/**
* 套接字.IO 同步流程管理器。
*
* @author CH
* @since 4.0.0.42
 */
public class SocketIOSyncFlow implements SyncFlow {

    /**
    * 客户端实例
     */
    private final SocketIOSyncClient client;
    /**
    * 服务器实例
     */
    private final SocketIOSyncServer server;
    /**
    * running
     */
    private volatile boolean running = false;
    /**
    * 监听器列表
     */
    private final List<SyncFlowListener> listeners = new ArrayList<>();

    /**
    * 创建 套接字io同步流 实例
    * @param setting setting
    * @param serverUrl 字符串
    * @param serverUrl 服务端url
     */
    public SocketIOSyncFlow(com.chua.common.support.network.server.ServerSetting setting, String serverUrl) {
        this.server = new SocketIOSyncServer(setting);
        this.client = new SocketIOSyncClient(serverUrl);
    }

    /**
    * 创建 套接字io同步流 实例
    * @param serverUrl 服务端url
     */
    public SocketIOSyncFlow(String serverUrl) {
        this.server = null;
        this.client = new SocketIOSyncClient(serverUrl);
    }

    /**
    * 创建 套接字io同步流 实例
    * @param setting setting
     */
    public SocketIOSyncFlow(com.chua.common.support.network.server.ServerSetting setting) {
        this.server = new SocketIOSyncServer(setting);
        this.client = null;
    }

    @Override
    /** 开始 */
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
    /** 停止 */
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
    /** 是否Running */
    public boolean isRunning() {
        return running;
    }

    @Override
    /** 获取服务端 */
    public SyncServer getServer() {
        return server;
    }

    @Override
    /** 获取客户端 */
    public SyncClient getClient() {
        return client;
    }

    @Override
    /** 添加监听器 */
    public void addListener(SyncFlowListener listener) {
        listeners.add(listener);
        if (server != null) {
            server.addListener(new SyncServerListener() {
                @Override
                /** on客户端连接 */
                public void onClientConnected(String clientId, Map<String, Object> metadata) {
                    listener.onClientConnected(clientId);
                }

                @Override
                /** on客户端断开连接 */
                public void onClientDisconnected(String clientId) {
                    listener.onClientDisconnected(clientId);
                }

                @Override
                /** on消息 */
                public void onMessage(String clientId, String topic, Object message) {
                    listener.onMessage(topic, message);
                }

                @Override
                /** On记录错误 */
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
    /** 移除监听器 */
    public void removeListener(SyncFlowListener listener) {
        listeners.remove(listener);
        if (client != null) {
            client.removeListener(listener);
        }
    }

    @Override
    /** 关闭 */
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
