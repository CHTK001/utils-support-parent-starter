package com.chua.common.support.network.sync.support;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncProtocol;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 同步服务端抽象基类：收编各传输实现共有的「客户端注册表 + 监听器管理 + 帧收发骨架」。
 *
 * <p>各传输实现（UDP/TCP/HTTP/WebSocket）只需提供：</p>
 * <ul>
 *     <li>{@link #sendToClient} —— 把一帧文本写到指定客户端（数据报 / 通道写）；</li>
 *     <li>连接建立、register 握手与 {@link #doStart()} / {@link #doStop()} 生命周期；</li>
 *     <li>可选覆写 {@link #afterSend} 补充发送后的副作用（如 UDP 的消息监听器通知）。</li>
 * </ul>
 *
 * <p>客户端表按 clientId 索引，值类型为 {@link SyncClientInfo} 的子类，
 * 传输私有状态（地址、通道、写队列）留在实现自身的 Client 结构里。</p>
 *
 * @param <C> 服务端视角的客户端信息类型
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public abstract class AbstractSyncServer<C extends SyncClientInfo> extends AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 客户端注册表（clientId -> 客户端信息）
     */
    protected final Map<String, C> clients = new ConcurrentHashMap<>();

    /**
     * 监听器列表
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建同步服务端。
     *
     * @param setting 服务端配置
     */
    protected AbstractSyncServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 获取协议类型标识。
     *
     * <p>{@code Server#getProtocol()}（default 实现）与 {@code SyncProtocol#getProtocol()}（抽象方法）
     * 同签名，抽象基类必须显式声明以消除菱形继承歧义；具体协议名由各传输实现返回。</p>
     *
     * @return 协议类型，如 udp / tcp
     */
    @Override
    public abstract String getProtocol();

    /**
     * 添加Listener
     */
    @Override
    public void addListener(SyncServerListener listener) {
        listeners.add(listener);
    }

    /**
     * 移除Listener
     */
    @Override
    public void removeListener(SyncServerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取ConnectedClients
     */
    @Override
    public List<String> getConnectedClients() {
        return Wraps.list(clients.keySet());
    }

    /**
     * 获取ClientMetadata
     */
    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        C client = clients.get(clientId);
        return client != null ? Collections.unmodifiableMap(client.getMetadata()) : Collections.emptyMap();
    }

    /**
     * 发布（向所有已注册客户端广播一帧）。
     */
    @Override
    public void publish(String topic, Object message) {
        String frame = SyncFrameCodec.encode(topic, message);
        for (C client : clients.values()) {
            sendToClient(client, frame);
        }
    }

    /**
     * 发送（向指定客户端定向发送一帧）。
     */
    @Override
    public void send(String clientId, String topic, Object message) {
        C client = clients.get(clientId);
        if (client == null) {
            return;
        }
        sendToClient(client, SyncFrameCodec.encode(topic, message));
        afterSend(clientId, topic, message);
    }

    /**
     * 把一帧文本写到指定客户端。
     *
     * @param client 客户端信息
     * @param frame  线路帧文本
     */
    protected abstract void sendToClient(C client, String frame);

    /**
     * 定向发送完成后的副作用钩子，默认无操作。
     *
     * <p>UDP 实现覆写为通知消息监听器；TCP 实现保持默认（服务端定向发送不回环通知）。</p>
     *
     * @param clientId 客户端标识
     * @param topic    主题
     * @param message  消息内容
     */
    protected void afterSend(String clientId, String topic, Object message) {
    }

    /**
     * 通知消息监听器（每消息热路径专用：避免每条消息分配捕获型 lambda）。
     *
     * @param clientId 客户端标识
     * @param topic    主题
     * @param payload  消息体
     */
    protected void notifyMessageListener(String clientId, String topic, Object payload) {
        for (SyncServerListener listener : listeners) {
            try {
                listener.onMessage(clientId, topic, payload);
            } catch (Exception e) {
                log.debug("[sync] 监听器回调执行失败，已忽略: {}", e.getMessage());
            }
        }
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    protected void notifyListener(Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.debug("[sync] 监听器回调执行失败，已忽略: {}", e.getMessage());
            }
        }
    }
}
