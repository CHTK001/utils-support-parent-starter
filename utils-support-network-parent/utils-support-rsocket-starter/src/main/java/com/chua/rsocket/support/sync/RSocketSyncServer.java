package com.chua.rsocket.support.sync;

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
 * RSocket 同步服务端实现。
 * <p>
 * 委托给 {@link com.chua.rsocket.support.server.RSocketServer} 处理底层 RSocket 通信。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("rsocket")
public class RSocketSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    @Override
    /** 获取Protocol */
    public String getProtocol() {
        return "rsocket";
    }

    @Override
    /** 创建Server */
    public SyncServer createServer(ServerSetting setting) {
        return new RSocketSyncServer(setting);
    }

    @Override
    /** 创建Client */
    public SyncClient createClient(Object setting) {
        String url = "rsocket://" + (setting instanceof String ? (String) setting : "127.0.0.1:19380");
        return new RSocketSyncClient(url);
    }

    /**
     * clients
     */
    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = new ArrayList<>();
    /**
     * 委托对象
     */
    private final com.chua.rsocket.support.server.RSocketServer delegate;

    /**
     * 创建 RSocketSyncServer 实例
     * @param setting setting
     */
    public RSocketSyncServer(ServerSetting setting) {
        super(setting);
        this.delegate = new com.chua.rsocket.support.server.RSocketServer(setting);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        delegate.start();
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        delegate.stop();
        clients.clear();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object message) {
        delegate.publish(topic, message.toString());
        notifyListener(l -> l.onMessage("broadcast", topic, message));
    }

    @Override
    /** 发送 */
    public void send(String clientId, String topic, Object message) {
        delegate.publish(topic, message.toString());
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    @Override
    /** 获取ConnectedClients */
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    /** 获取ClientMetadata */
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    @Override
    /** 添加Listener */
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    /** 移除Listener */
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    /** 获取ProtocolType */
    public ProtocolType getProtocolType() {
        return ProtocolType.UNKNOWN;
    }

    /** 通知Listener */
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
