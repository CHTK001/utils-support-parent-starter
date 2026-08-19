package com.chua.kafka.support.client;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Kafka 全功能链式客户端。
 *
 * <p>封装 Kafka Producer + Consumer + AdminClient，提供订阅/发布的链式 API。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 创建客户端
 * KafkaClient client = KafkaClient.builder()
 *     .bootstrapServers("127.0.0.1:9092")
 *     .groupId("my-group")
 *     .build();
 *
 * // 发布消息
 * client.producer().topic("order").key("k1").value("{\"id\":1}").send();
 *
 * // 订阅消息
 * client.consumer().topic("order").handler(msg -> {
 *     System.out.println("收到: " + msg.value());
 * }).subscribe();
 *
 * // 管理操作
 * client.admin().createTopic("order", 3, (short) 1);
 * client.admin().deleteTopic("order");
 * client.admin().listTopics();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Getter
public class KafkaClient implements AutoCloseable {

    /** Bootstrapservers */
    private final String bootstrapServers;
    /** 分组ID */
    private final String groupId;
    /** Extraprops */
    private final Properties extraProps;

    /** producer */
    private Producer<String, String> producer;
    /** Admin客户端 */
    private AdminClient adminClient;
    /** consumerCache */
    private final Map<String, KafkaConsumer<String, String>> consumerCache = new ConcurrentHashMap<>();
    /** consumerThreads */
    private final Map<String, Thread> consumerThreads = new ConcurrentHashMap<>();
    /** Closed */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private KafkaClient(String bootstrapServers, String groupId, Properties extraProps) {
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.extraProps = extraProps;
    }

    // ==================== 工厂方法 ====================

    public static KafkaClient create(String bootstrapServers) {
        return builder().bootstrapServers(bootstrapServers).build();
    }

    public static KafkaClient create(String bootstrapServers, String groupId) {
        return builder().bootstrapServers(bootstrapServers).groupId(groupId).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 启动/停止 ====================

    public KafkaClient start() {
        // 初始化 Producer
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        if (extraProps != null) {
            producerProps.putAll(extraProps);
        }
        this.producer = new KafkaProducer<>(producerProps);

        // 初始化 AdminClient
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        this.adminClient = AdminClient.create(adminProps);

        log.info("Kafka 客户端启动: bootstrapServers={}, groupId={}", bootstrapServers, groupId);
        return this;
    }

    public KafkaClient shutdown() {
        if (closed.compareAndSet(false, true)) {
            // 关闭所有消费者线程
            for (Map.Entry<String, Thread> entry : consumerThreads.entrySet()) {
                entry.getValue().interrupt();
            }
            consumerThreads.clear();

            // 关闭所有消费者
            for (KafkaConsumer<String, String> consumer : consumerCache.values()) {
                try { consumer.close(); } catch (Exception ignored) {}
            }
            consumerCache.clear();

            // 关闭 Producer
            if (producer != null) {
                try { producer.close(); } catch (Exception ignored) {}
            }

            // 关闭 AdminClient
            if (adminClient != null) {
                try { adminClient.close(); } catch (Exception ignored) {}
            }

            log.info("Kafka 客户端关闭");
        }
        return this;
    }

    /**
     * 获取生产者操作构建器。
     */
    public ProducerOperation producer() {
        return new ProducerOperation(this);
    }

    /**
     * 获取消费者操作构建器。
     */
    public ConsumerOperation consumer() {
        return new ConsumerOperation(this);
    }

    /**
     * 获取管理操作构建器。
     */
    public AdminOperation admin() {
        return new AdminOperation(this);
    }

    @Override
    public void close() {
        shutdown();
    }

    // ==================== Builder ====================

    public static class Builder {
        /** Bootstrapservers */
        private String bootstrapServers = "127.0.0.1:9092";
        /** 分组ID */
        private String groupId = "default-group";
        /** Extraprops */
        private Properties extraProps = new Properties();

        public Builder bootstrapServers(String s) { this.bootstrapServers = s; return this; }
        public Builder groupId(String g) { this.groupId = g; return this; }
        public Builder property(String key, String value) { this.extraProps.setProperty(key, value); return this; }
        public Builder properties(Properties p) { this.extraProps.putAll(p); return this; }
        public Builder autoOffsetReset(String policy) { this.extraProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, policy); return this; }
        public Builder enableAutoCommit(boolean enable) { this.extraProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enable); return this; }
        public Builder maxPollRecords(int max) { this.extraProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, max); return this; }

        public KafkaClient build() {
            return new KafkaClient(bootstrapServers, groupId, extraProps);
        }
    }

    // ==================== 生产者操作 ====================

    public static class ProducerOperation {
        /** 客户端 */
        private final KafkaClient client;
        /** Topic */
        private String topic;
        /** 密钥 */
        private String key;
        /** 值 */
        private String value;
        /** headers */
        private Map<String, byte[]> headers = new LinkedHashMap<>();
        /** Partition */
        private Integer partition;
        /** 时间戳 */
        private Long timestamp;

        ProducerOperation(KafkaClient client) { this.client = client; }

        public ProducerOperation topic(String t) { this.topic = t; return this; }
        public ProducerOperation key(String k) { this.key = k; return this; }
        public ProducerOperation value(String v) { this.value = v; return this; }
        public ProducerOperation partition(Integer p) { this.partition = p; return this; }
        public ProducerOperation timestamp(Long t) { this.timestamp = t; return this; }
        public ProducerOperation header(String name, byte[] value) { this.headers.put(name, value); return this; }
        public ProducerOperation header(String name, String value) { this.headers.put(name, value.getBytes()); return this; }

        /**
         * 发送消息（异步）。
         *
         * @param callback 发送结果回调
         */
        public void sendAsync(java.util.function.Consumer<RecordMetadata> callback) {
            ProducerRecord<String, String> record = buildRecord();
            client.producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Kafka 发送失败: topic={}", topic, exception);
                } else {
                    callback.accept(metadata);
                }
            });
        }

        /**
         * 同步发送消息。
         *
         * @return RecordMetadata
         */
        public RecordMetadata send() {
            try {
                return client.producer.send(buildRecord()).get();
            } catch (Exception e) {
                throw new KafkaClientException("Kafka 发送失败: " + topic, e);
            }
        }

        private ProducerRecord<String, String> buildRecord() {
            ProducerRecord<String, String> record;
            if (partition != null && timestamp != null) {
                record = new ProducerRecord<>(topic, partition, timestamp, key, value);
            } else if (partition != null) {
                record = new ProducerRecord<>(topic, partition, key, value);
            } else {
                record = new ProducerRecord<>(topic, key, value);
            }
            for (Map.Entry<String, byte[]> entry : headers.entrySet()) {
                record.headers().add(entry.getKey(), entry.getValue());
            }
            return record;
        }
    }

    // ==================== 消费者操作 ====================

    public static class ConsumerOperation {
        /** 客户端 */
        private final KafkaClient client;
        /** 分组ID */
        private String groupId;
        /** Topics */
        private String[] topics;
        /** handler */
        private Consumer<ConsumerRecord<String, String>> handler;
        /** Autocommit */
        private boolean autoCommit = true;
        /** 偏移reset */
        private String offsetReset = "latest";
        /** Poll超时MS */
        private long pollTimeoutMs = 1000;

        ConsumerOperation(KafkaClient client) { this.client = client; }

        public ConsumerOperation groupId(String g) { this.groupId = g; return this; }
        public ConsumerOperation topic(String... t) { this.topics = t; return this; }
        public ConsumerOperation autoCommit(boolean a) { this.autoCommit = a; return this; }
        public ConsumerOperation offsetReset(String o) { this.offsetReset = o; return this; }
        public ConsumerOperation pollTimeout(long ms) { this.pollTimeoutMs = ms; return this; }

        /**
         * 设置消息处理器。
         */
        public ConsumerOperation handler(Consumer<ConsumerRecord<String, String>> h) {
            this.handler = h;
            return this;
        }

        /**
         * 设置简单消息处理器（仅处理 value）。
         */
        public ConsumerOperation onMessage(Consumer<String> h) {
            this.handler = record -> h.accept(record.value());
            return this;
        }

        /**
         * 订阅并开始消费（在独立线程中）。
         *
         * @return 消费者线程名
         */
        public String subscribe() {
            String group = groupId != null ? groupId : client.groupId;
            String threadName = "kafka-consumer-" + (group != null ? group : "default") + "-" + System.currentTimeMillis();

            Properties consumerProps = new Properties();
            consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, client.bootstrapServers);
            consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit);
            consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
            if (client.extraProps != null) {
                consumerProps.putAll(client.extraProps);
            }
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
            consumer.subscribe(Arrays.asList(topics));

            client.consumerCache.put(threadName, consumer);

            Thread thread = new Thread(() -> {
                log.info("Kafka 消费者线程启动: topics={}, group={}", Arrays.toString(topics), group);
                while (!client.closed.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(pollTimeoutMs));
                        for (ConsumerRecord<String, String> record : records) {
                            if (handler != null) {
                                handler.accept(record);
                            }
                        }
                    } catch (Exception e) {
                        if (!client.closed.get()) {
                            log.error("Kafka 消费异常", e);
                        }
                    }
                }
                consumer.close();
                log.info("Kafka 消费者线程停止: {}", threadName);
            }, threadName);
            thread.setDaemon(true);
            thread.start();
            client.consumerThreads.put(threadName, thread);

            return threadName;
        }

        /**
         * 手动提交偏移量。
         */
        public void commitSync() {
            String threadName = client.consumerCache.keySet().stream().findFirst().orElse(null);
            if (threadName != null) {
                KafkaConsumer<String, String> consumer = client.consumerCache.get(threadName);
                if (consumer != null) {
                    consumer.commitSync();
                }
            }
        }
    }

    // ==================== 管理操作 ====================

    public static class AdminOperation {
        /** 客户端 */
        private final KafkaClient client;

        AdminOperation(KafkaClient client) { this.client = client; }

        /**
         * 创建 Topic。
         */
        public void createTopic(String name, int partitions, short replicationFactor) {
            try {
                NewTopic topic = new NewTopic(name, partitions, replicationFactor);
                client.adminClient.createTopics(Collections.singletonList(topic)).all().get();
                log.info("Topic 创建: {} (partitions={}, replication={})", name, partitions, replicationFactor);
            } catch (Exception e) {
                throw new KafkaClientException("创建 Topic 失败: " + name, e);
            }
        }

        /**
         * 删除 Topic。
         */
        public void deleteTopic(String name) {
            try {
                client.adminClient.deleteTopics(Collections.singletonList(name)).all().get();
                log.info("Topic 删除: {}", name);
            } catch (Exception e) {
                throw new KafkaClientException("删除 Topic 失败: " + name, e);
            }
        }

        /**
         * 列出所有 Topic。
         */
        public Set<String> listTopics() {
            try {
                return client.adminClient.listTopics().names().get();
            } catch (Exception e) {
                throw new KafkaClientException("列出 Topic 失败", e);
            }
        }

        /**
         * 获取 Topic 信息。
         */
        public TopicDescription describeTopic(String name) {
            try {
                DescribeTopicsResult result = client.adminClient.describeTopics(
                        Collections.singletonList(name));
                return result.topicNameValues().get(name).get();
            } catch (Exception e) {
                throw new KafkaClientException("描述 Topic 失败: " + name, e);
            }
        }
    }

    // ==================== 异常类 ====================

    public static class KafkaClientException extends RuntimeException {
        public KafkaClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
