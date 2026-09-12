package com.chua.kafka.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 分发器提供者，基于 Kafka 实现跨进程的发布订阅。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("kafka")
public class KafkaDispatcherProvider extends AbstractDispatcherProvider {

    /**
     * Kafka 生产者
     */
    private KafkaProducer<String, String> producer;

    /**
     * 主题与订阅定义列表的映射
     */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
     * 消费者线程池
     */
    private final ExecutorService executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder().setNameFormat("kafka-dispatcher-%d").setDaemon(true).build());

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
      * 创建 kafkadispatcher提供者 实例
     * @param config 配置
     */
    public KafkaDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    @Override
    /** 开始 */
    public void start() {
        this.producer = createProducer();
    }

    /**
     * 创建 Kafka 生产者。
     *
     * @return KafkaProducer
     */
    private KafkaProducer<String, String> createProducer() {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getUrl());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object body) {
        var value = body == null ? "" : body.toString();
        producer.send(new ProducerRecord<>(topic, value));
    }

    @Override
    /** 订阅 */
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(definition);
            startConsumer(topic);
        }
    }

    /**
     * 启动 Kafka 消费者监听指定主题。
     *
     * @param topic 主题
     */
    private void startConsumer(String topic) {
        executor.submit(() -> {
            var consumer = createConsumer(topic);
            try {
                while (!closed) {
                    var records = consumer.poll(Duration.ofMillis(1000));
                    for (var record : records) {
                        var definitions = definitionMap.get(topic);
                        if (definitions != null) {
                            for (var def : definitions) {
                                def.dispatch(record.value());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Kafka 消费异常，主题：{}", topic, e);
            } finally {
                consumer.close();
            }
        });
    }

    /**
     * 创建 Kafka 消费者。
     *
     * @param topic 主题
     * @return KafkaConsumer
     */
    private KafkaConsumer<Object, Object> createConsumer(String topic) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getUrl());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getGroupId() != null ? config.getGroupId() : "default-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, config.isAutoCommitOffset());
        var consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        return consumer;
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var definitions = definitionMap.get(topic);
            if (definitions != null) {
                definitions.remove(definition);
                if (definitions.isEmpty()) {
                    definitionMap.remove(topic);
                }
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        closed = true;
        producer.close();
        executor.shutdown();
        definitionMap.clear();
    }
}
