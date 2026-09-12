package com.chua.nats.support.client;

import io.nats.client.*;
import io.nats.client.api.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * NATS 全功能链式客户端。
 *
 * <p>封装 NATS Core Pub/Sub + JetStream + Key-Value Store + Object Store，提供链式 API。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 创建客户端
 * NatsClient client = NatsClient.builder()
 *     .url("nats://127.0.0.1:4222")
 *     .build();
 * client.start();
 *
 * // --- Core Pub/Sub ---
 * client.publish().subject("orders.create").body("{\"id\":1}").send();
 * client.subscribe().subject("orders.>").handler(msg -> {
 *     System.out.println("收到: " + new String(msg.getData()));
 * }).start();
 *
 * // --- Request/Reply ---
 * String reply = client.request("service.calc", "{\"x\":1}");
 *
 * // --- JetStream ---
 * client.jetStream().stream("ORDERS").addStream();
 * client.jetStream().stream("ORDERS").publish("{\"id\":2}");
 *
 * // --- Key-Value Store ---
 * client.kvStore("users").put("usr_001", "{\"name\":\"Alice\"}");
 *
 * // --- Object Store ---
 * client.objectStore("files").put("doc.pdf", pdfBytes);
 * }</pre>储("文件").放入("doc.pdf", pdfbytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class NatsClient implements AutoCloseable {

    /** 默认 NATS 服务器 URL */
    public static final String DEFAULT_URL = "nats://localhost:4222";

    /** URL */
    private final String url;
    /** 用户名 */
    private final String username;
    /** 密码 */
    private final String password;
    /** 令牌 */
    private final String token;
    /** Connection超时 */
    private final Duration connectionTimeout;
    /** Reconnectwait */
    private final Duration reconnectWait;
    /** 最大值reconnects */
    private final int maxReconnects;
    /** Pedantic */
    private final boolean pedantic;

    /** Connection */
    private Connection connection;
    /** JET流 */
    private JetStream jetStream;
    /** JET流management */
    private JetStreamManagement jetStreamManagement;
    /** Closed */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 活跃的订阅列表 */
    private final List<DispatchedSubscription> subscriptions = new ArrayList<>();

    /**
      * 创建 NATS客户端 实例
     * @param b b
     */
    private NatsClient(Builder b) {
        this.url = b.url;
        this.username = b.username;
        this.password = b.password;
        this.token = b.token;
        this.connectionTimeout = b.connectionTimeout;
        this.reconnectWait = b.reconnectWait;
        this.maxReconnects = b.maxReconnects;
        this.pedantic = b.pedantic;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建
     *
     * @return 创建的结果
     */
    public static NatsClient create() {
        return builder().build();
    }

    /**
     * 创建
     *
     * @param url url
     * @return 创建的结果
     */
    public static NatsClient create(String url) {
        return builder().url(url).build();
    }

    /**
     * 构建器
     *
     * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 启动/停止 ====================

    /**
     * 启动 NATS 客户端，连接到服务器。
     *
     * @return this
     */
    public NatsClient start() {
        try {
            var optionsBuilder = new Options.Builder()
                    .server(url)
                    .connectionTimeout(connectionTimeout)
                    .reconnectWait(reconnectWait)
                    .maxReconnects(maxReconnects)
                    .connectionListener((conn, type) -> {
                        switch (type) {
                            case CONNECTED -> log.info("NATS 已连接: {}", url);
                            case RECONNECTED -> log.info("NATS 已重连: {}", url);
                            case DISCONNECTED -> log.warn("NATS 连接断开: {}", url);
                            case CLOSED -> log.info("NATS 连接关闭");
                            case DISCOVERED_SERVERS -> log.debug("NATS 发现新服务器");
                        }
                    })
                    .errorListener(new ErrorListener() {
                        @Override
                        /** 记录错误Occurred */
                        public void errorOccurred(Connection conn, String error) {
                            log.error("NATS 错误: error={}", error);
                        }
                    });

            if (username != null && password != null) {
                optionsBuilder.userInfo(username, password);
            }
            if (token != null) {
                optionsBuilder.token(token);
            }

            this.connection = Nats.connect(optionsBuilder.build());
            this.jetStream = connection.jetStream();
            this.jetStreamManagement = connection.jetStreamManagement();

            log.info("NATS 客户端启动: url={}", url);
        } catch (IOException | InterruptedException e) {
            throw new NatsClientException("NATS 连接失败: " + url, e);
        }
        return this;
    }

    /**
     * 关闭 NATS 客户端。
     *
     * @return this
     */
    public NatsClient shutdown() {
        if (closed.compareAndSet(false, true)) {
            // 取消所有订阅
            for (var sub : subscriptions) {
                try {
                    if (sub.isJetStream) {
                        sub.jsSubscription.unsubscribe();
                    } else {
                        sub.coreSubscription.unsubscribe();
                    }
                } catch (Exception ignored) {
                }
            }
            subscriptions.clear();

            // 关闭连接
            if (connection != null) {
                try {
                    connection.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("NATS 关闭被中断");
                }
            }
            log.info("NATS 客户端关闭");
        }
        return this;
    }

    @Override
    /** 关闭 */
    public void close() {
        shutdown();
    }

    /**
     * 检查连接是否健康。
     *
     * @return 是否已连接
     */
    public boolean isConnected() {
        return connection != null && connection.getStatus() == Connection.Status.CONNECTED;
    }

    /**
     * 获取 NATS 服务器信息。
     *
     * @return 服务器信息
     */
    public ServerInfo getServerInfo() {
        return connection.getServerInfo();
    }

    // ==================== Core Pub/Sub ====================

    /**
     * 获取发布操作构建器。
     *
     * @return PublishOperation
     */
    public PublishOperation publish() {
        return new PublishOperation(this);
    }

    /**
     * 获取订阅操作构建器。
     *
     * @return SubscribeOperation
     */
    public SubscribeOperation subscribe() {
        return new SubscribeOperation(this);
    }

    /**
     * 发送请求并等待回复（同步）。
     *
     * @param subject 主题
     * @param body    请求体
     * @return 回复内容（字节数组）
     */
    public byte[] request(String subject, byte[] body) {
        return request(subject, body, Duration.ofSeconds(5));
    }

    /**
     * 发送请求并等待回复（同步），指定超时。
     *
     * @param subject 主题
     * @param body    请求体
     * @param timeout 超时时间
     * @return 回复内容（字节数组）
     */
    public byte[] request(String subject, byte[] body, Duration timeout) {
        try {
            var msg = connection.request(subject, body, timeout);
            return msg != null ? msg.getData() : null;
        } catch (InterruptedException e) {
            throw new NatsClientException("NATS 请求失败: " + subject, e);
        }
    }

    /**
     * 发送请求并等待回复（同步），参数为字符串。
     *
     * @param subject 主题
     * @param body    请求体（字符串）
     * @return 回复内容（字符串）
     */
    public String request(String subject, String body) {
        byte[] reply = request(subject, toBytes(body));
        return reply != null ? new String(reply, StandardCharsets.UTF_8) : null;
    }

    /**
     * 发送请求并等待回复（异步）。
     *
     * @param subject 主题
     * @param body    请求体
     * @return CompletableFuture 包含回复内容
     */
    public CompletableFuture<byte[]> requestAsync(String subject, byte[] body) {
        return requestAsync(subject, body, Duration.ofSeconds(5));
    }

    /**
     * 发送请求并等待回复（异步），指定超时。
     *
     * @param subject 主题
     * @param body    请求体
     * @param timeout 超时时间
     * @return CompletableFuture 包含回复内容
     */
    public CompletableFuture<byte[]> requestAsync(String subject, byte[] body, Duration timeout) {
        return connection.requestWithTimeout(subject, body, timeout)
                .thenApply(Message::getData);
    }

    // ==================== JetStream ====================

    /**
     * 获取 JetStream 操作构建器。
     *
     * @return JetStreamOperation
     */
    public JetStreamOperation jetStream() {
        return new JetStreamOperation(this);
    }

    // ==================== Key-Value Store ====================

    /**
      * 获取 键-值 存储 操作构建器。
     *
     * @param bucketName 存储桶名称
     * @return KvOperation
     */
    public KvOperation kvStore(String bucketName) {
        return new KvOperation(this, bucketName);
    }

    // ==================== Object Store ====================

    /**
      * 获取 对象 存储 操作构建器。
     *
     * @param bucketName 存储桶名称
     * @return ObjectStoreOperation
     */
    public ObjectStoreOperation objectStore(String bucketName) {
        return new ObjectStoreOperation(this, bucketName);
    }

    // ==================== Builder ====================
    /**
     * 构建器类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class Builder {
        /** URL */
        private String url = DEFAULT_URL;
        /** 用户名 */
        private String username;
        /** 密码 */
        private String password;
        /** 令牌 */
        private String token;
        /** Connection超时 */
        private Duration connectionTimeout = Duration.ofSeconds(5);
        /** Reconnectwait */
        private Duration reconnectWait = Duration.ofSeconds(2);
        /** 最大值reconnects */
        private int maxReconnects = 60;
        /** Pedantic */
        private boolean pedantic;

        /**
         * Url
         *
         * @param url url
         * @return url的结果
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * 凭证
         *
         * @param username 用户名
         * @param password 密码
         * @return 凭证的结果
         */
        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        /**
         * 令牌
         *
         * @param token 令牌
         * @return 令牌的结果
         */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /**
         * connection超时
         *
         * @param timeout 超时
         * @return connection超时的结果
         */
        public Builder connectionTimeout(Duration timeout) {
            this.connectionTimeout = timeout;
            return this;
        }

        /**
         * connection超时millis
         *
         * @param ms ms
         * @return connection超时millis的结果
         */
        public Builder connectionTimeoutMillis(long ms) {
            this.connectionTimeout = Duration.ofMillis(ms);
            return this;
        }

        /**
         * reconnectwait
         *
         * @param wait wait
         * @return reconnectWait的结果
         */
        public Builder reconnectWait(Duration wait) {
            this.reconnectWait = wait;
            return this;
        }

        /**
         * 最大值Reconnects
         *
         * @param max 最大
         * @return 最大reconnects的结果
         */
        public Builder maxReconnects(int max) {
            this.maxReconnects = max;
            return this;
        }

        /**
         * Pedantic
         *
         * @param pedantic pedantic
         * @return pedantic的结果
         */
        public Builder pedantic(boolean pedantic) {
            this.pedantic = pedantic;
            return this;
        }

        /**
         * 构建
         *
         * @return 构建的结果
         */
        public NatsClient build() {
            return new NatsClient(this);
        }
    }

    // ==================== 发布操作 ====================
    /**
     * 发布operation类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class PublishOperation {
        /** 客户端 */
        private final NatsClient client;
        /** 主题 */
        private String subject;
        /** 请求体 */
        private byte[] body;
        /** reply转为 */
        private String replyTo;
        /** 头部 */
        private Map<String, String> headers;

        PublishOperation(NatsClient client) {
            this.client = client;
        }

        /**
         * 主题
         *
         * @param s s
         * @return 主题的结果
         */
        public PublishOperation subject(String s) {
            this.subject = s;
            return this;
        }

        /**
         * 主体
         *
         * @param b b
         * @return 主体的结果
         */
        public PublishOperation body(byte[] b) {
            this.body = b;
            return this;
        }

        /**
         * 主体
         *
         * @param s s
         * @return 主体的结果
         */
        public PublishOperation body(String s) {
            this.body = toBytes(s);
            return this;
        }

        /**
         * reply转为
         *
         * @param r r
         * @return reply转为的结果
         */
        public PublishOperation replyTo(String r) {
            this.replyTo = r;
            return this;
        }

        /**
         * 头部
         *
         * @param key 键
         * @param value 值
         * @return 头部的结果
         */
        public PublishOperation header(String key, String value) {
            if (headers == null) {
                headers = new LinkedHashMap<>();
            }
            headers.put(key, value);
            return this;
        }

        /**
         * 头部
         *
         * @param h h
         * @return 头部的结果
         */
        public PublishOperation headers(Map<String, String> h) {
            this.headers = h;
            return this;
        }

        /**
         * 同步发送消息。
         */
        public void send() {
            client.connection.publish(buildMessage());
            log.info("NATS 发布成功: subject={}", subject);
        }

        /**
         * 异步发送消息。
         *
         * @return CompletableFuture
         */
        public CompletableFuture<Void> sendAsync() {
            return CompletableFuture.runAsync(this::send);
        }

        /**
         * 构建消息
         *
         * @return 构建消息的结果
         */
        private Message buildMessage() {
            io.nats.client.impl.NatsMessage.Builder builder = io.nats.client.impl.NatsMessage.builder()
                    .subject(subject)
                    .replyTo(replyTo)
                    .data(body);
            if (headers != null && !headers.isEmpty()) {
                io.nats.client.impl.Headers h = new io.nats.client.impl.Headers();
                headers.forEach(h::add);
                builder.headers(h);
            }
            return builder.build();
        }
    }

    // ==================== 订阅操作 ====================
    /**
     * 订阅operation类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class SubscribeOperation {
        /** 客户端 */
        private final NatsClient client;
        /** 主题 */
        private String subject;
        /** 队列 */
        private String queue;
        /** 处理器 */
        private Consumer<io.nats.client.Message> handler;
        /** autoACK */
        private boolean autoAck = true;

        SubscribeOperation(NatsClient client) {
            this.client = client;
        }

        /**
         * 主题
         *
         * @param s s
         * @return 主题的结果
         */
        public SubscribeOperation subject(String s) {
            this.subject = s;
            return this;
        }

        /**
         * 队列
         *
         * @param q q
         * @return 队列的结果
         */
        public SubscribeOperation queue(String q) {
            this.queue = q;
            return this;
        }

        /**
         * autoACK
         *
         * @param a a
         * @return autoACK的结果
         */
        public SubscribeOperation autoAck(boolean a) {
            this.autoAck = a;
            return this;
        }

        /**
         * 设置消息处理器。
         * @param h h
         * @return 处理器的结果
         */
        public SubscribeOperation handler(Consumer<io.nats.client.Message> h) {
            this.handler = h;
            return this;
        }

        /**
          * 设置简单消息处理器（仅处理 数据）。
         * @param h h
         * @return on消息的结果
         */
        public SubscribeOperation onMessage(Consumer<byte[]> h) {
            this.handler = msg -> h.accept(msg.getData());
            return this;
        }

        /**
         * 设置字符串消息处理器。
         * @param h h
         * @return on字符串的结果
         */
        public SubscribeOperation onString(Consumer<String> h) {
            this.handler = msg -> h.accept(new String(msg.getData(), StandardCharsets.UTF_8));
            return this;
        }

        /**
         * 开始订阅。
         *
         * @return 订阅对象
         */
        public DispatchedSubscription start() {
            DispatchedSubscription sub;
            if (queue != null && !queue.isEmpty()) {
                sub = new DispatchedSubscription(
                        client.createDispatcher(handler, autoAck).subscribe(subject, queue, msg -> handler.accept(msg)),
                        false);
            } else {
                sub = new DispatchedSubscription(
                        client.createDispatcher(handler, autoAck).subscribe(subject, msg -> handler.accept(msg)),
                        false);
            }
            client.subscriptions.add(sub);
            log.info("NATS 订阅: subject={}, queue={}", subject, queue);
            return sub;
        }
    }

    /**
     * 为订阅创建消息分发器。
     * @param handler 处理器
     * @param autoAck autoACK
     * @return 创建dispatcher的结果
     */
    private Dispatcher createDispatcher(Consumer<io.nats.client.Message> handler, boolean autoAck) {
        return connection.createDispatcher(msg -> {
            try {
                handler.accept(msg);
            } catch (Exception e) {
                log.error("NATS 消息处理异常: subject={}", msg.getSubject(), e);
            }
            if (autoAck && msg.isJetStream()) {
                msg.ack();
            }
        });
    }

    // ==================== JetStream 操作 ====================
    /**
     * jet流operation类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class JetStreamOperation {
        /** 客户端 */
        private final NatsClient client;

        JetStreamOperation(NatsClient client) {
            this.client = client;
        }

        /**
         * 获取指定流的操作构建器。
         *
         * @param streamName 流名称
         * @return StreamOperation
         */
        public StreamOperation stream(String streamName) {
            return new StreamOperation(client, streamName);
        }

        /**
         * 创建或更新流。
         *
         * @param config 流配置
         * @return StreamInfo
         */
        public StreamInfo addStream(StreamConfiguration config) {
            try {
                return client.jetStreamManagement.addStream(config);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("创建 JetStream 流失败: " + config.getName(), e);
            }
        }

        /**
         * 删除流。
         *
         * @param streamName 流名称
         */
        public void deleteStream(String streamName) {
            try {
                client.jetStreamManagement.deleteStream(streamName);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("删除 JetStream 流失败: " + streamName, e);
            }
        }

        /**
         * 获取流信息。
         *
         * @param streamName 流名称
         * @return StreamInfo
         */
        public StreamInfo getStream(String streamName) {
            try {
                return client.jetStreamManagement.getStreamInfo(streamName);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("获取 JetStream 流信息失败: " + streamName, e);
            }
        }

        /**
         * 列出所有流。
         *
         * @return 流名称列表
         */
        public List<String> listStreams() {
            try {
                return client.jetStreamManagement.getStreamNames();
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("列出 JetStream 流失败", e);
            }
        }
    }

    /**
     * JetStream 流操作。
     * @author CH
     * @since 4.0.0
     */
    public static class StreamOperation {
        /** 客户端 */
        private final NatsClient client;
        /** 流名称 */
        private final String streamName;
        /** 配置 */
        private StreamConfiguration config;

        StreamOperation(NatsClient client, String streamName) {
            this.client = client;
            this.streamName = streamName;
        }

        /**
         * 设置流配置。
         * @param config 配置
         * @return 配置的结果
         */
        public StreamOperation config(StreamConfiguration config) {
            this.config = config;
            return this;
        }

        /**
         * 添加流（不存在则创建）。
         *
         * @return StreamInfo
         */
        public StreamInfo addStream() {
            try {
                var cfg = config != null ? config : StreamConfiguration.builder()
                        .name(streamName)
                        .storageType(StorageType.File)
                        .build();
                return client.jetStreamManagement.addStream(cfg);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("添加 JetStream 流失败: " + streamName, e);
            }
        }

        /**
         * 发布消息到 JetStream 流。
         *
         * @param body 消息体
         * @return PublishAck
         */
        public PublishAck publish(byte[] body) {
            return publish(body, null);
        }

        /**
         * 发布消息到 JetStream 流。
         *
         * @param body 消息体
         * @return PublishAck
         */
        public PublishAck publish(String body) {
            return publish(toBytes(body));
        }

        /**
         * 发布消息到 JetStream 流，带消息头。
         *
         * @param body    消息体
         * @param headers 消息头
         * @return PublishAck
         */
        public PublishAck publish(byte[] body, Map<String, String> headers) {
            try {
                io.nats.client.impl.NatsMessage.Builder builder = io.nats.client.impl.NatsMessage.builder()
                        .subject(streamName)
                        .data(body);
                if (headers != null && !headers.isEmpty()) {
                    io.nats.client.impl.Headers h = new io.nats.client.impl.Headers();
                    headers.forEach(h::add);
                    builder.headers(h);
                }
                return client.jetStream.publish(builder.build());
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("JetStream 发布失败: " + streamName, e);
            }
        }

        /**
         * 订阅 JetStream 流（推送模式）。
         *
         * @param handler 消息处理器
         * @return DispatchedSubscription
         */
        public DispatchedSubscription subscribe(Consumer<io.nats.client.Message> handler) {
            return subscribe(handler, "default-consumer");
        }

        /**
         * 订阅 JetStream 流（推送模式），指定消费者名称。
         *
         * @param handler      消息处理器
         * @param consumerName 消费者名称
         * @return DispatchedSubscription
         */
        public DispatchedSubscription subscribe(Consumer<io.nats.client.Message> handler, String consumerName) {
            try {
                PushSubscribeOptions pushOptions = PushSubscribeOptions.builder()
                        .durable(consumerName)
                        .build();
                var sub = client.jetStream.subscribe(streamName, null, pushOptions);
                var dispatched = new DispatchedSubscription(sub, true);

                // 在新的虚拟线程中消费消息
                Thread.ofVirtual().name("nats-js-" + streamName).start(() -> {
                    try {
                        while (!dispatched.isCancelled()) {
                            var msg = sub.nextMessage(Duration.ofSeconds(5));
                            if (msg != null) {
                                try {
                                    handler.accept(msg);
                                    msg.ack();
                                } catch (Exception e) {
                                    log.error("JetStream 消息处理异常: stream={}", streamName, e);
                                    msg.nak();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        try {
                            sub.unsubscribe();
                        } catch (Exception ignored) {
                        }
                    }
                });

                client.subscriptions.add(dispatched);
                log.info("NATS JetStream 订阅: stream={}, consumer={}", streamName, consumerName);
                return dispatched;
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("JetStream 订阅失败: " + streamName, e);
            }
        }
    }

    // ==================== Key-Value Store 操作 ====================
    /**
     * KvOperation类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class KvOperation {
        /** 客户端 */
        private final NatsClient client;
        /** 存储桶名称 */
        private final String bucketName;
        /** 配置 */
        private KeyValueConfiguration config;

        KvOperation(NatsClient client, String bucketName) {
            this.client = client;
            this.bucketName = bucketName;
        }

        /**
         * 设置 KV 存储配置。
         * @param config 配置
         * @return 配置的结果
         */
        public KvOperation config(KeyValueConfiguration config) {
            this.config = config;
            return this;
        }

        /**
          * 创建或获取 键-值 存储桶。
         *
         * @return KeyValue
         */
        public KeyValue createOrGet() {
            try {
                return client.connection.keyValue(bucketName);
            } catch (IOException e) {
                // 存储桶不存在，创建新桶
                try {
                    var cfg = config != null ? config : KeyValueConfiguration.builder()
                            .name(bucketName)
                            .storageType(StorageType.File)
                            .build();
                    client.connection.keyValueManagement().create(cfg);
                    return client.connection.keyValue(bucketName);
                } catch (IOException | JetStreamApiException ex) {
                    throw new NatsClientException("创建 KV 存储桶失败: " + bucketName, ex);
                }
            }
        }

        /**
         * 写入键值对。
         *
         * @param key   键
         * @param value 值
         * @return 版本号（插入成功后的序列号）
         */
        public long put(String key, byte[] value) {
            try {
                return createOrGet().put(key, value);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("KV 写入失败: bucket=" + bucketName + ", key=" + key, e);
            }
        }

        /**
         * 写入字符串值。
         *
         * @param key   键
         * @param value 值
         * @return 版本号
         */
        public long put(String key, String value) {
            return put(key, toBytes(value));
        }

        /**
         * 读取键值对。
         *
         * @param key 键
         * @return 键值对条目
         */
        public KeyValueEntry get(String key) {
            try {
                return createOrGet().get(key);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("KV 读取失败: bucket=" + bucketName + ", key=" + key, e);
            }
        }

        /**
         * 读取键值对的值（字符串）。
         *
         * @param key 键
         * @return 值字符串
         */
        public String getString(String key) {
            var entry = get(key);
            return entry != null ? new String(entry.getValue(), StandardCharsets.UTF_8) : null;
        }

        /**
         * 删除键。
         *
         * @param key 键
         */
        public void delete(String key) {
            try {
                createOrGet().delete(key);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("KV 删除失败: bucket=" + bucketName + ", key=" + key, e);
            }
        }

        /**
         * 列出所有键。
         *
         * @return 键列表
         */
        public List<String> keys() {
            try {
                return createOrGet().keys();
            } catch (IOException | JetStreamApiException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new NatsClientException("KV 列出键失败: bucket=" + bucketName, e);
            }
        }

        /**
         * 删除存储桶。
         */
        public void deleteBucket() {
            try {
                client.connection.keyValueManagement().delete(bucketName);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("KV 删除存储桶失败: " + bucketName, e);
            }
        }
    }

    // ==================== Object Store 操作 ====================
    /**
     * 对象存储operation类。
     *
     * @author CH
     * @since 4.0.0
     */

    public static class ObjectStoreOperation {
        /** 客户端 */
        private final NatsClient client;
        /** 存储桶名称 */
        private final String bucketName;

        ObjectStoreOperation(NatsClient client, String bucketName) {
            this.client = client;
            this.bucketName = bucketName;
        }

        /**
          * 创建或获取 对象 存储 存储桶。
         *
         * @return ObjectStore
         */
        public ObjectStore createOrGet() {
            try {
                return client.connection.objectStore(bucketName);
            } catch (IOException e) {
                try {
                    client.connection.objectStoreManagement()
                            .create(ObjectStoreConfiguration.builder().name(bucketName)
                                    .storageType(StorageType.File).build());
                    return client.connection.objectStore(bucketName);
                } catch (IOException | JetStreamApiException ex) {
                    throw new NatsClientException("创建 Object Store 存储桶失败: " + bucketName, ex);
                }
            }
        }

        /**
         * 上传对象。
         *
         * @param name     对象名称
         * @param data     数据
         * @param metadata 元数据
         * @return ObjectInfo 条目信息
         */
        public ObjectInfo put(String name, byte[] data, Map<String, String> metadata) {
            try {
                ObjectMeta meta = ObjectMeta.objectName(name);
                if (metadata != null) {
                    meta = ObjectMeta.builder(name)
                            .description(String.join(",", metadata.entrySet().stream()
                                    .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new)))
                            .build();
                }
                return createOrGet().put(meta, new java.io.ByteArrayInputStream(data));
            } catch (Exception e) {
                throw new NatsClientException("Object Store 上传失败: bucket=" + bucketName + ", name=" + name, e);
            }
        }

        /**
         * 上传对象。
         *
         * @param name 对象名称
         * @param data 数据
         * @return ObjectInfo 条目信息
         */
        public ObjectInfo put(String name, byte[] data) {
            return put(name, data, null);
        }

        /**
         * 获取对象信息。
         *
         * @param name 对象名称
         * @return ObjectInfo 条目信息
         */
        public ObjectInfo get(String name) {
            try {
                return createOrGet().getInfo(name);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("Object Store 获取失败: bucket=" + bucketName + ", name=" + name, e);
            }
        }

        /**
         * 删除对象。
         *
         * @param name 对象名称
         */
        public void delete(String name) {
            try {
                createOrGet().delete(name);
            } catch (IOException | JetStreamApiException e) {
                throw new NatsClientException("Object Store 删除失败: bucket=" + bucketName + ", name=" + name, e);
            }
        }

        /**
         * 列出所有对象。
         *
         * @return 对象列表
         */
        public List<ObjectInfo> list() {
            try {
                return createOrGet().getList();
            } catch (Exception e) {
                throw new NatsClientException("Object Store 列出失败: bucket=" + bucketName, e);
            }
        }
    }

    // ==================== 订阅包装 ====================

    /**
     * 已分发的订阅。
     * @author CH
     * @since 4.0.0
     */
    @Getter
    public static class DispatchedSubscription {
        /** Coresubscription */
        private final io.nats.client.Subscription coreSubscription;
        /** jssubscription */
        private final io.nats.client.JetStreamSubscription jsSubscription;
        /** ISJET流 */
        private final boolean isJetStream;
        /** cancelled */
        private volatile boolean cancelled;

        DispatchedSubscription(io.nats.client.Subscription subscription, boolean isJetStream) {
            this.coreSubscription = subscription;
            this.jsSubscription = isJetStream ? (io.nats.client.JetStreamSubscription) subscription : null;
            this.isJetStream = isJetStream;
        }

        /**
         * 取消订阅。
         */
        public void unsubscribe() {
            cancelled = true;
            try {
                if (isJetStream && jsSubscription != null) {
                    jsSubscription.unsubscribe();
                } else if (coreSubscription != null) {
                    coreSubscription.unsubscribe();
                }
            } catch (Exception e) {
                throw new NatsClientException("取消订阅失败", e);
            }
        }

        /**
         * 暂停订阅。
         * <p>
         * 当前 jnats 版本未提供暂停接口，方法保留为 no-op。
         * </p>
         */
        public void pause() {
            // 当前 jnats 版本未提供暂停订阅能力
        }

        /**
         * 恢复订阅。
         * <p>
         * 当前 jnats 版本未提供恢复接口，方法保留为 no-op。
         * </p>
         */
        public void resume() {
            // 当前 jnats 版本未提供恢复订阅能力
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 转为bytes
     *
     * @param s s
     * @return 转为bytes的结果
     */
    private static byte[] toBytes(String s) {
        return s != null ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    // ==================== 异常类 ====================

    /**
     * NATS 客户端异常。
     * @author CH
     * @since 4.0.0
     */
    public static class NatsClientException extends RuntimeException {
        /**
          * 创建 NATS客户端异常 实例
         * @param message 消息
         * @param cause Throwable
         * @param cause cause
         */
        public NatsClientException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
          * 创建 NATS客户端异常 实例
         * @param message 消息
         */
        public NatsClientException(String message) {
            super(message);
        }
    }
}
