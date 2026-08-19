package com.chua.common.support.network.invoker.filter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 共享调用上下文，在一次远程调用中持续存在，跨多次方法调用共享数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SharedInvocationContext {

    /** attributes */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    /** defaultHeaders */
    private final Map<String, String> defaultHeaders = new ConcurrentHashMap<>();
    /** Injectrules */
    private final List<InjectRule> injectRules = new CopyOnWriteArrayList<>();

    /** InjectRule */
    public record InjectRule(String target, InjectCallback callback) {}

    /** 添加InjectRule */
    public void addInjectRule(String target, InjectCallback callback) {
        injectRules.add(new InjectRule(target, callback));
    }

    /** 获取InjectRules */
    public List<InjectRule> getInjectRules() {
        return injectRules;
    }

    /** 设置Attribute */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    /** 获取Attribute */
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /** 添加DefaultHeader */
    public void addDefaultHeader(String name, String value) {
        if (name != null && value != null) {
            defaultHeaders.put(name, value);
        }
    }

    /** 获取DefaultHeaders */
    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    /** 应用To */
    public void applyTo(InvocationContext ctx) {
        defaultHeaders.forEach(ctx::addHeader);
    }

    /** Clear */
    public void clear() {
        attributes.clear();
        defaultHeaders.clear();
        injectRules.clear();
    }
}