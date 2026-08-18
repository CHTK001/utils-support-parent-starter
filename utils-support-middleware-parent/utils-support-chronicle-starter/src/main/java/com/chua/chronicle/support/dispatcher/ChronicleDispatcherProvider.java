package com.chua.chronicle.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.ConsumerDispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Chronicle Queue 分发器提供者，基于 Chronicle Queue 实现进程内的持久化发布订阅。
 *
 * <p>消息体使用 Jackson 进行 JSON 序列化，确保复杂对象（如 {@code List<Map<String,Object>>}）
 * 在发布-订阅链路中保持类型一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("chronicle")
public class ChronicleDispatcherProvider extends AbstractDispatcherProvider {

    private static final ChronicleQueueSerializer SERIALIZER = new ChronicleQueueSerializer();

    private final Map<String, ChronicleQueue> queueMap = new ConcurrentHashMap<>();
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("chronicle-dispatcher-", 0).factory());
    private volatile boolean closed = false;

    public ChronicleDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    private ChronicleQueue getOrCreateQueue(String topic) {
        try {
            return queueMap.computeIfAbsent(topic, t -> {
                var path = config.getDataPath() != null
                        ? config.getDataPath() + "/" + t
                        : System.getProperty("java.io.tmpdir") + "/chronicle/" + t;
                return SingleChronicleQueueBuilder.single(path).build();
            });
        } catch (Throwable e) {
            throw new RuntimeException("Chronicle Queue 初始化失败。请添加 --add-opens 相关 JVM 参数，或使用 directDispatch=true 绕过。topic=" + topic, e);
        }
    }

    @Override
    public void publish(String topic, Object body) {
        var queue = getOrCreateQueue(topic);
        try {
            try (var dc = queue.createAppender().writingDocument()) {
                byte[] data = SERIALIZER.serialize(body);
                dc.wire().write("msg").bytes(data);
            }
            log.debug("Chronicle 已发布消息到主题：{}", topic);
        } catch (Throwable t) {
            log.error("Chronicle 发布异常，主题：{}", topic, t);
        }
    }

    @Override
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var isFirst = definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).isEmpty();
            definitionMap.get(topic).add(definition);
            if (isFirst && !closed) {
                startConsumer(topic);
            }
        }
    }

    private void startConsumer(String topic) {
        ChronicleQueue queue = getOrCreateQueue(topic);
executor.submit(() -> {
            ExcerptTailer tailer = queue.createTailer().toStart();
            while (!closed) {
                try (var dc = tailer.readingDocument()) {
                    if (dc.isPresent() && dc.isData()) {
                        var bytes = dc.wire().read("msg").bytes();
                        if (bytes != null) {
                            var payload = SERIALIZER.deserialize(bytes);
                            if (payload != null) {
                                var definitions = definitionMap.get(topic);
                                if (definitions != null) {
                                    for (var def : definitions) {
                                        try {
                                            def.dispatch(payload);
                                        } catch (Exception e) {
                                            log.warn("订阅方法执行异常，主题：{}", topic, e);
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // 无数据时短暂休眠，避免 busy-spin
                        try { Thread.sleep(1); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (!closed) {
                        log.error("Chronicle 消费异常，主题：{}", topic, e);
                    }
                } catch (Throwable e) {
                    if (!closed) {
                        log.error("Chronicle 严重错误，主题：{}", topic, e);
                    }
                }
            }
        });
    }

    /**
     * 根据订阅者的泛型类型还原反序列化 payload。
     */
    private Object deserialize(String text, DispatcherDefinition definition) {
        try {
            Class<?> type = inferType(definition);
            if (type == null || type == String.class) {
                return text;
            }
            if (type == Void.TYPE) {
                return null;
            }
            return MAPPER.readValue(text, type);
        } catch (Exception e) {
            log.warn("Chronicle 反序列化失败，原始字符串：{}", text, e);
            return text;
        }
    }

    /**
     * 通过反射拿到 ConsumerDispatcherDefinition 上的泛型类型 T。
     * <p>
     * 泛型 T 在普通实例化时会被擦除，优先使用定义上显式携带的 bodyType；
     * 未携带时（匿名子类或继承）再退回反射推断。
     * </p>
     */
    private Class<?> inferType(DispatcherDefinition definition) {
        try {
            // 优先使用显式指定的消息体类型，避免泛型擦除导致类型丢失
            if (definition instanceof ConsumerDispatcherDefinition<?> cdd) {
                Class<?> bodyType = cdd.getBodyType();
                if (bodyType != null && bodyType != Object.class) {
                    return bodyType;
                }
            }
            // 拿到类上的实际泛型参数 T（来自 ConsumerDispatcherDefinition<T>）
            java.lang.reflect.Type genericSuper = definition.getClass().getGenericSuperclass();
            Class<?> match = extractFirstTypeArg(genericSuper);
            if (match != null) {
                return match;
            }
            // fallback：从实例自身到父类链上查找 consumer 字段来推断泛型（ConsumerDispatcherDefinition 的字段在自身）
            Class<?> current = definition.getClass();
            while (current != null && current != Object.class) {
                try {
                    var field = current.getDeclaredField("consumer");
                    field.setAccessible(true);
                    java.lang.reflect.Type fieldGenericType = field.getGenericType();
                    if (fieldGenericType instanceof java.lang.reflect.ParameterizedType pt) {
                        java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                        if (args.length > 0) {
                            // 直接是 Class（如 List）则直接使用；是嵌套泛型（如 List<Map<..>>）则取其原始类型（List）
                            if (args[0] instanceof Class<?> c) {
                                return c;
                            }
                            if (args[0] instanceof java.lang.reflect.ParameterizedType pp) {
                                java.lang.reflect.Type raw = pp.getRawType();
                                if (raw instanceof Class<?> c) {
                                    return c;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                current = current.getSuperclass();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Class<?> extractFirstTypeArg(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> c) {
                return c;
            }
        }
        return null;
    }

    @Override
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
    public void close() {
        closed = true;
        executor.shutdown();
        queueMap.values().forEach(ChronicleQueue::close);
        queueMap.clear();
        definitionMap.clear();
    }

    /**
     * 序列化工具：优先使用 Fury（性能最优），不可用时降级到 Jackson。
     */
    static class ChronicleQueueSerializer {
        private final com.chua.common.support.base.serialize.Serialization fury;
        private final com.fasterxml.jackson.databind.ObjectMapper fallback;
        private final boolean useFury;

        ChronicleQueueSerializer() {
            com.chua.common.support.base.serialize.Serialization f = null;
            try {
                f = new com.chua.fory.support.serialize.ForySerialization();
            } catch (Throwable t) {
                log.info("Fury 不可用，降级为 Jackson 序列化: {}", t.getMessage());
            }
            this.fury = f;
            this.useFury = f != null;
            this.fallback = new com.fasterxml.jackson.databind.ObjectMapper();
        }

        byte[] serialize(Object obj) {
            try {
                if (useFury) {
                    return fury.serialize(obj);
                }
                return fallback.writeValueAsBytes(obj);
            } catch (Exception e) {
                throw new RuntimeException("序列化失败", e);
            }
        }

        Object deserialize(byte[] data) {
            try {
                if (useFury) {
                    return fury.deserialize(data, Object.class);
                }
                return fallback.readValue(data, Object.class);
            } catch (Exception e) {
                log.warn("反序列化失败，尝试 Jackson 降级", e);
                try {
                    return fallback.readValue(data, Object.class);
                } catch (Exception ex) {
                    throw new RuntimeException("反序列化失败", ex);
                }
            }
        }
    }
}