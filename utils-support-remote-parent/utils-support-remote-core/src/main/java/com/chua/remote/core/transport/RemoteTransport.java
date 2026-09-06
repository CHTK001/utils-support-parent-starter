package com.chua.remote.core.transport;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.netty.NettyWebSocketSyncFlow;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远控传输层。
 *
 * <p>基于 {@link NettyWebSocketSyncFlow} 封装，提供 Frame 级别的消息收发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteTransport {

    /** 流程 */
    private final NettyWebSocketSyncFlow flow;

    /** 订阅处理器 */
    private final Map<MessageType, FrameHandler> handlers = new ConcurrentHashMap<>();

    /**
     * 创建服务端传输。
     *
     * @param setting 服务配置（含加密配置）
     */
    public RemoteTransport(ServerSetting setting) {
        this.flow = new NettyWebSocketSyncFlow(setting);
    }

    /**
     * 创建客户端传输。
     *
     * @param serverUrl 服务端地址
     */
    public RemoteTransport(String serverUrl) {
        this.flow = new NettyWebSocketSyncFlow(serverUrl);
    }

    /**
     * 启动传输。
     */
    public void start() {
        flow.start();
        flow.addListener(new com.chua.common.support.network.sync.SyncFlowListener() {
            @Override
            public void onClientConnected(String clientId) {
                log.info("远程连接建立: clientId={}", clientId);
            }

            @Override
            public void onClientDisconnected(String clientId) {
                log.info("远程连接断开: clientId={}", clientId);
            }

            @Override
            public void onMessage(String topic, Object message) {
                if (message instanceof Frame) {
                    Frame frame = (Frame) message;
                    FrameHandler handler = handlers.get(frame.getType());
                    if (handler != null) {
                        handler.handle(frame);
                    }
                }
            }
        });
    }

    /**
     * 停止传输。
     */
    public void stop() {
        flow.stop();
    }

    /**
     * 发送帧（通过客户端）。
     *
     * @param frame 帧
     */
    public void send(Frame frame) {
        SyncClient client = flow.getClient();
        if (client == null) {
            log.warn("客户端未连接，无法发送");
            return;
        }
        String topic = frame.getType().name();
        byte[] payload = frame.getPayload();
        String msg = payload != null ? new String(payload, java.nio.charset.StandardCharsets.UTF_8) : "";
        client.send(topic, msg);
    }

    /**
     * 广播帧（通过服务端）。
     *
     * @param frame 帧
     */
    public void publish(Frame frame) {
        SyncServer server = flow.getServer();
        if (server == null) {
            log.warn("服务端未启动，无法广播");
            return;
        }
        String topic = frame.getType().name();
        byte[] payload = frame.getPayload();
        String msg = payload != null ? new String(payload, java.nio.charset.StandardCharsets.UTF_8) : "";
        server.publish(topic, msg);
    }

    /**
     * 向指定客户端发送帧（通过服务端）。
     *
     * @param clientId 客户端 id
     * @param frame    帧
     */
    public void send(String clientId, Frame frame) {
        SyncServer server = flow.getServer();
        if (server == null) {
            log.warn("服务端未启动，无法发送");
            return;
        }
        String topic = frame.getType().name();
        byte[] payload = frame.getPayload();
        String msg = payload != null ? new String(payload, java.nio.charset.StandardCharsets.UTF_8) : "";
        server.send(clientId, topic, msg);
    }

    /**
     * 注册消息处理器。
     *
     * @param type    消息类型
     * @param handler 处理器
     */
    public void on(MessageType type, FrameHandler handler) {
        handlers.put(type, handler);
    }

    /**
     * 获取底层 Flow。
     *
     * @return Flow
     */
    public NettyWebSocketSyncFlow getFlow() {
        return flow;
    }

    /**
     * 帧处理器接口。
     */
    @FunctionalInterface
    public interface FrameHandler {
        void handle(Frame frame);
    }
}
