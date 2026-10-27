package com.chua.socketio.support.sync;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Socket.IO 同步服务端实现。
 * <p>
 * 委托给 {@link com.chua.socketio.support.server.SocketIOServer} 处理底层 Socket.IO 通信。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("socketio")
public class SocketIOSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 获取协议
    */
    @Override
    public String getProtocol() {
        return "socketio";
    }

    /**
     * 创建服务端
    */
    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new SocketIOSyncServer(setting);
    }

    /**
     * 创建客户端
    */
    @Override
    public SyncClient createClient(Object setting) {
        String url = "http://" + (setting instanceof String ? (String) setting : "127.0.0.1:19380");
        return new SocketIOSyncClient(url);
    }

    /**
     * 客户端
     */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = Wraps.list();
    /**
     * 委托对象
     */
    private final com.chua.socketio.support.server.SocketIOServer delegate;

    /**
     * 创建 Socketio同步服务端 实例
     * @param setting setting
     */
    public SocketIOSyncServer(ServerSetting setting) {
        super(setting);
        this.delegate = new com.chua.socketio.support.server.SocketIOServer(setting);
    }

    /**
     * 执行开始
    */
    @Override
    protected void doStart() {
        delegate.start();
    }

    /**
     * 执行停止
    */
    @Override
    protected void doStop() {
        delegate.stop();
        clients.clear();
    }

    /**
     * 发布
    */
    @Override
    public void publish(String topic, Object message) {
        delegate.publish(topic, message.toString());
        notifyListener(l -> l.onMessage("broadcast", topic, message));
    }

    /**
     * 发送
    */
    @Override
    public void send(String clientId, String topic, Object message) {
        delegate.publish(topic, message.toString());
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    /**
     * 获取连接客户端
    */
    @Override
    public List<String> getConnectedClients() {
        return Wraps.list(clients.keySet());
    }

    /**
     * 获取客户端metadata
    */
    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    /**
     * 添加监听器
    */
    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除监听器
    */
    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取协议类型
    */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.UNKNOWN;
    }

    /**
     * 通知监听器
     *
     * @param action 动作
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
