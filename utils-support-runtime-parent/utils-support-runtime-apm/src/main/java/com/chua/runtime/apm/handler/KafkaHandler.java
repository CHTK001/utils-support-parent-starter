package com.chua.runtime.apm.handler;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kafka 应用层 Handler — 拦截 KafkaProducer / KafkaConsumer 并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.kafka.clients.producer.KafkaProducer} — 生产消息</li>
 *   <li>{@code org.apache.kafka.clients.consumer.KafkaConsumer} — 消费消息</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：</p>
 * <ul>
 *   <li>不引入 kafka-clients 编译期依赖</li>
 *   <li>通过 {@link RuntimeSpy#registerInterceptor} 注册精确规则，
 *       若 Kafka 不在 classpath 则 SpyTransformer 找不到类而不生效（无副作用）</li>
 *   <li>从 ctx.userData 反射读取 broker 地址</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KafkaHandler implements Plugin, RuntimeSpy.Interceptor {
    private static final Logger LOG = Logger.getLogger(KafkaHandler.class.getName());

    /**
     * KafkaProducer 内部名
     */
    private static final String PRODUCER_CLASS = "org/apache/kafka/clients/producer/KafkaProducer";

    /**
     * KafkaConsumer 内部名
     */
    private static final String CONSUMER_CLASS = "org/apache/kafka/clients/consumer/KafkaConsumer";

    /**
     * 最大记录数
     */
    private static final int MAX_RECORDS = 5000;

    private final List<TransmissionRecord> records;
    private boolean enabled;
    private final AtomicBoolean started;

    public KafkaHandler() {
        this.records = Collections.synchronizedList(new ArrayList<>());
        this.started = new AtomicBoolean(false);
    }

    @Override
    public String name() {
        return "kafka-handler";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void init(PluginContext context) throws Exception {
        this.enabled = "true".equals(context.getProperty("kafka.enabled", "true"));
        LOG.log(Level.INFO, String.format("KafkaHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        registerInterceptors();
        LOG.log(Level.INFO, "KafkaHandler 启动完成，应用层 Kafka 拦截已注册");
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "KafkaHandler 停止");
    }

    @Override
    public String status() {
        return String.format("KafkaHandler[enabled=%s, records=%d]", enabled, records.size());
    }

    @Override
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 注册 KafkaProducer/KafkaConsumer 关键方法插桩规则。
     */
    private void registerInterceptors() {
        // Producer: send(ProducerRecord) / send(ProducerRecord, Callback)
        RuntimeSpy.registerInterceptor(PRODUCER_CLASS, "send",
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;)Ljava/util/concurrent/Future;",
                InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(PRODUCER_CLASS, "send",
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;Lorg/apache/kafka/clients/producer/Callback;)" +
                        "Ljava/util/concurrent/Future;",
                InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(PRODUCER_CLASS, "send",
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;)Ljava/util/concurrent/Future;",
                InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(PRODUCER_CLASS, "send",
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;Lorg/apache/kafka/clients/producer/Callback;)" +
                        "Ljava/util/concurrent/Future;",
                InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(PRODUCER_CLASS, "send",
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;)Ljava/util/concurrent/Future;",
                InterceptPoint.EXCEPTION, this);
        RuntimeSpy.registerInterceptor(PRODUCER_CLASS, "send",
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;Lorg/apache/kafka/clients/producer/Callback;)" +
                        "Ljava/util/concurrent/Future;",
                InterceptPoint.EXCEPTION, this);

        // Consumer: poll(long)
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "poll", "(J)Lorg/apache/kafka/clients/consumer/ConsumerRecords;",
                InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "poll", "(J)Lorg/apache/kafka/clients/consumer/ConsumerRecords;",
                InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "poll", "(J)Lorg/apache/kafka/clients/consumer/ConsumerRecords;",
                InterceptPoint.EXCEPTION, this);

        // Consumer: commitSync() / commitAsync()
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "commitSync", "()V",
                InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "commitSync", "()V",
                InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "commitAsync", "()V",
                InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "commitAsync", "()V",
                InterceptPoint.EXIT, this);
    }

    @Override
    public void onIntercept(InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        InterceptPoint point = ctx.getPoint();
        switch (point) {
            case ENTRY -> handleEntry(ctx);
            case EXIT -> handleExit(ctx);
            case EXCEPTION -> handleException(ctx);
            default -> {
            }
        }
    }

    private static final ThreadLocal<TransmissionRecord> CURRENT = new ThreadLocal<>();

    private void handleEntry(InterceptContext ctx) {
        try {
            TransmissionRecord record = new TransmissionRecord();
            record.setTraceId(ctx.getTraceId());
            record.setSpanId(ctx.getSpanId());
            record.setStartTime(System.currentTimeMillis());
            record.setOperation(deriveOperation(ctx));

            boolean isProducer = PRODUCER_CLASS.equals(ctx.getClassName());
            Software software = isProducer ? Software.KAFKA_PRODUCER : Software.KAFKA_CONSUMER;
            EndpointKind kind = isProducer ? EndpointKind.PRODUCER : EndpointKind.CONSUMER;
            record.setSoftware(software);
            record.setProtocol(Protocol.KAFKA);

            // source = 调用方（producer / consumer instance）
            Object client = ctx.getUserData();
            Endpoint source = Endpoint.builder()
                    .kind(kind)
                    .protocol(Protocol.KAFKA)
                    .software(software)
                    .host(localHost())
                    .port(0)
                    .path("/")
                    .build();
            record.setSource(source);

            // target = Kafka broker
            Endpoint target = Endpoint.builder()
                    .kind(EndpointKind.SERVER)
                    .protocol(Protocol.KAFKA)
                    .software(software)
                    .host(extractBootstrapBroker(client))
                    .port(9092)
                    .path(isProducer ? "/produce" : "/consume")
                    .build();
            record.setTarget(target);

            CURRENT.set(record);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("KafkaHandler.entry 异常: %s", e.getMessage()));
        }
    }

    private void handleExit(InterceptContext ctx) {
        try {
            TransmissionRecord record = CURRENT.get();
            if (record == null) {
                return;
            }
            CURRENT.remove();
            record.setEndTime(System.currentTimeMillis());
            record.setDuration(record.getEndTime() - record.getStartTime());
            record.setStatus(StatusCode.OK);
            addAndEmit(record, false);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("KafkaHandler.exit 异常: %s", e.getMessage()));
        }
    }

    private void handleException(InterceptContext ctx) {
        try {
            TransmissionRecord record = CURRENT.get();
            if (record == null) {
                return;
            }
            CURRENT.remove();
            record.setEndTime(System.currentTimeMillis());
            record.setDuration(record.getEndTime() - record.getStartTime());
            record.setStatus(StatusCode.ERROR);
            if (ctx.getThrowable() != null) {
                record.setErrorType(ctx.getThrowable().getClass().getName());
                record.setErrorMessage(ctx.getThrowable().getMessage());
            }
            addAndEmit(record, true);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("KafkaHandler.exception 异常: %s", e.getMessage()));
        }
    }

    private void addAndEmit(TransmissionRecord record, boolean isError) {
        if (records.size() >= MAX_RECORDS) {
            records.remove(0);
        }
        records.add(record);
        try {
            com.chua.runtime.apm.storage.StorageManager.appendTransmission(record);
        } catch (Exception ignore) {
        }
        try {
            DependencyGraphHandler handler = ApmBootstrap.getGlobalHandler(DependencyGraphHandler.class);
            if (handler == null) {
                return;
            }
            if (record.getSource() == null || record.getTarget() == null) {
                return;
            }
            String errorType = isError ? record.getErrorType() : null;
            handler.record(record.getSource(), record.getTarget(), record.getProtocol(),
                    record.getSoftware(), record.getDuration(), isError, errorType);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("KafkaHandler emit 异常: %s", e.getMessage()));
        }
    }

    private static String deriveOperation(InterceptContext ctx) {
        String method = ctx.getMethodName();
        return CONSUMER_CLASS.equals(ctx.getClassName()) ? method.toUpperCase() : "PRODUCE";
    }

    /**
     * 反射从 KafkaProducer/KafkaConsumer 读取 bootstrap.servers 配置。
     *
     * <p>KafkaProducer 内部结构：producer -> KafkaProducer(this) -> ... -> configs Map;
     * 简化路径：直接查找字段 "bootstrap.servers" / 通过 reflection 拿到 metadata。
     * 为降低耦合，统一兜底为 "kafka-broker"。</p>
     */
    private static String extractBootstrapBroker(Object client) {
        if (client == null) {
            return "kafka-broker";
        }
        try {
            // KafkaProducer 字段：metadata -> metadataResponse
            // 简化：返回第一个发现的 broker 字段
            Field[] fields = client.getClass().getDeclaredFields();
            for (Field f : fields) {
                if (f.getName().toLowerCase().contains("broker")) {
                    f.setAccessible(true);
                    Object v = f.get(client);
                    if (v != null) {
                        return v.toString();
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "kafka-broker";
    }

    private static String localHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    public List<TransmissionRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }
}