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

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final Map<String, String> defaultHeaders = new ConcurrentHashMap<>();
    /** Injectrules */
    private final List<InjectRule> injectRules = new CopyOnWriteArrayList<>();

    public record InjectRule(String target, InjectCallback callback) {}

    public void addInjectRule(String target, InjectCallback callback) {
        injectRules.add(new InjectRule(target, callback));
    }

    public List<InjectRule> getInjectRules() {
        return injectRules;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public void addDefaultHeader(String name, String value) {
        if (name != null && value != null) {
            defaultHeaders.put(name, value);
        }
    }

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public void applyTo(InvocationContext ctx) {
        defaultHeaders.forEach(ctx::addHeader);
    }

    public void clear() {
        attributes.clear();
        defaultHeaders.clear();
        injectRules.clear();
    }
}