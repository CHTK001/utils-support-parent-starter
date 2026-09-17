package com.chua.common.support.concurrent.dispatcher.provider;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
* 内存式分发器提供者工具类，基于 Reactor 实现进程内的发布订阅。
* <p>
* 使用 Sinks.many 作为事件总线，支持背压控制，
* 适用于单进程内不同模块之间的消息解耦。
* </p>
*
* @author CH
* @since 2025-11-26
 */
@Slf4j
@SpiDefault
@Spi("memory")
public class MemoryDispatcherProvider extends AbstractDispatcherProvider {

    /**
    * 主题与 Sink 的映射，每个主题对应一个多播 Sink
    */
    private final Map<String, Sinks.Many<Object>> sinkMap = new ConcurrentHashMap<>();

    /**
    * 主题与订阅定义列表的映射
    */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();

    /**
    * 创建 MemoryDispatcherProvider 实例
    * @param config config
    */
    public MemoryDispatcherProvider(DispatcherConfig config) {
        super(config);
    }

    /**
    * 向指定主题发布消息，若存在订阅者则尝试将消息投递给对应 Sink。
    *
    * @param topic 目标主题
    * @param body 消息体内容
    */
    @Override
    public void publish(String topic, Object body) {
        var definitions = definitionMap.get(topic);
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        var sink = sinkMap.get(topic);
        if (sink == null) {
            return;
        }
        var result = sink.tryEmitNext(body);
        if (result.isFailure()) {
            log.warn("消息发布失败，主题：{}，原因：{}", topic, result);
        }
    }

    /**
    * 为指定订阅定义注册主题订阅，并创建对应的内存 Sink 与消息转发链路。
    *
    * @param definition 订阅定义对象
    */
    @Override
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var definitions = definitionMap.computeIfAbsent(topic, t -> {
                var sink = Sinks.many().multicast().onBackpressureBuffer();
                sinkMap.put(t, sink);
                sink.asFlux()
                        .onBackpressureBuffer()
                        .doOnError(e -> log.error("消息处理异常，主题：{}", topic, e))
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
    * 关闭内存分发器，完成所有 Sink 的结束信号发送并清空注册状态。
    */
    @Override
    public void close() {
        sinkMap.values().forEach(sink -> sink.tryEmitComplete());
        sinkMap.clear();
        definitionMap.clear();
        log.info("内存分发器已关闭");
    }
}
