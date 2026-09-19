package com.chua.runtime.apm.handler;

import com.chua.common.support.reflection.ReflectUtils;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kafka 应用层 处理器 — 拦截 kafkaproducer / kafkaconsumer 并生成应用语义传输记录。
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
 * 若 Kafka 不在 类路径 则 spy转换 找不到类而不生效（无副作用）</li>
 *   <li>从 ctx.userData 反射读取 broker 地址</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KafkaHandler implements Plugin, RuntimeSpy.Interceptor {
    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(KafkaHandler.class.getName());

    /**
     * kafkaproducer 内部名
     */
    private static final String PRODUCER_CLASS = "org/apache/kafka/clients/producer/KafkaProducer";

    /**
     * kafkaconsumer 内部名
     */
    private static final String CONSUMER_CLASS = "org/apache/kafka/clients/consumer/KafkaConsumer";

    /**
     * 最大记录数
     */
    private static final int MAX_RECORDS = 5000;

    /**
     * records
     */
    private final com.chua.runtime.apm.handler.BoundedRecordList<TransmissionRecord> records;
    /**
     * 已启用
     */
    private boolean enabled;
    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /** 创建 kafka处理器 实例 */
    public KafkaHandler() {
        this.records = new com.chua.runtime.apm.handler.BoundedRecordList<>(10000);
        this.started = new AtomicBoolean(false);
    }

    @Override
    /** 名称 */
    public String name() {
        return "kafka-handler";
    }

    @Override
    /** 版本 */
    public String version() {
        return "1.0.0";
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.enabled = "true".equals(context.getProperty("kafka.enabled", "true"));
        LOG.log(Level.INFO, String.format("KafkaHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    /** 开始 */
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
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "KafkaHandler 停止");
    }

    @Override
    /** 状态 */
    public String status() {
        return String.format("KafkaHandler[enabled=%s, records=%d]", enabled, records.size());
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 注册 kafkaproducer/kafkaconsumer 关键方法插桩规则。
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
                "(Lorg/apache/kafka/clients/producer/ProducerRecord;Lorg/apache/kafka/clients/producer/Callback;)" +
                        "Ljava/util/concurrent/Future;",
                InterceptPoint.EXIT, this);

        // Consumer: poll(long)
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "poll", "(J)Lorg/apache/kafka/clients/consumer/ConsumerRecords;",
                InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(CONSUMER_CLASS, "poll", "(J)Lorg/apache/kafka/clients/consumer/ConsumerRecords;",
                InterceptPoint.EXIT, this);

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
    /** onintercept */
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

    /**
     * 当前
     */
    private static final ThreadLocal<TransmissionRecord> CURRENT = new ThreadLocal<>();

    /**
     * 处理Entry
     *
     * @param ctx ctx
     */
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

    /**
     * 处理Exit
     *
     * @param ctx ctx
     */
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

    /**
     * 处理异常
     *
     * @param ctx ctx
     */
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

    /**
     * 添加和发送
     *
     * @param record record
     * @param isError 是否错误
     */
    private void addAndEmit(TransmissionRecord record, boolean isError) {
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

    /**
     * deriveoperation
     *
     * @param ctx ctx
     * @return deriveOperation的结果
     */
    private static String deriveOperation(InterceptContext ctx) {
        String method = ctx.getMethodName();
        return CONSUMER_CLASS.equals(ctx.getClassName()) ? method.toUpperCase() : "PRODUCE";
    }

    /**
     * 反射从 kafkaproducer/kafkaconsumer 读取 bootstrap.服务端 配置。
     *
     * <p>KafkaProducer 内部结构：producer -> KafkaProducer(this) -> ... -> configs Map;
     * 简化路径：直接查找字段 "bootstrap.服务端" / 通过 反射 拿到 metadata。
     * 为降低耦合，统一兜底为 "Kafka-broker"。</p>
     * @param client 客户端
     * @return extractBootstrapBroker的结果
     */
    private static String extractBootstrapBroker(Object client) {
        if (client == null) {
            return "kafka-broker";
        }
        try {
            Object v = ReflectUtils.findFieldBySubstring(client, "broker");
            if (v != null) {
                return v.toString();
            }
        } catch (Exception ignore) {
        }
        return "kafka-broker";
    }

    /**
     * 本地主机
     *
     * @return 本地主机的结果
     */
    private static String localHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * 获取Records
     *
     * @return 获取records的结果
     */
    public List<TransmissionRecord> getRecords() {
        return records.snapshot();
    }
}
