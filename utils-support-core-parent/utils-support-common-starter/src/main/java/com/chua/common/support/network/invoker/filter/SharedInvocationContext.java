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

    /**
     * attributes
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    /**
     * defaultHeaders
     */
    private final Map<String, String> defaultHeaders = new ConcurrentHashMap<>();
    /**
     * Injectrules
     */
    private final List<InjectRule> injectRules = new CopyOnWriteArrayList<>();

    /**
     * 注入规则。
     *
     * <p>把「一次远程调用前要往请求里补什么值」的声明打包成不可变对：
     * {@link #target()} 说明补到哪里，{@link #callback()} 说明值从哪来。
     * 注册入口是 {@code Invoker#addInject(String, InjectCallback)} /
     * {@code HttpApiOptions#addInject(String, InjectCallback)} / {@code @RemoteInject} 注解；
     * 实际执行方是 SPI {@code InvokerInjectServerFilter}（order 为 {@code Integer.MIN_VALUE + 1000}），
     * 它在每次调用前遍历规则列表、调用 {@link #callback()} 取值，再按 {@link #target()} 的前缀分流。</p>
     *
     * <p>本 record 不定义紧凑构造器，两个组件都不做校验；调用链上的约定是二者都不允许为
     * {@code null}（{@code HttpApiOptions#addInject} 会过滤掉 {@code null} 参数，
     * 而 {@code SharedInvocationContext#addInjectRule} 不校验，直接构造）。</p>
     *
     * @param target   注入目标路径，由「前缀 + 名称」构成，前缀决定注入位置：
     *                 {@code headers.<名称>}（如 {@code headers.Authorization}）写入请求头，
     *                 {@code attributes.<键>}（如 {@code attributes.traceId}）写入共享上下文属性。
     *                 不允许为 {@code null}（分流时直接调用 {@code target.startsWith(...)}，为 {@code null} 会 NPE）；
     *                 前缀既不是 {@code headers.} 也不是 {@code attributes.} 时该规则被静默跳过，不报错
     * @param callback 注入值提供者，每次远程调用前执行一次，返回要写入的字符串。
     *                 不允许为 {@code null}（过滤器直接调用 {@code rule.callback().apply(ctx)}，为 {@code null} 会 NPE）；
     *                 回调自身返回 {@code null} 是合法的，表示本次不注入（过滤器会跳过，不写入任何位置）。
     *                 回调可读取传入的 {@code InvocationContext}（请求头、返回值、属性）来动态取值，
     *                 典型场景是每次取新的令牌
     */
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

    /**
     * 获取Attribute
     * @param key 键，不允许为 null
     * @return T 对象
     */
    @SuppressWarnings("unchecked")
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

    /**
     * Clear
     */
    public void clear() {
        attributes.clear();
        defaultHeaders.clear();
        injectRules.clear();
    }
}