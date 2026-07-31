package com.chua.rabbitmq.support.client;

import com.rabbitmq.client.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * RabbitMQ 全功能链式客户端。
 *
 * <p>封装 AMQP Connection + Channel，提供 Exchange/Queue/Binding/Publish/Consume 全链式 API。</p>
 *
 * <h2>核心能力</h2>
 * <ul>
 *   <li>连接管理 — 自动重连、心跳保活、连接超时</li>
 *   <li>交换机 — 声明/删除 direct/topic/fanout/headers 类型</li>
 *   <li>队列 — 声明/删除/清空，支持 TTL、死信、优先级、消息大小限制</li>
 *   <li>绑定 — 队列与交换机绑定/解绑</li>
 *   <li>发布 — 同步/异步发送，支持持久化、消息头、Confirm 模式</li>
 *   <li>消费 — 自动/手动 ACK，prefetch 限流，批量 ACK</li>
 *   <li>事务 — TX 模式事务支持</li>
 *   <li>Channel 池 — 多 Channel 并发消费</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * RabbitmqClient client = RabbitmqClient.builder()
 *     .host("127.0.0.1").port(5672)
 *     .username("guest").password("guest")
 *     .virtualHost("/")
 *     .automaticRecovery(true)
 *     .connectionTimeout(5000)
 *     .heartbeat(30)
 *     .build();
 * client.start();
 *
 * // 交换机
 * client.exchange().name("order").topic().durable(true).declare();
 *
 * // 队列（TTL + 死信）
 * client.queue().name("order.created").durable(true)
 *     .ttl(60000)
 *     .deadLetterExchange("order.dlx")
 *     .deadLetterRoutingKey("order.dead")
 *     .priority(10)
 *     .maxLength(10000)
 *     .declare();
 *
 * // 绑定
 * client.bind().queue("order.created").exchange("order").routingKey("order.*").bind();
 *
 * // 发布（Confirm 模式）
 * client.publish().exchange("order").routingKey("order.created")
 *     .body("{\"id\":1}").persistent(true)
 *     .confirm(true)
 *     .send();
 *
 * // 消费（手动 ACK + prefetch）
 * client.consume().queue("order.created")
 *     .prefetch(10)
 *     .autoAck(false)
 *     .handler(msg -> {
 *         System.out.println("收到: " + msg.getBodyAsString());
 *         msg.ack();
 *     })
 *     .start();
 *
 * // 事务
 * client.tx().execute(ch -> {
 *     ch.basicPublish("order", "order.created", null, "msg1".getBytes());
 *     ch.basicPublish("order", "order.dead", null, "msg2".getBytes());
 * });
 * }</pre>
 *
 * @author CH
 * @since 2026/07/18
 */
@Slf4j
@Getter
public class RabbitmqClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final int connectionTimeout;
    private final int heartbeat;
    private final boolean automaticRecovery;
    private final int prefetchCount;

    private ConnectionFactory factory;
    private Connection connection;
    private final Map<String, Channel> channelCache = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean confirmMode = new AtomicBoolean(false);

    private RabbitmqClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.username = b.username;
        this.password = b.password;
        this.virtualHost = b.virtualHost;
        this.connectionTimeout = b.connectionTimeout;
        this.heartbeat = b.heartbeat;
        this.automaticRecovery = b.automaticRecovery;
        this.prefetchCount = b.prefetchCount;
    }

    // ==================== 工厂方法 ====================

    public static RabbitmqClient create() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    // ==================== 启动/停止 ====================

    public RabbitmqClient start() {
        try {
            factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            if (username != null) {
                factory.setUsername(username);
            }
            if (password != null) {
                factory.setPassword(password);
            }
            if (virtualHost != null) {
                factory.setVirtualHost(virtualHost);
            }
            factory.setConnectionTimeout(connectionTimeout);
            factory.setHandshakeTimeout(connectionTimeout);
            factory.setRequestedHeartbeat(heartbeat);
            factory.setAutomaticRecoveryEnabled(automaticRecovery);
            factory.setNetworkRecoveryInterval(5000);
            factory.setTopologyRecoveryEnabled(true);

            connection = factory.newConnection();
            log.info("RabbitMQ 连接成功: {}:{}, vhost={}", host, port, virtualHost);
        } catch (Exception e) {
            throw new RabbitmqClientException("RabbitMQ 连接失败: " + host + ":" + port, e);
        }
        return this;
    }

    public RabbitmqClient shutdown() {
        if (closed.compareAndSet(false, true)) {
            try {
                for (Channel ch : channelCache.values()) {
                    try { ch.close(); } catch (Exception ignored) {}
                }
                channelCache.clear();
                if (connection != null && connection.isOpen()) {
                    connection.close();
                }
                log.info("RabbitMQ 连接关闭");
            } catch (Exception e) {
                log.warn("RabbitMQ 关闭异常: {}", e.getMessage());
            }
        }
        return this;
    }

    @Override
    public void close() { shutdown(); }

    // ==================== Channel 管理 ====================

    public Channel getChannel() {
        return getChannel("default");
    }

    public Channel getChannel(String name) {
        return channelCache.computeIfAbsent(name, k -> {
            try {
                Channel ch = connection.createChannel();
                if (prefetchCount > 0) {
                    ch.basicQos(prefetchCount);
                }
                return ch;
            } catch (IOException e) {
                throw new RabbitmqClientException("创建 Channel 失败: " + k, e);
            }
        });
    }

    public void closeChannel(String name) {
        Channel ch = channelCache.remove(name);
        if (ch != null) {
            try { ch.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== 操作入口 ====================

    public ExchangeOperation exchange() { return new ExchangeOperation(this); }
    public QueueOperation queue() { return new QueueOperation(this); }
    public BindOperation bind() { return new BindOperation(this); }
    public PublishOperation publish() { return new PublishOperation(this); }
    public ConsumeOperation consume() { return new ConsumeOperation(this); }
    public TxOperation tx() { return new TxOperation(this); }

    // ==================== Builder ====================

    public static class Builder {
        private String host = "127.0.0.1";
        private int port = 5672;
        private String username = "guest";
        private String password = "guest";
        private String virtualHost = "/";
        private int connectionTimeout = 10000;
        private int heartbeat = 60;
        private boolean automaticRecovery = true;
        private int prefetchCount = 0;

        public Builder host(String h) { this.host = h; return this; }
        public Builder port(int p) { this.port = p; return this; }
        public Builder username(String u) { this.username = u; return this; }
        public Builder password(String p) { this.password = p; return this; }
        public Builder virtualHost(String vh) { this.virtualHost = vh; return this; }
        public Builder connectionTimeout(int ms) { this.connectionTimeout = ms; return this; }
        public Builder heartbeat(int sec) { this.heartbeat = sec; return this; }
        public Builder automaticRecovery(boolean r) { this.automaticRecovery = r; return this; }
        public Builder prefetch(int count) { this.prefetchCount = count; return this; }

        public RabbitmqClient build() { return new RabbitmqClient(this); }
    }

    // ==================== 交换机操作 ====================

    public static class ExchangeOperation {
        private final RabbitmqClient client;
        private String name = "";
        private String type = "direct";
        private boolean durable = true;
        private boolean autoDelete = false;
        private Map<String, Object> arguments;

        ExchangeOperation(RabbitmqClient client) { this.client = client; }

        public ExchangeOperation name(String n) { this.name = n; return this; }
        public ExchangeOperation type(String t) { this.type = t; return this; }
        public ExchangeOperation durable(boolean d) { this.durable = d; return this; }
        public ExchangeOperation autoDelete(boolean ad) { this.autoDelete = ad; return this; }
        public ExchangeOperation arguments(Map<String, Object> a) { this.arguments = a; return this; }
        public ExchangeOperation direct() { this.type = "direct"; return this; }
        public ExchangeOperation topic() { this.type = "topic"; return this; }
        public ExchangeOperation fanout() { this.type = "fanout"; return this; }
        public ExchangeOperation headers() { this.type = "headers"; return this; }

        public void declare() {
            try {
                client.getChannel().exchangeDeclare(name, type, durable, autoDelete, arguments);
            } catch (IOException e) { throw new RabbitmqClientException("声明交换机失败: " + name, e); }
        }

        public void delete() {
            try { client.getChannel().exchangeDelete(name); }
            catch (IOException e) { throw new RabbitmqClientException("删除交换机失败: " + name, e); }
        }

        public boolean exists() {
            try { client.getChannel().exchangeDeclarePassive(name); return true; }
            catch (IOException e) { return false; }
        }
    }

    // ==================== 队列操作 ====================

    public static class QueueOperation {
        private final RabbitmqClient client;
        private String name = "";
        private boolean durable = true;
        private boolean exclusive = false;
        private boolean autoDelete = false;
        private Map<String, Object> arguments = new HashMap<>();

        QueueOperation(RabbitmqClient client) { this.client = client; }

        public QueueOperation name(String n) { this.name = n; return this; }
        public QueueOperation durable(boolean d) { this.durable = d; return this; }
        public QueueOperation exclusive(boolean e) { this.exclusive = e; return this; }
        public QueueOperation autoDelete(boolean ad) { this.autoDelete = ad; return this; }
        public QueueOperation arguments(Map<String, Object> a) { this.arguments.putAll(a); return this; }

        /**
         * 设置消息 TTL（毫秒）
         */
        public QueueOperation ttl(int ms) {
            arguments.put("x-message-ttl", ms);
            return this;
        }

        /**
         * 设置队列 TTL（毫秒），队列无消息时自动删除
         */
        public QueueOperation queueTtl(int ms) {
            arguments.put("x-expires", ms);
            return this;
        }

        /**
         * 设置死信交换机
         */
        public QueueOperation deadLetterExchange(String exchange) {
            arguments.put("x-dead-letter-exchange", exchange);
            return this;
        }

        /**
         * 设置死信路由键
         */
        public QueueOperation deadLetterRoutingKey(String routingKey) {
            arguments.put("x-dead-letter-routing-key", routingKey);
            return this;
        }

        /**
         * 设置最大消息数
         */
        public QueueOperation maxLength(int max) {
            arguments.put("x-max-length", max);
            return this;
        }

        /**
         * 设置最大消息大小（字节）
         */
        public QueueOperation maxLengthBytes(long maxBytes) {
            arguments.put("x-max-length-bytes", maxBytes);
            return this;
        }

        /**
         * 设置最大优先级
         */
        public QueueOperation priority(int maxPriority) {
            arguments.put("x-max-priority", maxPriority);
            return this;
        }

        /**
         * 设置消息大小限制（兼容 maxLengthBytes）
         */
        public QueueOperation messageSizeLimit(long bytes) {
            arguments.put("x-message-size-limit", bytes);
            return this;
        }

        /**
         * 声明队列，返回实际队列名
         */
        public String declare() {
            try {
                AMQP.Queue.DeclareOk ok = client.getChannel()
                        .queueDeclare(name, durable, exclusive, autoDelete, arguments);
                return ok.getQueue();
            } catch (IOException e) { throw new RabbitmqClientException("声明队列失败: " + name, e); }
        }

        public void delete() {
            try { client.getChannel().queueDelete(name); }
            catch (IOException e) { throw new RabbitmqClientException("删除队列失败: " + name, e); }
        }

        public void purge() {
            try { client.getChannel().queuePurge(name); }
            catch (IOException e) { throw new RabbitmqClientException("清空队列失败: " + name, e); }
        }

        public long messageCount() {
            try { return client.getChannel().queueDeclarePassive(name).getMessageCount(); }
            catch (IOException e) { throw new RabbitmqClientException("获取队列消息数失败: " + name, e); }
        }

        public long consumerCount() {
            try { return client.getChannel().queueDeclarePassive(name).getConsumerCount(); }
            catch (IOException e) { throw new RabbitmqClientException("获取消费者数失败: " + name, e); }
        }
    }

    // ==================== 绑定操作 ====================

    public static class BindOperation {
        private final RabbitmqClient client;
        private String queue;
        private String exchange;
        private String routingKey = "";
        private Map<String, Object> arguments;

        BindOperation(RabbitmqClient client) { this.client = client; }

        public BindOperation queue(String q) { this.queue = q; return this; }
        public BindOperation exchange(String e) { this.exchange = e; return this; }
        public BindOperation routingKey(String k) { this.routingKey = k; return this; }
        public BindOperation arguments(Map<String, Object> a) { this.arguments = a; return this; }

        public void bind() {
            try { client.getChannel().queueBind(queue, exchange, routingKey, arguments); }
            catch (IOException e) { throw new RabbitmqClientException("绑定失败", e); }
        }

        public void unbind() {
            try { client.getChannel().queueUnbind(queue, exchange, routingKey, arguments); }
            catch (IOException e) { throw new RabbitmqClientException("解绑失败", e); }
        }
    }

    // ==================== 发布操作 ====================

    public static class PublishOperation {
        private final RabbitmqClient client;
        private String exchange = "";
        private String routingKey = "";
        private byte[] body;
        private AMQP.BasicProperties.Builder propsBuilder = new AMQP.BasicProperties.Builder();
        private boolean useConfirm = false;
        private long confirmTimeoutMs = 10000;

        PublishOperation(RabbitmqClient client) { this.client = client; }

        public PublishOperation exchange(String e) { this.exchange = e; return this; }
        public PublishOperation routingKey(String k) { this.routingKey = k; return this; }
        public PublishOperation body(String b) { this.body = b.getBytes(StandardCharsets.UTF_8); return this; }
        public PublishOperation body(byte[] b) { this.body = b; return this; }
        public PublishOperation contentType(String ct) { propsBuilder.contentType(ct); return this; }
        public PublishOperation messageId(String id) { propsBuilder.messageId(id); return this; }
        public PublishOperation correlationId(String id) { propsBuilder.correlationId(id); return this; }
        public PublishOperation replyTo(String queue) { propsBuilder.replyTo(queue); return this; }
        public PublishOperation expiration(String ms) { propsBuilder.expiration(ms); return this; }
        public PublishOperation priority(int p) { propsBuilder.priority(p); return this; }
        public PublishOperation type(String t) { propsBuilder.type(t); return this; }
        public PublishOperation headers(Map<String, Object> h) { propsBuilder.headers(h); return this; }
        public PublishOperation header(String key, Object value) {
            Map<String, Object> h = propsBuilder.build().getHeaders() != null
                    ? new HashMap<>(propsBuilder.build().getHeaders()) : new HashMap<>();
            h.put(key, value);
            propsBuilder.headers(h);
            return this;
        }

        public PublishOperation persistent(boolean p) { propsBuilder.deliveryMode(p ? 2 : 1); return this; }
        public PublishOperation nonPersistent(boolean t) { propsBuilder.deliveryMode(t ? 1 : 2); return this; }

        /**
         * 开启 Publisher Confirm 模式
         */
        public PublishOperation confirm(boolean c) { this.useConfirm = c; return this; }
        public PublishOperation confirmTimeout(long ms) { this.confirmTimeoutMs = ms; return this; }

        /**
         * 同步发送
         */
        public void send() {
            try {
                client.getChannel().basicPublish(exchange, routingKey, propsBuilder.build(), body);
                if (useConfirm) {
                    client.getChannel().waitForConfirmsOrDie(confirmTimeoutMs);
                }
            } catch (IOException | TimeoutException | InterruptedException e) {
                throw new RabbitmqClientException("发布消息失败", e);
            }
        }

        /**
         * 异步发送（带回调）
         */
        public void sendAsync(Consumer<Boolean> callback) {
            try {
                Channel ch = client.getChannel();
                if (useConfirm) {
                    ch.confirmSelect();
                    long seqNo = ch.getNextPublishSeqNo();
                    ch.addConfirmListener(new ConfirmListener() {
                        @Override
                        public void handleAck(long deliveryTag, boolean multiple) {
                            callback.accept(true);
                        }
                        @Override
                        public void handleNack(long deliveryTag, boolean multiple) {
                            callback.accept(false);
                        }
                    });
                }
                ch.basicPublish(exchange, routingKey, propsBuilder.build(), body);
                if (!useConfirm) {
                    callback.accept(true);
                }
            } catch (IOException e) {
                throw new RabbitmqClientException("异步发布失败", e);
            }
        }

        /**
         * 发布并等待返回（RPC 模式）
         */
        public byte[] sendAndReceive(long timeoutMs) {
            try {
                Channel ch = client.getChannel();
                String replyQueue = ch.queueDeclare().getQueue();
                String corrId = UUID.randomUUID().toString();

                AMQP.BasicProperties props = propsBuilder
                        .correlationId(corrId)
                        .replyTo(replyQueue)
                        .build();

                final byte[] response = new byte[1];
                final CountDownLatch latch = new CountDownLatch(1);

                ch.basicConsume(replyQueue, true, new DefaultConsumer(ch) {
                    @Override
                    public void handleDelivery(String tag, Envelope envelope,
                                               AMQP.BasicProperties properties, byte[] body) {
                        if (properties.getCorrelationId().equals(corrId)) {
                            response[0] = 1;
                            latch.countDown();
                        }
                    }
                });

                ch.basicPublish(exchange, routingKey, props, body);
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                ch.queueDelete(replyQueue);
                return response[0] == 1 ? response : null;
            } catch (Exception e) {
                throw new RabbitmqClientException("RPC 调用失败", e);
            }
        }
    }

    // ==================== 消费操作 ====================

    public static class ConsumeOperation {
        private final RabbitmqClient client;
        private String queue;
        private Consumer<MessageHandler> handler;
        private boolean autoAck = true;
        private String consumerTag = "";
        private boolean noLocal = false;
        private boolean exclusive = false;
        private Map<String, Object> arguments;
        private int prefetch = 0;
        private String channelName = "default";

        ConsumeOperation(RabbitmqClient client) { this.client = client; }

        public ConsumeOperation queue(String q) { this.queue = q; return this; }
        public ConsumeOperation autoAck(boolean a) { this.autoAck = a; return this; }
        public ConsumeOperation consumerTag(String t) { this.consumerTag = t; return this; }
        public ConsumeOperation noLocal(boolean n) { this.noLocal = n; return this; }
        public ConsumeOperation exclusive(boolean e) { this.exclusive = e; return this; }
        public ConsumeOperation arguments(Map<String, Object> a) { this.arguments = a; return this; }
        public ConsumeOperation prefetch(int count) { this.prefetch = count; return this; }
        public ConsumeOperation channel(String name) { this.channelName = name; return this; }

        public ConsumeOperation handler(Consumer<MessageHandler> h) { this.handler = h; return this; }

        public ConsumeOperation onMessage(Consumer<String> h) {
            this.handler = msg -> h.accept(new String(msg.getBody(), StandardCharsets.UTF_8));
            return this;
        }

        public ConsumeOperation onBytes(Consumer<byte[]> h) {
            this.handler = msg -> h.accept(msg.getBody());
            return this;
        }

        /**
         * 开始消费，返回 consumerTag
         */
        public String start() {
            try {
                Channel ch = client.getChannel(channelName);
                if (prefetch > 0) {
                    ch.basicQos(prefetch);
                }

                DeliverCallback deliver = (tag, delivery) -> {
                    if (handler != null) {
                        handler.accept(new MessageHandler(client, ch, delivery, tag));
                    }
                };
                CancelCallback cancel = tag -> log.warn("消费者被取消: queue={}, tag={}", queue, tag);

                String tag = client.getChannel(channelName).basicConsume(
                        queue, autoAck, consumerTag, noLocal, exclusive, arguments, deliver, cancel);
                log.info("开始消费: queue={}, tag={}, autoAck={}", queue, tag, autoAck);
                return tag;
            } catch (IOException e) {
                throw new RabbitmqClientException("开始消费失败: " + queue, e);
            }
        }

        /**
         * 停止消费
         */
        public void stop(String consumerTag) {
            try { client.getChannel(channelName).basicCancel(consumerTag); }
            catch (IOException e) { throw new RabbitmqClientException("停止消费失败", e); }
        }
    }

    // ==================== 事务操作 ====================

    public static class TxOperation {
        private final RabbitmqClient client;

        TxOperation(RabbitmqClient client) { this.client = client; }

        /**
         * 执行事务操作
         */
        public <T> T execute(TransactionCallback<T> callback) {
            Channel ch = client.getChannel();
            try {
                ch.txSelect();
                T result = callback.doInTransaction(ch);
                ch.txCommit();
                return result;
            } catch (Exception e) {
                try { ch.txRollback(); } catch (Exception ignored) {}
                throw new RabbitmqClientException("事务执行失败", e);
            }
        }

        /**
         * 批量发送（事务）
         */
        public void sendBatch(Runnable batch) {
            execute(ch -> {
                batch.run();
                return null;
            });
        }
    }

    @FunctionalInterface
    public interface TransactionCallback<T> {
        T doInTransaction(Channel channel) throws Exception;
    }

    // ==================== 消息包装 ====================

    @Getter
    public static class MessageHandler {
        private final RabbitmqClient client;
        private final Channel channel;
        private final Delivery delivery;
        private final String consumerTag;

        MessageHandler(RabbitmqClient client, Channel channel, Delivery delivery, String consumerTag) {
            this.client = client;
            this.channel = channel;
            this.delivery = delivery;
            this.consumerTag = consumerTag;
        }

        public byte[] getBody() { return delivery.getBody(); }
        public String getBodyAsString() { return new String(delivery.getBody(), StandardCharsets.UTF_8); }
        public long getDeliveryTag() { return delivery.getEnvelope().getDeliveryTag(); }
        public String getExchange() { return delivery.getEnvelope().getExchange(); }
        public String getRoutingKey() { return delivery.getEnvelope().getRoutingKey(); }
        public AMQP.BasicProperties getProperties() { return delivery.getProperties(); }
        public Map<String, Object> getHeaders() {
            return delivery.getProperties().getHeaders();
        }
        public String getHeader(String name) {
            Object v = delivery.getProperties().getHeaders() != null
                    ? delivery.getProperties().getHeaders().get(name) : null;
            return v != null ? v.toString() : null;
        }

        /**
         * 手动 ACK 单条
         */
        public void ack() {
            try { channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false); }
            catch (IOException e) { throw new RabbitmqClientException("ACK 失败", e); }
        }

        /**
         * 手动 ACK 批量（含当前及之前所有未确认消息）
         */
        public void ackMultiple() {
            try { channel.basicAck(delivery.getEnvelope().getDeliveryTag(), true); }
            catch (IOException e) { throw new RabbitmqClientException("ACK 失败", e); }
        }

        /**
         * 拒绝消息
         */
        public void nack(boolean requeue) {
            try { channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, requeue); }
            catch (IOException e) { throw new RabbitmqClientException("NACK 失败", e); }
        }

        /**
         * 拒绝并重新入队
         */
        public void reject() {
            try { channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true); }
            catch (IOException e) { throw new RabbitmqClientException("Reject 失败", e); }
        }

        /**
         * 拒绝并丢弃
         */
        public void rejectAndDrop() {
            try { channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false); }
            catch (IOException e) { throw new RabbitmqClientException("Reject 失败", e); }
        }
    }

    // ==================== 异常类 ====================

    public static class RabbitmqClientException extends RuntimeException {
        public RabbitmqClientException(String message, Throwable cause) { super(message, cause); }
        public RabbitmqClientException(String message) { super(message); }
    }
}
