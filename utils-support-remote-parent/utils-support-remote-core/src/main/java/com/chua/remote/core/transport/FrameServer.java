package com.chua.remote.core.transport;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远控零拷贝帧服务端。
 *
 * <p>基于 NIO 阻塞通道 + 虚拟线程，一连接一线程；帧为二进制定长前缀格式
 * （{@link FrameCodec#writeBinary}/{@link FrameCodec#readBinary}），
 * 全程无缓冲流、无字符集转换、载荷零复制。</p>
 *
 * <p>连接建立后首帧必须为 hello 控制帧（元数据携带 clientId），
 * 服务端以 clientId 注册连接表并回执 hello-ack，之后方可收发业务帧。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FrameServer {

    /** hello 元数据键：连接标识 */
    public static final String META_CLIENT_ID = "clientId";

    /** hello 元数据键：帧种类 */
    private static final String META_KIND = FrameCodec.METADATA_KIND;

    /** 监听配置 */
    private final ServerSetting setting;

    /** 连接表：clientId -> 连接 */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /** 事件监听器 */
    private volatile FrameListener listener;

    /** 服务端通道 */
    private volatile ServerSocketChannel serverChannel;

    /** 运行状态 */
    private volatile boolean running;

    /**
     * 创建帧服务端。
     *
     * @param setting 监听配置
     */
    public FrameServer(ServerSetting setting) {
        this.setting = setting;
    }

    /**
     * 设置事件监听器。
     *
     * @param listener 监听器
     */
    public void setListener(FrameListener listener) {
        this.listener = listener;
    }

    /**
     * 启动服务端并开始接受连接。
     */
    public void start() {
        if (running) {
            return;
        }
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), setting.getBacklog());
        } catch (IOException e) {
            throw new RuntimeException("FrameServer 启动失败: " + setting.getHost() + ":" + setting.getPort(), e);
        }
        running = true;
        Thread.ofVirtual().name("remote-frame-accept-" + setting.getPort()).start(this::acceptLoop);
        log.info("远控帧服务端已启动: {}:{}", setting.getHost(), setting.getPort());
    }

    /**
     * 停止服务端并断开全部连接。
     */
    public void stop() {
        running = false;
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
        serverChannel = null;
        for (Connection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
        log.info("远控帧服务端已停止");
    }

    /**
     * 向指定连接发送帧。
     *
     * @param clientId 连接标识
     * @param frame    帧
     */
    public void send(String clientId, Frame frame) {
        Connection connection = connections.get(clientId);
        if (connection == null) {
            log.debug("连接不存在，丢弃帧: clientId={}, type={}", clientId, frame.getType());
            return;
        }
        connection.write(frame);
    }

    /**
     * 向全部连接广播帧。
     *
     * @param frame 帧
     */
    public void publish(Frame frame) {
        for (Connection connection : connections.values()) {
            connection.write(frame);
        }
    }

    /**
     * 获取已注册连接标识。
     *
     * @return 连接标识列表
     */
    public List<String> getConnectedClients() {
        return new ArrayList<>(connections.keySet());
    }

    /**
     * 接受连接循环（虚拟线程执行）。
     */
    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel channel = serverChannel.accept();
                if (channel == null) {
                    continue;
                }
                channel.socket().setTcpNoDelay(true);
                Thread.ofVirtual().name("remote-frame-conn").start(() -> serve(channel));
            } catch (IOException e) {
                if (running) {
                    log.error("远控帧服务端 accept 异常", e);
                }
            }
        }
    }

    /**
     * 服务单个连接：首帧 hello 注册，之后逐帧读取分发（虚拟线程执行）。
     *
     * @param channel 连接通道
     */
    private void serve(SocketChannel channel) {
        String clientId = null;
        try {
            while (running) {
                Frame frame = FrameCodec.readBinary(channel);
                if (clientId == null) {
                    clientId = register(frame, channel);
                    if (clientId == null) {
                        break;
                    }
                    continue;
                }
                FrameListener current = listener;
                if (current != null) {
                    current.onFrame(clientId, frame);
                }
            }
        } catch (IOException e) {
            log.debug("连接结束: clientId={}, cause={}", clientId, e.getMessage());
        } catch (Exception e) {
            log.warn("连接处理异常，断开: clientId={}", clientId, e);
        } finally {
            if (clientId != null) {
                connections.remove(clientId);
                FrameListener current = listener;
                if (current != null) {
                    current.onDisconnected(clientId);
                }
            }
            closeQuietly(channel);
        }
    }

    /**
     * 处理首帧 hello 注册并回执。
     *
     * @param frame   首帧
     * @param channel 连接通道
     * @return 连接标识，首帧非法时返回 null
     */
    private String register(Frame frame, SocketChannel channel) {
        if (frame.getType() != MessageType.CTRL || frame.getMetadata() == null
                || frame.getMetadata().get(META_CLIENT_ID) == null) {
            log.warn("首帧非合法 hello，拒绝连接");
            return null;
        }
        String clientId = frame.getMetadata().get(META_CLIENT_ID);
        connections.put(clientId, new Connection(channel));
        Frame ack = Frame.builder()
                .type(MessageType.CTRL)
                .metadata(Map.of(META_KIND, "hello-ack", META_CLIENT_ID, clientId))
                .build();
        connections.get(clientId).write(ack);
        FrameListener current = listener;
        if (current != null) {
            current.onConnected(clientId);
        }
        log.debug("连接注册成功: clientId={}", clientId);
        return clientId;
    }

    /**
     * 静默关闭通道。
     *
     * @param channel 通道
     */
    private void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 单个连接封装：串行化写（多线程路由/广播并发写同一连接）。
     */
    private static final class Connection {

        /** 连接通道 */
        private final SocketChannel channel;

        /**
         * 创建连接。
         *
         * @param channel 连接通道
         */
        private Connection(SocketChannel channel) {
            this.channel = channel;
        }

        /**
         * 写出帧（串行化）。
         *
         * @param frame 帧
         */
        private void write(Frame frame) {
            synchronized (channel) {
                try {
                    FrameCodec.writeBinary(channel, frame);
                } catch (IOException e) {
                    log.debug("帧写出失败，连接将断开: {}", e.getMessage());
                    closeQuietly(channel);
                }
            }
        }

         /**
          * 关闭连接。
          */
         private void close() {
             synchronized (channel) {
                 closeQuietly(channel);
             }
         }

         /** 安全关闭通道 */
         private static void closeQuietly(SocketChannel channel) {
             if (channel != null) {
                 try {
                     channel.close();
                 } catch (IOException ignored) {
                 }
             }
         }
     }

    /**
     * 帧服务端事件监听器。
     */
    public interface FrameListener {

        /**
         * 收到业务帧。
         *
         * @param clientId 连接标识
         * @param frame    帧
         */
        void onFrame(String clientId, Frame frame);

        /**
         * 连接注册完成。
         *
         * @param clientId 连接标识
         */
        default void onConnected(String clientId) {
        }

        /**
         * 连接断开。
         *
         * @param clientId 连接标识
         */
        default void onDisconnected(String clientId) {
        }
    }
}
