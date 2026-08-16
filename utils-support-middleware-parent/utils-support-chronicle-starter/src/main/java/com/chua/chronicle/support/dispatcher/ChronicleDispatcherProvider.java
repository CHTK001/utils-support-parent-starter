package com.chua.chronicle.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, ChronicleQueue> queueMap = new ConcurrentHashMap<>();
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder().setNameFormat("chronicle-dispatcher-%d").setDaemon(true).build());
    private volatile boolean closed = false;
    private volatile boolean chronicleAvailable = true;
    private final Map<String, ConcurrentLinkedQueue<Object>> fallbackQueueMap = new ConcurrentHashMap<>();

    public ChronicleDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    private ChronicleQueue getOrCreateQueue(String topic) {
        if (!chronicleAvailable) {
            return null;
        }
        try {
            return queueMap.computeIfAbsent(topic, t -> {
                var path = config.getDataPath() != null
                        ? config.getDataPath() + "/" + t
                        : System.getProperty("java.io.tmpdir") + "/chronicle/" + t;
                return SingleChronicleQueueBuilder.single(path).build();
            });
        } catch (Throwable e) {
            log.warn("Chronicle Queue 初始化失败，降级为内存队列。缺少 JVM 参数，请添加 --add-opens 相关参数。错误：{}", e.getMessage());
            chronicleAvailable = false;
            return null;
        }
    }

    @Override
    public void publish(String topic, Object body) {
        var queue = getOrCreateQueue(topic);
        if (queue == null) {
            fallbackQueueMap.computeIfAbsent(topic, t -> new ConcurrentLinkedQueue<>()).add(body);
            log.debug("内存队列已发布消息到主题：{}", topic);
            return;
        }
        try {
            String value;
            try {
                value = body == null ? "" : MAPPER.writeValueAsString(body);
            } catch (Exception e) {
                log.error("Chronicle 序列化消息失败，主题：{}", topic, e);
                return;
            }
            try (var dc = queue.createAppender().writingDocument()) {
                dc.wire().write("msg").text(value);
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
        if (!chronicleAvailable) {
            startFallbackConsumer(topic);
            return;
        }
        ChronicleQueue queue = getOrCreateQueue(topic);
        if (queue == null) {
            chronicleAvailable = false;
            startFallbackConsumer(topic);
            return;
        }
        executor.submit(() -> {
            ExcerptTailer tailer = queue.createTailer();
            while (!closed) {
                try (var dc = tailer.readingDocument()) {
                    if (dc.isPresent() && dc.isData()) {
                        var text = dc.wire().read("msg").text();
                        if (text != null) {
                            var definitions = definitionMap.get(topic);
                            if (definitions != null) {
                                for (var def : definitions) {
                                    try {
                                        Object payload = deserialize(text, def);
                                        System.out.println("[CHRONICLE-CONSUME] topic=" + topic + " payload=" + payload);
                                        def.dispatch(payload);
                                    } catch (Exception e) {
                                        log.warn("订阅方法执行异常，主题：{}", topic, e);
                                    }
                                }
                            }
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

    private void startFallbackConsumer(String topic) {
        executor.submit(() -> {
            var fallbackQueue = fallbackQueueMap.computeIfAbsent(topic, t -> new ConcurrentLinkedQueue<>());
            while (!closed) {
                var body = fallbackQueue.poll();
                if (body != null) {
                    var definitions = definitionMap.get(topic);
                    if (definitions != null) {
                        for (var def : definitions) {
                            try {
                                System.out.println("[FALLBACK-CONSUME] topic=" + topic + " body=" + body);
                                def.dispatch(body);
                            } catch (Exception e) {
                                log.warn("订阅方法执行异常，主题：{}", topic, e);
                            }
                        }
                    }
                } else {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
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
     */
    private Class<?> inferType(DispatcherDefinition definition) {
        try {
            // 拿到类上的实际泛型参数 T（来自 ConsumerDispatcherDefinition<T>）
            java.lang.reflect.Type genericSuper = definition.getClass().getGenericSuperclass();
            Class<?> match = extractFirstTypeArg(genericSuper);
            if (match != null) {
                return match;
            }
            // fallback：从父类字段类型推断
            var field = definition.getClass().getSuperclass().getDeclaredField("consumer");
            field.setAccessible(true);
            java.lang.reflect.Type fieldGenericType = field.getGenericType();
            if (fieldGenericType instanceof java.lang.reflect.ParameterizedType pt) {
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> c) {
                    return c;
                }
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
        fallbackQueueMap.clear();
    }
}