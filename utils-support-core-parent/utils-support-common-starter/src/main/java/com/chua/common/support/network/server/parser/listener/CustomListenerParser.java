package com.chua.common.support.network.server.parser.listener;

import com.chua.common.support.network.annotations.ListenerParser;
import com.chua.common.support.network.annotations.OnEventOpen;
import com.chua.common.support.network.annotations.OnEventMessage;
import com.chua.common.support.network.annotations.OnEventClose;
import com.chua.common.support.network.annotations.OnEventError;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义事件注解的监听解析器实现。
 *
 * <p>解析 {@code @OnEventOpen}、{@code @OnEventMessage}、{@code @OnEventClose}、{@code @OnEventError}
 * 四个自定义注解，将方法映射为对应的事件类型。
 *
 * <h2>事件类型映射</h2>
 * <ul>
 *   <li>{@code @OnEventOpen} → "open"</li>
 *   <li>{@code @OnEventMessage} → "message"</li>
 *   <li>{@code @OnEventClose} → "close"</li>
 *   <li>{@code @OnEventError} → "error"</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
public class CustomListenerParser implements ListenerParser {

    @Override
    /** 解析 */
    public Map<String, Method> parse(Class<?> clazz) {
        Map<String, Method> result = new LinkedHashMap<>();
        for (Method method : clazz.getDeclaredMethods()) {
            String event = matchAnnotation(method);
            if (event != null) {
                method.setAccessible(true);
                result.put(event, method);
            }
        }
        return result;
    }

    @Override
    /** Support */
    public boolean support(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnEventOpen.class)
                    || method.isAnnotationPresent(OnEventMessage.class)
                    || method.isAnnotationPresent(OnEventClose.class)
                    || method.isAnnotationPresent(OnEventError.class)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 100;
    }

    /** MatchAnnotation */
    private String matchAnnotation(Method method) {
        if (method.isAnnotationPresent(OnEventOpen.class)) {
            return "open";
        }
        if (method.isAnnotationPresent(OnEventMessage.class)) {
            return "message";
        }
        if (method.isAnnotationPresent(OnEventClose.class)) {
            return "close";
        }
        if (method.isAnnotationPresent(OnEventError.class)) {
            return "error";
        }
        return null;
    }
}