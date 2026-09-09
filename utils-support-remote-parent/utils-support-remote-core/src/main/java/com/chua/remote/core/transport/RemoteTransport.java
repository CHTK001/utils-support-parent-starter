package com.chua.remote.core.transport;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 远控传输层。
 *
 * <p>基于内置零拷贝帧引擎（{@link FrameServer}/{@link FrameClient}）封装，
 * 提供 Frame 级别的消息收发。链路为二进制定长前缀帧：
 * 无缓冲流、无 Base64、无字符串中转，载荷零复制。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteTransport {

    /** 服务端引擎（服务端模式） */
    private final FrameServer server;

    /** 客户端引擎（客户端模式） */
    private final FrameClient client;

    /** 订阅处理器（同一类型支持多个处理器，按注册顺序调用） */
    private final Map<MessageType, List<FrameHandler>> handlers = new ConcurrentHashMap<>();

    /**
     * 创建服务端传输。
     *
     * @param setting 服务配置（含加密配置）
     */
    public RemoteTransport(ServerSetting setting) {
        this.server = new FrameServer(setting);
        this.client = null;
    }

    /**
     * 创建客户端传输（随机连接标识）。
     *
     * @param serverUrl 服务端地址
     */
    public RemoteTransport(String serverUrl) {
        this.server = null;
        this.client = new FrameClient(UUID.randomUUID().toString(), serverUrl);
    }

    /**
     * 创建客户端传输（显式连接标识）。
     *
     * <p>连接标识即服务端定向路由的 clientId，
     * 被控端应传入被控端 id、控制端应传入接入令牌，以便网关按身份定向发送。</p>
     *
     * @param clientId  连接标识
     * @param serverUrl 服务端地址
     */
    public RemoteTransport(String clientId, String serverUrl) {
        this.server = null;
        this.client = new FrameClient(clientId, serverUrl);
    }

    /**
     * 启动传输。
     */
    public void start() {
        if (server != null) {
            server.setListener(new FrameServer.FrameListener() {
                @Override
                public void onFrame(String clientId, Frame frame) {
                    dispatch(frame);
                }
            });
            server.start();
            log.info("远控零拷贝服务端传输已启动");
        }
        if (client != null) {
            client.setListener(this::dispatch);
            client.connect();
            log.info("远控零拷贝客户端传输已连接");
        }
    }

    /**
     * 停止传输。
     */
    public void stop() {
        if (server != null) {
            server.stop();
        }
        if (client != null) {
            client.disconnect();
        }
    }

    /**
     * 发送帧（通过客户端）。
     *
     * @param frame 帧
     */
    public void send(Frame frame) {
        if (client == null) {
            log.warn("客户端未连接，无法发送");
            return;
        }
        client.send(frame);
    }

    /**
     * 客户端连接状态（供断连重连判定）。
     *
     * @return 已连接
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * 广播帧（通过服务端）。
     *
     * @param frame 帧
     */
    public void publish(Frame frame) {
        if (server == null) {
            log.warn("服务端未启动，无法广播");
            return;
        }
        server.publish(frame);
    }

    /**
     * 向指定客户端发送帧（通过服务端）。
     *
     * @param clientId 客户端连接标识
     * @param frame    帧
     */
    public void send(String clientId, Frame frame) {
        if (server == null) {
            log.warn("服务端未启动，无法发送");
            return;
        }
        server.send(clientId, frame);
    }

    /**
     * 注册消息处理器。
     *
     * <p>同一消息类型可注册多个处理器，按注册顺序依次调用，
     * 单个处理器异常不影响后续处理器。</p>
     *
     * @param type    消息类型
     * @param handler 处理器
     */
    public void on(MessageType type, FrameHandler handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * 分发帧到订阅处理器。
     *
     * @param frame 帧
     */
    public void dispatch(Frame frame) {
        List<FrameHandler> handlerList = handlers.get(frame.getType());
        if (handlerList == null || handlerList.isEmpty()) {
            return;
        }
        for (FrameHandler handler : handlerList) {
            try {
                handler.handle(frame);
            } catch (Exception e) {
                log.warn("帧处理器执行异常: type={}, sessionId={}", frame.getType(), frame.getSessionId(), e);
            }
        }
    }

    /**
     * 帧处理器接口。
     */
    @FunctionalInterface
    public interface FrameHandler {
        void handle(Frame frame);
    }
}
