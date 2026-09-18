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

    /**
     * 添加InjectRule
     * @param target 目标，不允许为 null
     * @param callback 回调，不允许为 null
     */
    public void addInjectRule(String target, InjectCallback callback) {
        injectRules.add(new InjectRule(target, callback));
    }

    /**
     * 获取InjectRules
     * @return 结果列表，无数据时为空列表
     */
    public List<InjectRule> getInjectRules() {
        return injectRules;
    }

    /**
     * 设置Attribute
     * @param key 键，不允许为 null
     * @param value 值，不允许为 null
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    /**
     * 获取Attribute
     * @param key 键，不允许为 null
     * @return T 对象
     */
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 添加DefaultHeader
     * @param name 名称，不允许为 null
     * @param value 值，不允许为 null
     */
    public void addDefaultHeader(String name, String value) {
        if (name != null && value != null) {
            defaultHeaders.put(name, value);
        }
    }

    /**
     * 获取DefaultHeaders
     * @return 结果映射，无数据时为空映射
     */
    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    /**
     * 应用To
     * @param ctx 上下文，不允许为 null
     */
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