package com.chua.nats.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NATS 分发器提供者，基于 NATS Core Pub/Sub 实现跨进程的发布订阅。
 *
 * @author CH
 * @since 2026-07-29
 */
@Slf4j
@Spi("nats")
public class NatsDispatcherProvider extends AbstractDispatcherProvider {

    /** NATS 连接 */
    private Connection connection;

    /** NATS 分发器 */
    private Dispatcher dispatcher;

    /** 主题与订阅定义列表的映射 */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /** 是否已关闭 */
    private volatile boolean closed = false;

    public NatsDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    @Override
    public void start() {
        try {
            var options = new Options.Builder()
                    .server(config.getUrl())
                    .connectionTimeout(java.time.Duration.ofSeconds(5))
                    .reconnectWait(java.time.Duration.ofSeconds(2))
                    .maxReconnects(-1) // 无限重连
                    .build();

            this.connection = Nats.connect(options);
            this.dispatcher = connection.createDispatcher(msg -> {
                var topic = msg.getSubject();
                var definitions = definitionMap.get(topic);
                if (definitions != null) {
                    var body = new String(msg.getData(), StandardCharsets.UTF_8);
                    for (var def : definitions) {
                        def.dispatch(body);
                    }
                }
            });
            log.info("NATS 分发器启动: url={}", config.getUrl());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("创建 NATS 连接失败: " + config.getUrl(), e);
        }
    }

    @Override
    public void publish(String topic, Object body) {
        if (connection == null || connection.getStatus() != Connection.Status.CONNECTED) {
            log.warn("NATS 未连接，无法发布消息到: {}", topic);
            return;
        }
        var value = body == null ? "" : body.toString();
        connection.publish(topic, value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(definition);
            if (!closed && connection != null) {
                dispatcher.subscribe(topic);
            }
        }
    }

    @Override
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var definitions = definitionMap.get(topic);
            if (definitions != null) {
                definitions.remove(definition);
                if (definitions.isEmpty()) {
                    definitionMap.remove(topic);
                    if (dispatcher != null) {
                        dispatcher.unsubscribe(topic);
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (dispatcher != null) {
                dispatcher.unsubscribe("*");
            }
            if (connection != null) {
                connection.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("关闭 NATS 连接被中断");
        }
        definitionMap.clear();
        log.info("NATS 分发器关闭");
    }
}
