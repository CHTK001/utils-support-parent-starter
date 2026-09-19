package com.chua.common.support.concurrent.dispatcher.provider;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 内存队列分发器提供者，基于 {@link LinkedBlockingQueue} 实现纯内存发布订阅。
 *
 * <p>适用于同 JVM 内轻量级 pub/sub，无需磁盘持久化或外部中间件。
 * 消息直接通过队列传递，不经过序列化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class InMemoryDispatcherProvider extends AbstractDispatcherProvider implements DispatcherProvider {

    /**
     * 主题队列映射
    */
    private final Map<String, LinkedBlockingQueue<Object>> topicQueues = new ConcurrentHashMap<>();
    /**
     * 分发定义映射
    */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();
    /**
     * 线程池执行器
    */
    private final ExecutorService executor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("inmem-dispatcher-", 0).factory());
    /**
     * 是否已关闭
    */
    private volatile boolean closed = false;

    /**
     * 队列容量
    */
    private static final int QUEUE_CAPACITY = 50000;

    /**
     * 创建 InMemoryDispatcherProvider 实例
     * @param config config
     */
    public InMemoryDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    @Override
    /**
     * 发布
    */
    public void publish(String topic, Object body) {
        if (closed) {
            return;
        }
        var queue = topicQueues.computeIfAbsent(topic, t -> new LinkedBlockingQueue<>(QUEUE_CAPACITY));
        if (!queue.offer(body)) {
            log.warn("内存队列已满，丢弃消息，topic={}", topic);
        }
    }

    @Override
    /**
     * 订阅
    */
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var isFirst = definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).isEmpty();
            definitionMap.get(topic).add(definition);
            if (isFirst && !closed) {
                startConsumer(topic);
            }
        }
    }

    @Override
    /**
     * 取消订阅
    */
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var list = definitionMap.get(topic);
            if (list != null) {
                list.remove(definition);
                if (list.isEmpty()) {
                    definitionMap.remove(topic);
                }
            }
        }
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        closed = true;
        topicQueues.clear();
        definitionMap.clear();
    }

    /**
     * 开始Consumer
     * @param topic 方法入参 topic
     */
    private void startConsumer(String topic) {
        executor.submit(() -> {
            var queue = topicQueues.computeIfAbsent(topic, t -> new LinkedBlockingQueue<>(QUEUE_CAPACITY));
            log.info("InMemory 消费者已启动 topic={}", topic);
            while (!closed) {
                try {
                    var body = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (body == null) {
                        continue;
                    }
                    var definitions = definitionMap.get(topic);
                    if (definitions != null) {
                        for (var def : definitions) {
                            try {
                                def.dispatch(body);
                            } catch (Exception e) {
                                log.warn("订阅方法执行异常，topic={}", topic, e);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("InMemory 消费者已停止 topic={}", topic);
        });
    }
}
