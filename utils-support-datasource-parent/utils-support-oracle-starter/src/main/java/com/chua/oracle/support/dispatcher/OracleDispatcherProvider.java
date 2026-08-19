package com.chua.oracle.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Oracle CDC 分发器提供者，模拟 Oracle LogMiner 变更数据捕获事件流。
 * <p>
 * 基于 Reactor Sinks 实现进程内发布订阅，主题约定为：
 * {@code oracle.cdc.<schema>.<table>}，消息体为 JSON 格式的变更事件，
 * 包含操作类型（INSERT/UPDATE/DELETE）、变更前后数据等信息。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("oracle")
public class OracleDispatcherProvider extends AbstractDispatcherProvider {

    /**
     * 主题与 Sink 的映射，每个主题对应一个多播 Sink
     */
    private final Map<String, Sinks.Many<Object>> sinkMap = new ConcurrentHashMap<>();

    /**
     * 主题与订阅定义列表的映射
     */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 创建 OracleDispatcherProvider 实例
     * @param config config
     */
    public OracleDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    /**
     * 无参构造，使用默认配置，供 SPI 自动实例化。
     */
    public OracleDispatcherProvider() {
        this(DispatcherConfig.builder().build());
    }

    /**
     * 向指定 Oracle CDC 主题发布变更事件。
     *
     * @param topic 目标主题，格式为 oracle.cdc.<schema>.<table>
     * @param body  变更事件 JSON 字符串
     */
    @Override
    public void publish(String topic, Object body) {
        if (closed) {
            return;
        }
        var definitions = definitionMap.get(topic);
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        var sink = sinkMap.computeIfAbsent(topic, t -> Sinks.many().multicast().onBackpressureBuffer());
        var result = sink.tryEmitNext(body);
        if (result.isFailure()) {
            log.warn("Oracle CDC 事件发布失败，主题：{}，原因：{}", topic, result);
        }
    }

    /**
     * 注册 Oracle CDC 主题订阅。
     *
     * @param definition 订阅定义，主题列表中的每个主题应符合 oracle.cdc.<schema>.<table> 格式
     */
    @Override
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var definitions = definitionMap.computeIfAbsent(topic, t -> {
                var sink = Sinks.many().multicast().onBackpressureBuffer();
                sinkMap.put(t, sink);
                sink.asFlux()
                        .onBackpressureBuffer()
                        .doOnError(e -> log.error("Oracle CDC 消息处理异常，主题：{}", topic, e))
                        .retry()
                        .subscribe(body -> {
                            for (var def : definitionMap.getOrDefault(topic, List.of())) {
                                def.dispatch(body);
                            }
                        });
                return new CopyOnWriteArrayList<DispatcherDefinition>();
            });
            definitions.add(definition);
        }
    }

    /**
     * 取消指定订阅定义在目标主题上的注册关系。
     *
     * @param definition 待取消的订阅定义对象
     */
    @Override
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var definitions = definitionMap.get(topic);
            if (definitions != null) {
                definitions.remove(definition);
                if (definitions.isEmpty()) {
                    definitionMap.remove(topic);
                    sinkMap.remove(topic);
                }
            }
        }
    }

    /**
     * 关闭 Oracle CDC 分发器，完成所有 Sink 的结束信号发送并清空注册状态。
     */
    @Override
    public void close() {
        closed = true;
        sinkMap.values().forEach(sink -> sink.tryEmitComplete());
        sinkMap.clear();
        definitionMap.clear();
        log.info("Oracle CDC 分发器已关闭");
    }
}
