package com.chua.common.support.concurrent.dispatcher;

import com.chua.common.support.concurrent.dispatcher.provider.MemoryDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 分发器流程工具类，统一管理消息发布、订阅注册和生命周期清理。
 * <p>
 * 支持同时挂载多个 {@link DispatcherProvider} 实例（如内存、Kafka、RabbitMQ 等），
 * 通过反射扫描 {@link Subscribe} 注解完成自动订阅，并在关闭时统一释放所有 Provider 资源。
 * </p>
 *
 * @author CH
 * @since 2025-11-26
 */
@Slf4j
public class DispatcherFlow {

    /**
     * 已注册的分发器提供者列表
     */
    private final List<DispatcherProvider> providers = new CopyOnWriteArrayList<>();

    /**
     * 订阅者对象与其注册定义列表的映射，用于卸载
     */
    private final Map<Object, List<DispatcherDefinition>> subscriberDefinitions = new ConcurrentHashMap<>();

    /**
     * 创建一个分发器入口，自动注册默认的内存分发器。
     */
    public DispatcherFlow() {
        this(true);
    }

    /**
     * 创建一个分发器入口。
     *
     * @param withDefault 是否注册默认的内存分发器
     */
    public DispatcherFlow(boolean withDefault) {
        if (withDefault) {
            register(new MemoryDispatcherProvider(DispatcherConfig.builder().build()));
        }
    }

    /**
     * 注册一个分发器提供者。
     *
     * @param provider 分发器提供者实例
     */
    public void register(DispatcherProvider provider) {
        providers.add(provider);
        log.info("已注册分发器：{}", provider.getClass().getSimpleName());
    }

    /**
     * 发布消息到指定主题，所有已注册的分发器都将收到消息。
     *
     * @param topic 目标主题
     * @param body  消息体
     */
    public void publish(String topic, Object body) {
        for (var provider : providers) {
            provider.publish(topic, body);
        }
    }

    /**
     * 注册订阅者对象，通过反射扫描 {@link Subscribe} 注解自动订阅。
     * <p>
     * 每个标注了 {@link Subscribe} 的方法（必须有且仅有一个入参）生成一个 {@link DispatcherDefinition}，
     * 根据注解中指定的 topic 和 type 注册到对应的 Provider：
     * <ul>
     *   <li>type 为空时注册给第一个 Provider</li>
     *   <li>type 指定时只注册给匹配该 SPI 类型的 Provider</li>
     * </ul>
     * </p>
     *
     * @param subscriber 订阅者实例对象
     * @return 注册定义列表
     * @throws IllegalArgumentException 当 subscriber 为 Class 或接口时抛出
     */
    public List<DispatcherDefinition> register(Object subscriber) {
        if (subscriber instanceof Class) {
            throw new IllegalArgumentException("不支持注册 Class 类型，请传入实例对象");
        }
        if (subscriber.getClass().isInterface()) {
            throw new IllegalArgumentException("不支持注册接口类型，请传入实例对象");
        }

        var definitions = new ArrayList<DispatcherDefinition>();
        var clazz = subscriber.getClass();

        for (var method : clazz.getMethods()) {
            var subscribe = method.getAnnotation(Subscribe.class);
            if (subscribe == null) {
                continue;
            }
            if (method.getParameterCount() != 1) {
                log.warn("@Subscribe 方法必须有且仅有一个入参，跳过：{}#{}", clazz.getSimpleName(), method.getName());
                continue;
            }

            var topics = List.of(subscribe.topic());
            var definition = new DispatcherDefinition(subscriber, method, topics);
            definitions.add(definition);

            var matchedProviders = resolveProviders(subscribe.type());
            for (var provider : matchedProviders) {
                provider.subscribe(definition);
            }

            log.debug("已订阅主题：{}，方法：{}#{}", topics, clazz.getSimpleName(), method.getName());
        }

        subscriberDefinitions.put(subscriber, definitions);
        return definitions;
    }

    /**
     * 卸载订阅者对象，遍历所有定义并调用每个 Provider 的 unsubscribe。
     *
     * @param subscriber 订阅者对象
     */
    public void unregister(Object subscriber) {
        var definitions = subscriberDefinitions.remove(subscriber);
        if (definitions == null) {
            return;
        }
        for (var definition : definitions) {
            for (var provider : providers) {
                provider.unsubscribe(definition);
            }
        }
        log.info("已卸载订阅者：{}", subscriber.getClass().getSimpleName());
    }

    /**
     * 根据 SPI 类型解析目标 Provider 列表。
     *
     * @param type SPI 类型标识，为空时返回第一个 Provider
     * @return 匹配的 Provider 列表
     */
    private List<DispatcherProvider> resolveProviders(String type) {
        if (type == null || type.isEmpty()) {
            return providers.isEmpty() ? List.of() : List.of(providers.getFirst());
        }
        var result = new ArrayList<DispatcherProvider>();
        for (var provider : providers) {
            var spi = provider.getClass().getAnnotation(Spi.class);
            if (spi != null) {
                for (var value : spi.value()) {
                    if (type.equals(value)) {
                        result.add(provider);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * 关闭所有已注册的分发器提供者。
     */
    public void close() {
        for (var provider : providers) {
            try {
                provider.close();
            } catch (Exception e) {
                log.warn("关闭分发器失败：{}", provider.getClass().getSimpleName(), e);
            }
        }
        providers.clear();
        subscriberDefinitions.clear();
    }
}
