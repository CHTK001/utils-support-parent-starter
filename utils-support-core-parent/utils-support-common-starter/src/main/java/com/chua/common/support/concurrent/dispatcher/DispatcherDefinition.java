package com.chua.common.support.concurrent.dispatcher;

import com.chua.common.support.utils.BeanUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 分发器注册定义工具类，封装单个订阅方法及其目标主题。
 * <p>
 * 每个被 {@code @Subscribe} 注解标记的方法都会对应一个 {@code DispatcherDefinition}，
 * 在消息到达时由该对象负责将消息体转换为目标方法参数类型并执行反射调用。
 * </p>
 *
 * @author CH
 * @since 2025-11-26
 */
@Slf4j
public class DispatcherDefinition {

    /**
     * 订阅者对象实例
     */
    @Getter
    /** Subscriber */
    private final Object subscriber;

    /**
     * 目标方法
     */
    private final Method method;

    /**
     * 订阅的主题列表。
     */
    @Getter
    /** Topics */
    private final List<String> topics;

    /**
     * 创建订阅定义实例。
     *
     * @param subscriber 订阅者对象实例
     * @param method 目标订阅方法
     * @param topics 订阅主题列表
     */
    public DispatcherDefinition(Object subscriber, Method method, List<String> topics) {
        this.subscriber = subscriber;
        this.method = method;
        this.topics = topics;
    }

    /**
     * 分派消息到目标方法，自动将 body 转换为方法参数类型。
     *
     * @param body 消息体
     */
    public void dispatch(Object body) {
        try {
            var paramType = method.getParameterTypes()[0];
            var converted = BeanUtils.convert(body, paramType);
            if (converted == null) {
                log.warn("消息体无法转换为目标类型，方法：{}，目标类型：{}", method.getName(), paramType);
                return;
            }
            // 订阅者可能为 package-private/内部类，需放开访问权限（与 KcpClient.safeInvoke 一致）
            method.setAccessible(true);
            method.invoke(subscriber, converted);
        } catch (Exception e) {
            log.error("反射调用订阅方法失败，方法：{}", method.getName(), e);
        }
    }
}
