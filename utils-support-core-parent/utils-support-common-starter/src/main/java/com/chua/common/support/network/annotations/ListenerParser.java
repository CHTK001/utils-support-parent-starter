package com.chua.common.support.network.annotations;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 监听解析器 SPI，将事件监听注解解析为回调处理器。
 * <p>
 * 支持以下实现：
 * <ul>
 *   <li>Javax — 解析 {@code @OnOpen}、{@code @OnMessage}、{@code @OnClose} 等</li>
 *   <li>自定义 — 解析 {@code @OnEventOpen}、{@code @OnEventMessage} 等</li>
 * </ul>
 *
 * @author CH
 */
public interface ListenerParser {

    /**
     * 解析指定类中的监听方法。
     *
     * @param clazz 监听器类
     * @return 事件类型到方法的映射
     */
    Map<String, Method> parse(Class<?> clazz);

    /**
     * 判断是否支持该类型。
     *
     * @param clazz 监听器类
     * @return 支持返回 true
     */
    boolean support(Class<?> clazz);

    /**
     * 获取 SPI 排序值。
     *
     * <p>值越小优先级越高。
     *
     * @return 排序值，默认 0
     */
    default int getOrder() {
        return 0;
    }
}
