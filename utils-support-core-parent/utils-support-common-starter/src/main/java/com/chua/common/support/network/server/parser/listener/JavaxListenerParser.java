package com.chua.common.support.network.server.parser.listener;

import com.chua.common.support.network.annotations.ListenerParser;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Javax WebSocket 注解的监听解析器实现。
 *
 * <p>解析 {@code javax.websocket} 和 {@code jakarta.websocket} 注解：
 * {@code @OnOpen}、{@code @OnMessage}、{@code @OnClose}、{@code @OnError}。
 * 使用反射方式加载注解，不依赖 WebSocket 运行时。
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
 * @since 2026/07/16
 */
public class JavaxListenerParser implements ListenerParser {

    /** javax.websocket 注解类名列表 */
    private static final String[] JAVAX_ANNOTATIONS = {
            "javax.websocket.OnOpen",
            "javax.websocket.OnMessage",
            "javax.websocket.OnClose",
            "javax.websocket.OnError"
    };

    /** jakarta.websocket 注解类名列表 */
    private static final String[] JAKARTA_ANNOTATIONS = {
            "jakarta.websocket.OnOpen",
            "jakarta.websocket.OnMessage",
            "jakarta.websocket.OnClose",
            "jakarta.websocket.OnError"
    };

    @Override
    /** 解析 */
    public Map<String, Method> parse(Class<?> clazz) {
        Map<String, Method> result = new LinkedHashMap<>();
        for (Method method : clazz.getDeclaredMethods()) {
            String event = matchJavaxAnnotation(method);
            if (event == null) {
                event = matchJakartaAnnotation(method);
            }
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
            if (hasAnyAnnotation(method, JAVAX_ANNOTATIONS, clazz.getClassLoader())) {
                return true;
            }
            if (hasAnyAnnotation(method, JAKARTA_ANNOTATIONS, clazz.getClassLoader())) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 0;
    }

    /**
     * 检查方法是否标注了 javax.websocket 注解。
     *
     * @param method 目标方法
     * @return 事件类型名，未匹配返回 null
     */
    private String matchJavaxAnnotation(Method method) {
        for (String annClass : JAVAX_ANNOTATIONS) {
            try {
                Class<?> c = Class.forName(annClass, false, method.getDeclaringClass().getClassLoader());
                if (method.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) c)) {
                    return extractEventName(annClass);
                }
            } catch (ClassNotFoundException e) {
                // javax.websocket 注解类不在 classpath 中，忽略
            }
        }
        return null;
    }

    /**
     * 检查方法是否标注了 jakarta.websocket 注解。
     *
     * @param method 目标方法
     * @return 事件类型名，未匹配返回 null
     */
    private String matchJakartaAnnotation(Method method) {
        for (String annClass : JAKARTA_ANNOTATIONS) {
            try {
                Class<?> c = Class.forName(annClass, false, method.getDeclaringClass().getClassLoader());
                if (method.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) c)) {
                    return extractEventName(annClass);
                }
            } catch (ClassNotFoundException e) {
                // jakarta.websocket 注解类不在 classpath 中，忽略
            }
        }
        return null;
    }

    /**
     * 检查方法上是否存在列表中的任一注解。
     *
     * @param method      目标方法
     * @param annotations 注解类名列表
     * @param classLoader 类加载器
     * @return 存在返回 true，否则返回 false
     */
    private boolean hasAnyAnnotation(Method method, String[] annotations, ClassLoader classLoader) {
        for (String annClass : annotations) {
            try {
                Class<?> c = Class.forName(annClass, false, classLoader);
                if (method.isAnnotationPresent((Class<? extends java.lang.annotation.Annotation>) c)) {
                    return true;
                }
            } catch (ClassNotFoundException e) {
                // 注解类不在 classpath 中，忽略
            }
        }
        return false;
    }

    /**
     * 从注解全限定类名中提取事件名。
     * 如 {@code javax.websocket.OnOpen} → "open"。
     *
     * @param fullClassName 注解全限定类名
     * @return 事件名（小写）
     */
    private String extractEventName(String fullClassName) {
        String simpleName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
        return simpleName.replace("On", "").toLowerCase();
    }
}