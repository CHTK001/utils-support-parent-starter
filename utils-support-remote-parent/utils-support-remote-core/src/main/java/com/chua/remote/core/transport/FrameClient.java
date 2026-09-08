package com.chua.remote.core.transport;

import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 远控零拷贝帧客户端。
 *
 * <p>基于 NIO 阻塞通道 + 虚拟线程读循环；帧为二进制定长前缀格式
 * （{@link FrameCodec#writeBinary}/{@link FrameCodec#readBinary}），
 * 全程无缓冲流、无字符集转换、载荷零复制。</p>
 *
 * <p>连接建立后发送 hello 控制帧（元数据携带 clientId）并同步等待
 * hello-ack，{@link #connect()} 返回即可收发业务帧。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FrameClient {

    /** hello-ack 等待超时（毫秒） */
    private static final long HELLO_ACK_TIMEOUT_MS = 5000L;

    /** 连接标识 */
    private final String clientId;

    /** 服务端地址 */
    private final String serverUrl;

    /** 事件监听器 */
    private volatile FrameListener listener;

    /** 连接通道 */
    private volatile SocketChannel channel;

    /** 已连接（通道可用） */
    private volatile boolean connected;

    /** hello 握手完成门闩 */
    private final CountDownLatch helloAck = new CountDownLatch(1);

    /**
     * 创建帧客户端。
     *
     * @param clientId  连接标识
     * @param serverUrl 服务端地址（tcp://host:port 或 host:port）
     */
    public FrameClient(String clientId, String serverUrl) {
        this.clientId = clientId;
        this.serverUrl = serverUrl;
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
     * 连接服务端并完成 hello 握手（阻塞直至可收发）。
     */
    public void connect() {
        if (connected) {
            return;
        }
        try {
            channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.connect(parseAddress(serverUrl));
            connected = true;
            Thread.ofVirtual().name("remote-frame-read-" + clientId).start(this::readLoop);
            write(helloFrame());
            if (!helloAck.await(HELLO_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("hello 握手超时: " + serverUrl);
            }
            log.info("远控帧客户端已连接: clientId={}, server={}", clientId, serverUrl);
        } catch (IOException e) {
            closeSilently();
            throw new RuntimeException("FrameClient 连接失败: " + serverUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeSilently();
            throw new RuntimeException("FrameClient 连接被中断", e);
        }
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        if (!connected) {
            return;
        }
        connected = false;
        closeSilently();
        log.info("远控帧客户端已断开: clientId={}", clientId);
    }

    /**
     * 是否已连接。
     *
     * @return 是否已连接
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 获取连接标识。
     *
     * @return 连接标识
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 发送帧。
     *
     * @param frame 帧
     */
    public void send(Frame frame) {
        if (!connected) {
            throw new IllegalStateException("客户端未连接");
        }
        write(frame);
    }

    /**
     * 发送 hello 握手帧。
     *
     * @return hello 帧
     */
    private Frame helloFrame() {
        return Frame.builder()
                .type(MessageType.CTRL)
                .metadata(Map.of(FrameCodec.METADATA_KIND, "hello", FrameServer.META_CLIENT_ID, clientId))
                .build();
    }

    /**
     * 读循环（虚拟线程执行）：逐帧读取并分发，直至连接关闭。
     */
    private void readLoop() {
        SocketChannel connChannel = channel;
        try {
            while (connected) {
                Frame frame = FrameCodec.readBinary(connChannel);
                if (isHelloAck(frame)) {
                    helloAck.countDown();
                    continue;
                }
                FrameListener current = listener;
                if (current != null) {
                    log.info("客户端收到帧: type={}", frame.getType());
                    current.onFrame(frame);
                }
            }
        } catch (IOException e) {
            if (connected) {
                log.info("远控帧客户端连接断开: clientId={}, cause={}", clientId, e.getMessage());
            }
        } finally {
            if (channel == connChannel) {
                connected = false;
            }
            helloAck.countDown();
            closeSilently();
        }
    }

    /**
     * 判断是否 hello 回执帧。
     *
     * @param frame 帧
     * @return 是否回执
     */
    private boolean isHelloAck(Frame frame) {
        return frame.getType() == MessageType.CTRL && frame.getMetadata() != null
                && "hello-ack".equals(frame.getMetadata().get(FrameCodec.METADATA_KIND));
    }

    /**
     * 写出帧（串行化，读线程与业务线程可能并发写）。
     *
     * @param frame 帧
     */
    private void write(Frame frame) {
        SocketChannel connChannel = channel;
        if (connChannel == null) {
            return;
        }
        synchronized (connChannel) {
            try {
                FrameCodec.writeBinary(connChannel, frame);
            } catch (IOException e) {
                connected = false;
                throw new RuntimeException("帧写出失败", e);
            }
        }
    }

    /**
     * 解析服务端地址。
     *
     * @param url 地址
     * @return 绑定地址
     */
    private InetSocketAddress parseAddress(String url) {
        String address = url;
        if (address.startsWith("tcp://")) {
            address = address.substring("tcp://".length());
        }
        if (address.startsWith("ws://")) {
            address = address.substring("ws://".length());
        }
        int colon = address.lastIndexOf(':');
        String host = colon > 0 ? address.substring(0, colon) : "127.0.0.1";
        int port = colon > 0 ? Integer.parseInt(address.substring(colon + 1)) : 19390;
        return new InetSocketAddress(host, port);
    }

    /**
     * 静默关闭通道。
     */
    private void closeSilently() {
        SocketChannel connChannel = channel;
        if (connChannel != null) {
            try {
                connChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 帧客户端事件监听器。
     */
    public interface FrameListener {

        /**
         * 收到服务端帧。
         *
         * @param frame 帧
         */
        void onFrame(Frame frame);
    }
}
