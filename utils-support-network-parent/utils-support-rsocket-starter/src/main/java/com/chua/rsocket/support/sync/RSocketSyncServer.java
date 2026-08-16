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
    public String getProtocol() {
        return "rsocket";
    }

    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new RSocketSyncServer(setting);
    }

    @Override
    public SyncClient createClient(Object setting) {
        String url = "rsocket://" + (setting instanceof String ? (String) setting : "127.0.0.1:19380");
        return new RSocketSyncClient(url);
    }

    private final Map<String, Map<String, Object>> clients = new ConcurrentHashMap<>();
    private final List<SyncServerListener> listeners = new ArrayList<>();
    private final com.chua.rsocket.support.server.RSocketServer delegate;

    public RSocketSyncServer(ServerSetting setting) {
        super(setting);
        this.delegate = new com.chua.rsocket.support.server.RSocketServer(setting);
    }

    @Override
    protected void doStart() {
        delegate.start();
    }

    @Override
    protected void doStop() {
        delegate.stop();
        clients.clear();
    }

    @Override
    public void publish(String topic, Object message) {
        delegate.publish(topic, message.toString());
        notifyListener(l -> l.onMessage("broadcast", topic, message));
    }

    @Override
    public void send(String clientId, String topic, Object message) {
        delegate.publish(topic, message.toString());
        notifyListener(l -> l.onMessage(clientId, topic, message));
    }

    @Override
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        Map<String, Object> meta = clients.get(clientId);
        return meta != null ? Collections.unmodifiableMap(meta) : Collections.emptyMap();
    }

    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.UNKNOWN;
    }

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
