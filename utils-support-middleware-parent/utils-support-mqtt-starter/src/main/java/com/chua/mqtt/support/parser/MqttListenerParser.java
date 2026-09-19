package com.chua.mqtt.support.parser;

import com.chua.common.support.network.annotations.ListenerParser;
import com.chua.common.support.objects.annotation.OnClose;
import com.chua.common.support.objects.annotation.OnError;
import com.chua.common.support.objects.annotation.OnMessage;
import com.chua.common.support.objects.annotation.OnOpen;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MQTT 注解监听解析器，扫描 @on打开/@on关闭/@on消息。
 *
 * <p>复用现有注解，无需新增 MQTT 专用注解：
 * <ul>
 *   <li>{@code @OnOpen} — 连接建立回调</li>
 *   <li>{@code @OnClose} — 连接断开回调</li>
 *   <li>{@code @OnMessage("topic")} — 消息接收回调，value 指定 topic</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MqttListenerParser implements ListenerParser {

    @Override
    /** 解析 */
    public Map<String, Method> parse(Class<?> clazz) {
        Map<String, Method> result = new LinkedHashMap<>();
        for (Method method : clazz.getDeclaredMethods()) {
            String event = matchAnnotation(method);
            if (event != null) {
                result.put(event, method);
            }
        }
        return result;
    }

    @Override
    /** 支持 */
    public boolean support(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(OnOpen.class)
                    || method.isAnnotationPresent(OnClose.class)
                    || method.isAnnotationPresent(OnMessage.class)
                    || method.isAnnotationPresent(OnError.class)) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 50;
    }

    /**
     * 匹配注解，返回事件类型。
     *
     * <p>对于 @OnMessage，返回格式为 "message:topic"，topic 来自注解的 value 属性。</p>
     * @param method 方法
     * @return 匹配注解的结果
     */
    private String matchAnnotation(Method method) {
        if (method.isAnnotationPresent(OnOpen.class)) {
            return "open";
        }
        if (method.isAnnotationPresent(OnClose.class)) {
            return "close";
        }
        if (method.isAnnotationPresent(OnError.class)) {
            return "error";
        }
        OnMessage onMessage = method.getAnnotation(OnMessage.class);
        if (onMessage != null) {
            String topic = onMessage.value();
            if (topic != null && !topic.isEmpty()) {
                return "message:" + topic;
            }
            return "message";
        }
        return null;
    }
}
