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
* 客户端.admin().列表topics();
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
    /** 分组标识 */
    private final String groupId;
    /** Extraprops */
    private final Properties extraProps;

    /** producer */
    private Producer<String, String> producer;
    /** Admin客户端 */
    private AdminClient adminClient;
    /** consumer缓存 */
    private final Map<String, KafkaConsumer<String, String>> consumerCache = new ConcurrentHashMap<>();
    /** consumerthreads */
    private final Map<String, Thread> consumerThreads = new ConcurrentHashMap<>();
    /** Closed */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
    * 创建 kafka客户端 实例
    * @param bootstrapServers bootstrap服务端
    * @param bootstrapServers 字符串
    * @param extraProps 属性
    * @param groupId 群体标识
    * @param extraProps extraprops
     */
    private KafkaClient(String bootstrapServers, String groupId, Properties extraProps) {
        this.bootstrapServers = bootstrapServers;
        this.groupId = groupId;
        this.extraProps = extraProps;
    }

    // ==================== 工厂方法 ====================

    /**
    * 创建
    *
    * @param bootstrapServers bootstrap服务端
    * @return 创建的结果
     */
    public static KafkaClient create(String bootstrapServers) {
        return builder().bootstrapServers(bootstrapServers).build();
    }

    /**
    * 创建
    *
    * @param bootstrapServers bootstrap服务端
    * @param groupId 群体标识
    * @return 创建的结果
     */
    public static KafkaClient create(String bootstrapServers, String groupId) {
        return builder().bootstrapServers(bootstrapServers).groupId(groupId).build();
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
    * 开始
    *
    * @return 启动的结果
     */
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

 // 初始化 admin客户端
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        this.adminClient = AdminClient.create(adminProps);

        log.info("Kafka 客户端启动: bootstrapServers={}, groupId={}", bootstrapServers, groupId);
        return this;
    }

    /**
    * 关闭
    *
    * @return 关闭的结果
     */
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

 // 关闭 admin客户端
            if (adminClient != null) {
                try { adminClient.close(); } catch (Exception ignored) {}
            }

            log.info("Kafka 客户端关闭");
        }
        return this;
    }

    /**
    * 获取生产者操作构建器。
    * @return producer的结果
     */
    public ProducerOperation producer() {
        return new ProducerOperation(this);
    }

    /**
    * 获取消费者操作构建器。
    * @return consumer的结果
     */
    public ConsumerOperation consumer() {
        return new ConsumerOperation(this);
    }

    /**
    * 获取管理操作构建器。
    * @return admin的结果
     */
    public AdminOperation admin() {
        return new AdminOperation(this);
    }

    @Override
    /** 关闭 */
    public void close() {
        shutdown();
    }

    // ==================== Builder ====================
    /**
    * 构建器类。
    *
    * @author CH
    * @since 4.0.0
     */

    public static class Builder {
        /** Bootstrapservers */
        private String bootstrapServers = "127.0.0.1:9092";
        /** 分组标识 */
        private String groupId = "default-group";
        /** Extraprops */
        private Properties extraProps = new Properties();

        /**
        * bootstrap服务端
        *
        * @param s s
        * @return bootstrap服务端的结果
         */
        public Builder bootstrapServers(String s) { this.bootstrapServers = s; return this; }
        /**
        * 分组标识
        *
        * @param g g
        * @return 群体id的结果
         */
        public Builder groupId(String g) { this.groupId = g; return this; }
        /**
        * 财产
        *
        * @param key 键
        * @param value 值
        * @return 财产的结果
         */
        public Builder property(String key, String value) { this.extraProps.setProperty(key, value); return this; }
        /**
        * 属性
        *
        * @param p p
        * @return 属性的结果
         */
        public Builder properties(Properties p) { this.extraProps.putAll(p); return this; }
        /**
        * auto偏移量重置
        *
        * @param policy policy
        * @return auto偏移量reset的结果
         */
        public Builder autoOffsetReset(String policy) { this.extraProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, policy); return this; }
        /**
        * 启用Auto提交
        *
        * @param enable enable
        * @return enableAutoCommit的结果
         */
        public Builder enableAutoCommit(boolean enable) { this.extraProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enable); return this; }
        /**
        * 最大值取出Records
        *
        * @param max 最大
        * @return 最大pollrecords的结果
         */
        public Builder maxPollRecords(int max) { this.extraProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, max); return this; }

        /**
        * 构建
        *
        * @return 构建的结果
         */
        public KafkaClient build() {
            return new KafkaClient(bootstrapServers, groupId, extraProps);
        }
    }

    // ==================== 生产者操作 ====================
    /**
    * ProducerOperation类。
    *
    * @author CH
    * @since 4.0.0
     */

    public static class ProducerOperation {
        /** 客户端 */
        private final KafkaClient client;
        /** Topic */
        private String topic;
        /** 密钥 */
        private String key;
        /** 值 */
        private String value;
        /** 头部 */
        private Map<String, byte[]> headers = new LinkedHashMap<>();
        /** 分区 */
        private Integer partition;
        /** 时间戳 */
        private Long timestamp;

        ProducerOperation(KafkaClient client) { this.client = client; }

        /**
        * Topic
        *
        * @param t t
        * @return topic的结果
         */
        public ProducerOperation topic(String t) { this.topic = t; return this; }
        /**
        * 键
        *
        * @param k k
        * @return 键的结果
         */
        public ProducerOperation key(String k) { this.key = k; return this; }
        /**
        * 值
        *
        * @param v v
        * @return 值的结果
         */
        public ProducerOperation value(String v) { this.value = v; return this; }
        /**
        * 分区
        *
        * @param p p
        * @return 分区的结果
         */
        public ProducerOperation partition(Integer p) { this.partition = p; return this; }
        /**
        * 时间戳
        *
        * @param t t
        * @return 时间戳的结果
         */
        public ProducerOperation timestamp(Long t) { this.timestamp = t; return this; }
        /**
        * 头部
        *
        * @param name 名称
        * @param value 值
        * @return 头部的结果
         */
        public ProducerOperation header(String name, byte[] value) { this.headers.put(name, value); return this; }
        /**
        * 头部
        *
        * @param name 名称
        * @param value 值
        * @return 头部的结果
         */
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

        /**
        * 构建Record
        *
        * @return 构建record的结果
         */
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
    /**
    * ConsumerOperation类。
    *
    * @author CH
    * @since 4.0.0
     */

    public static class ConsumerOperation {
        /** 客户端 */
        private final KafkaClient client;
        /** 分组标识 */
        private String groupId;
        /** Topics */
        private String[] topics;
        /** 处理器 */
        private Consumer<ConsumerRecord<String, String>> handler;
        /** Autocommit */
        private boolean autoCommit = true;
        /** 偏移reset */
        private String offsetReset = "latest";
        /** Poll超时MS */
        private long pollTimeoutMs = 1000;

        ConsumerOperation(KafkaClient client) { this.client = client; }

        /**
        * 分组标识
        *
        * @param g g
        * @return 群体id的结果
         */
        public ConsumerOperation groupId(String g) { this.groupId = g; return this; }
        /**
        * Topic
        *
        * @param t t
        * @return topic的结果
         */
        public ConsumerOperation topic(String... t) { this.topics = t; return this; }
        /**
        * Auto提交
        *
        * @param a a
        * @return autoCommit的结果
         */
        public ConsumerOperation autoCommit(boolean a) { this.autoCommit = a; return this; }
        /**
        * 偏移量重置
        *
        * @param o o
        * @return 偏移量reset的结果
         */
        public ConsumerOperation offsetReset(String o) { this.offsetReset = o; return this; }
        /**
        * 取出超时
        *
        * @param ms ms
        * @return poll超时的结果
         */
        public ConsumerOperation pollTimeout(long ms) { this.pollTimeoutMs = ms; return this; }

        /**
        * 设置消息处理器。
        * @param h h
        * @return 处理器的结果
         */
        public ConsumerOperation handler(Consumer<ConsumerRecord<String, String>> h) {
            this.handler = h;
            return this;
        }

        /**
        * 设置简单消息处理器（仅处理 值）。
        * @param h h
        * @return on消息的结果
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
    /**
    * AdminOperation类。
    *
    * @author CH
    * @since 4.0.0
     */

    public static class AdminOperation {
        /** 客户端 */
        private final KafkaClient client;

        AdminOperation(KafkaClient client) { this.client = client; }

        /**
        * 创建 Topic。
        * @param name 名称
        * @param partitions 分区
        * @param replicationFactor replicationfactor
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
        * @param name 名称
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
        * @return 列表topics的结果
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
        * @param name 名称
        * @return describeTopic的结果
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
    /**
    * kafka客户端异常类。
    *
    * @author CH
    * @since 4.0.0
     */

    public static class KafkaClientException extends RuntimeException {
        /**
        * 创建 kafka客户端异常 实例
        * @param message 消息
        * @param cause Throwable
        * @param cause cause
         */
        public KafkaClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
