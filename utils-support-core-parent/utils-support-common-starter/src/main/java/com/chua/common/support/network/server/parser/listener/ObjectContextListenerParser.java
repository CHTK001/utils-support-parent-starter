package com.chua.common.support.network.server.parser.listener;

import com.chua.common.support.network.annotations.ListenerParser;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
* ObjectContext 通用注解的监听解析器实现。
*
* <p>解析 {@code objects.annotation} 包下的通用生命周期/消息注解：
* {@code @OnOpen}、{@code @OnMessage}、{@code @OnClose}、{@code @OnError}。
* 这些注解不绑定具体协议，由 HTTP/MQTT/WS 等 Server 根据自身事件模型派发。</p>
*
* <h2>事件类型映射</h2>
* <ul>
*   <li>{@code @OnOpen} → "open"</li>
*   <li>{@code @OnMessage} → "message"</li>
*   <li>{@code @OnClose} → "close"</li>
*   <li>{@code @OnError} → "error"</li>
* </ul>
*
* @author CH
* @since 2026/07/20
 */
public class ObjectContextListenerParser implements ListenerParser {

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
            if (method.isAnnotationPresent(OnOpen.class)
                    || method.isAnnotationPresent(OnMessage.class)
                    || method.isAnnotationPresent(OnClose.class)
                    || method.isAnnotationPresent(OnError.class)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 50;
    }

    /** MatchAnnotation */
    private String matchAnnotation(Method method) {
        if (method.isAnnotationPresent(OnOpen.class)) {
            return "open";
        }
        if (method.isAnnotationPresent(OnMessage.class)) {
            return "message";
        }
        if (method.isAnnotationPresent(OnClose.class)) {
            return "close";
        }
        if (method.isAnnotationPresent(OnError.class)) {
            return "error";
        }
        return null;
    }
}
