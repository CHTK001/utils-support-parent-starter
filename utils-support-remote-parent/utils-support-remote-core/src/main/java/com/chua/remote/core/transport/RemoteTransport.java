package com.chua.remote.core.transport;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncFlowListener;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 远控传输层。
 *
 * <p>基于 {@link SyncServer}/{@link SyncClient}（TCP 同步族）封装，提供 Frame 级别的消息收发。
 * 底层传输经 SPI 加载 {@code tcp} 扩展（{@code TcpSyncServer}/{@code TcpSyncClient}），
 * 不直连具体实现类。</p>
 *
 * <p>跨进程统一使用线格式（{@link FrameCodec#encodeWire}）：二进制载荷以 Base64 承载，
 * 避免行协议字符集转换损坏；接收端按主题还原完整 Frame（含载荷与会话标识）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RemoteTransport {

    /** 服务端传输（服务端模式）——经 SPI 加载的 TCP 同步服务端 */
    private final SyncServer server;

    /** 客户端传输（客户端模式）——经 SPI 加载的 TCP 同步客户端 */
    private final SyncClient client;

    /** 订阅处理器（同一类型支持多个处理器，按注册顺序调用） */
    private final Map<MessageType, List<FrameHandler>> handlers = new ConcurrentHashMap<>();

    /**
     * 创建服务端传输。
     *
     * @param setting 服务配置（含加密配置）
     */
    public RemoteTransport(ServerSetting setting) {
        this.server = ServiceProvider.of(SyncServer.class).getNewExtension("tcp", setting);
        this.client = null;
    }

    /**
     * 创建客户端传输（随机连接标识）。
     *
     * @param serverUrl 服务端地址
     */
    public RemoteTransport(String serverUrl) {
        this.client = ServiceProvider.of(SyncClient.class).getNewExtension("tcp", serverUrl);
        this.server = null;
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
        this.client = ServiceProvider.of(SyncClient.class).getNewExtension("tcp", clientId, serverUrl);
        this.server = null;
    }

    /**
     * 启动传输。
     */
    public void start() {
        if (server != null) {
            server.addListener(new SyncServerListener() {
                @Override
                public void onMessage(String clientId, String topic, Object message) {
                    dispatch(topic, message);
                }
            });
            server.start();
            log.info("远控 TCP 服务端传输已启动");
        }
        if (client != null) {
            client.addListener(new SyncFlowListener() {
                @Override
                public void onMessage(String topic, Object message) {
                    dispatch(topic, message);
                }
            });
            client.connect();
            log.info("远控 TCP 客户端传输已连接");
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
        client.send(frame.getType().name(), FrameCodec.encodeWire(frame));
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
        server.publish(frame.getType().name(), FrameCodec.encodeWire(frame));
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
        server.send(clientId, frame.getType().name(), FrameCodec.encodeWire(frame));
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
     * 分发帧消息到订阅处理器。
     *
     * @param topic   消息主题（帧类型名）
     * @param message 消息体（Frame 或线格式字符串）
     */
    private void dispatch(String topic, Object message) {
        Frame frame = resolveFrame(topic, message);
        if (frame == null) {
            return;
        }
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
     * 将传输层原始消息还原为 Frame。
     *
     * <p>优先按线格式解码；失败时按裸载荷兜底（仅主题与文本载荷）。</p>
     *
     * @param topic   消息主题
     * @param message 传输层原始消息
     * @return 帧，无法识别时返回 null
     */
    private Frame resolveFrame(String topic, Object message) {
        if (message instanceof Frame frame) {
            return frame;
        }
        if (message instanceof String text) {
            Frame frame = FrameCodec.decodeWire(text);
            if (frame != null) {
                return frame;
            }
            try {
                return Frame.builder()
                        .type(MessageType.valueOf(topic))
                        .payload(text.getBytes(StandardCharsets.UTF_8))
                        .build();
            } catch (IllegalArgumentException e) {
                log.warn("未知帧类型主题，忽略: {}", topic);
            }
        }
        return null;
    }

    /**
     * 帧处理器接口。
     */
    @FunctionalInterface
    public interface FrameHandler {
        void handle(Frame frame);
    }
}
