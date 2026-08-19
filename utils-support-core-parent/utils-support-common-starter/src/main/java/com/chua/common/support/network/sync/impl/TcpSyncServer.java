package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 NIO + Reactor + 虚拟线程的 TCP 同步服务端实现。
 *
 * <p>对比旧版 BIO（每连接一个阻塞线程）的改进：</p>
 * <ul>
 *     <li><b>NIO Selector 事件循环</b>：单个 Reactor 线程管理所有连接的 accept/read/write 事件，连接数不再受线程数限制；</li>
 *     <li><b>虚拟线程处理业务</b>：Reactor 线程只做 IO 就绪检测与行切分，业务回调（onMessage 等）交由虚拟线程执行，避免业务阻塞事件循环；</li>
 *     <li><b>非阻塞写</b>：写入按连接加锁 + 发送缓冲，广播/定向发送均不阻塞 Reactor 线程。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("tcp")
public class TcpSyncServer extends com.chua.common.support.network.server.AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 读取缓冲大小
     */
    private static final int READ_BUFFER_SIZE = 8192;

    /**
     * 客户端连接表（clientId -> connection）
     */
    private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();

    /**
     * 同步监听器
     */
    private final List<SyncServerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 服务端通道
     */
    private ServerSocketChannel serverChannel;

    /**
     * Reactor 选择器
     */
    private Selector selector;

    /**
     * Reactor 事件循环线程
     */
    private Thread reactorThread;

    /**
     * 运行状态
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 空闲构造。
     */
    public TcpSyncServer() {
        super(null);
    }

    /**
     * 配置构造。
     *
     * @param setting 服务器配置
     */
    public TcpSyncServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /** 获取Protocol */
    public String getProtocol() {
        return "tcp";
    }

    @Override
    /** 创建Server */
    public SyncServer createServer(ServerSetting setting) {
        return new TcpSyncServer(setting);
    }

    @Override
    /** 创建Client */
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : "tcp://127.0.0.1:19391";
        return new TcpSyncClient(url);
    }

    @Override
    /** Do开始 */
    protected void doStart() {
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), setting.getBacklog());
            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            running.set(true);
            reactorThread = ThreadUtils.newThread(this::reactorLoop, "tcp-sync-reactor-" + setting.getPort());
            reactorThread.setDaemon(true);
            reactorThread.start();
        } catch (IOException e) {
            throw new RuntimeException("TCP SyncServer 启动失败", e);
        }
    }

    @Override
    /** Do停止 */
    protected void doStop() {
        running.set(false);
        if (selector != null) {
            try {
                selector.wakeup();
            } catch (Exception ignored) {
            }
        }
        if (reactorThread != null) {
            try {
                reactorThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reactorThread = null;
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
            serverChannel = null;
        }
        for (ClientConnection connection : clients.values()) {
            connection.close();
        }
        clients.clear();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object message) {
        String payload = topic + ":" + message;
        for (ClientConnection connection : clients.values()) {
            connection.write(payload);
        }
    }

    @Override
    /** 发送 */
    public void send(String clientId, String topic, Object message) {
        ClientConnection connection = clients.get(clientId);
        if (connection == null) {
            return;
        }
        connection.write(topic + ":" + message);
    }

    @Override
    /** 获取ConnectedClients */
    public List<String> getConnectedClients() {
        return new ArrayList<>(clients.keySet());
    }

    @Override
    /** 获取ClientMetadata */
    public Map<String, Object> getClientMetadata(String clientId) {
        ClientConnection connection = clients.get(clientId);
        return connection != null ? Collections.unmodifiableMap(connection.metadata) : Collections.emptyMap();
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
        return ProtocolType.TCP;
    }

    /**
     * Reactor 事件循环：单线程管理 accept/read/write 事件，业务处理交虚拟线程。
     */
    private void reactorLoop() {
        while (running.get() && selector.isOpen()) {
            try {
                selector.select(500);
                var selected = selector.selectedKeys();
                if (selected.isEmpty()) {
                    continue;
                }
                var iterator = selected.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (IOException e) {
                        handleChannelError(key, e);
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("TCP SyncServer Reactor 循环异常", e);
                }
            }
        }
    }

    /**
     * 处理 accept 事件。
     *
     * @param key 选择键
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel channel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = channel.accept();
        if (socketChannel == null) {
            return;
        }
        socketChannel.configureBlocking(false);
        socketChannel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
        ClientConnection connection = new ClientConnection(socketChannel);
        socketChannel.register(selector, SelectionKey.OP_READ, connection);
    }

    /**
     * 处理 read 事件：非阻塞读入缓冲，按行切分后交虚拟线程处理。
     *
     * @param key 选择键
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientConnection connection = (ClientConnection) key.attachment();
        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        int read;
        while ((read = channel.read(buffer)) > 0) {
            buffer.flip();
            connection.appendBuffer(buffer);
            buffer.clear();
        }
        if (read == -1) {
            // 对端关闭
            connection.unregister();
            return;
        }
        // 切分完整行并交虚拟线程处理
        List<String> lines = connection.drainLines();
        for (String line : lines) {
            final String msg = line;
            Thread.ofVirtual().name("tcp-sync-handler").start(() -> connection.handleLine(msg));
        }
    }

    /**
     * 处理 write 事件：冲刷连接发送队列。
     *
     * @param key 选择键
     */
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientConnection connection = (ClientConnection) key.attachment();
        connection.flushQueue(channel, key);
    }

    /**
     * 通道异常：通知监听器并关闭连接。
     *
     * @param key 选择键
     * @param e   异常
     */
    private void handleChannelError(SelectionKey key, IOException e) {
        ClientConnection connection = (ClientConnection) key.attachment();
        if (connection != null) {
            notifyListener(l -> l.onError(connection.getClientId(), e));
            connection.unregister();
        } else {
            key.cancel();
        }
    }

    /**
     * 通知监听器。
     *
     * @param action 动作
     */
    private void notifyListener(java.util.function.Consumer<SyncServerListener> action) {
        for (SyncServerListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 客户端连接封装（NIO 非阻塞）。
     *
     */
    private final class ClientConnection {

        /**
         * 底层通道
         */
        private final SocketChannel channel;

        /**
         * 客户端标识
         */
        private volatile String clientId;

        /**
         * 客户端元数据
         */
        private final Map<String, Object> metadata = new HashMap<>();

        /**
         * 读取缓冲（按行切分前的原始字节）
         */
        private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE * 2);

        /**
         * 发送队列（写事件就绪时冲刷）
         */
        private final LinkedBlockingQueue<String> writeQueue = new LinkedBlockingQueue<>();

        /**
         * 是否已注册写事件
         */
        private final AtomicBoolean writePending = new AtomicBoolean(false);

        /**
         * 创建客户端连接。
         *
         * @param channel 底层通道
         */
        private ClientConnection(SocketChannel channel) {
            this.channel = channel;
            // 初始置为读模式（无数据）：position=0, limit=0，供 appendBuffer 的 compact() 正确腾出空间
            readBuffer.flip();
        }

        /**
         * 追加读取字节。调用前 readBuffer 处于读模式（position=0, limit=数据末尾）。
         *
         * @param buffer 数据
         */
        void appendBuffer(ByteBuffer buffer) {
            synchronized (readBuffer) {
                readBuffer.compact();
                while (buffer.hasRemaining()) {
                    readBuffer.put(buffer.get());
                }
                readBuffer.flip();
            }
        }

        /**
         * 切分完整行（按 \n），未完成行保留在缓冲头部。
         *
         * @return 完整行列表
         */
        List<String> drainLines() {
            List<String> lines = new ArrayList<>();
            synchronized (readBuffer) {
                StringBuilder sb = new StringBuilder();
                while (readBuffer.hasRemaining()) {
                    char c = (char) readBuffer.get();
                    if (c == '\n') {
                        if (sb.length() > 0) {
                            lines.add(sb.toString());
                            sb.setLength(0);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                // 未完成行写回缓冲头部，保持读模式
                byte[] tail = sb.toString().getBytes(StandardCharsets.UTF_8);
                readBuffer.clear();
                readBuffer.put(tail);
                readBuffer.flip();
            }
            return lines;
        }

        /**
         * 处理一行消息（虚拟线程中执行）。
         *
         * @param line 消息行
         */
        void handleLine(String line) {
            String message = line.trim();
            if (message.isEmpty()) {
                return;
            }
            int colon = message.indexOf(':');
            String topic = colon > 0 ? message.substring(0, colon) : "";
            String payload = colon > 0 ? message.substring(colon + 1) : message;
            if ("register".equals(topic)) {
                clientId = payload;
                metadata.put("clientId", payload);
                clients.put(payload, this);
                write("registered:" + payload);
                notifyListener(l -> l.onClientConnected(payload, metadata));
            } else {
                notifyListener(l -> l.onMessage(clientId, topic, payload));
            }
        }

        /**
         * 写入一行消息（入队，写事件就绪时冲刷）。
         *
         * @param payload 消息内容
         */
        void write(String payload) {
            writeQueue.offer(payload);
            if (writePending.compareAndSet(false, true)) {
                SelectionKey key = channel.keyFor(selector);
                if (key != null) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    selector.wakeup();
                }
            }
        }

        /**
         * 冲刷发送队列。
         *
         * @param ch  通道
         * @param key 选择键
         */
        void flushQueue(SocketChannel ch, SelectionKey key) throws IOException {
            String payload;
            while ((payload = writeQueue.poll()) != null) {
                ByteBuffer buffer = ByteBuffer.wrap((payload + "\n").getBytes(StandardCharsets.UTF_8));
                ch.write(buffer);
            }
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            writePending.set(false);
        }

        /**
         * 获取客户端标识。
         *
         * @return 客户端标识
         */
        String getClientId() {
            return clientId;
        }

        /**
         * 注销连接。
         */
        void unregister() {
            if (clientId != null) {
                clients.remove(clientId);
                notifyListener(l -> l.onClientDisconnected(clientId));
            }
            close();
        }

        /**
         * 关闭连接。
         */
        void close() {
            try {
                SelectionKey key = channel.keyFor(selector);
                if (key != null) {
                    key.cancel();
                }
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
