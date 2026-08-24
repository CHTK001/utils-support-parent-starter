package com.chua.common.support.task.taskrunner;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 运行上下文 — 贯穿一次 TaskRunner 执行的数据载体。
 *
 * <p>职责：</p>
 * <ul>
 *   <li><strong>结果存取</strong>：任务执行成功后写入返回值，下游任务按节点 ID 读取，
 *       是 {@code dependsNode} 数据依赖的基础</li>
 *   <li><strong>属性传递</strong>：跨节点的自定义共享数据（如请求头、追踪 ID）</li>
 *   <li><strong>输入承载</strong>：{@code execute(input)} 传入的初始输入</li>
 * </ul>
 *
 * <p>内部容器均为并发安全实现，可被多个虚拟线程同时读写。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RunnerContext {

    /**
     * 初始输入，可能为 null
     */
    private final Object input;

    /**
     * 节点结果存储：nodeId -> 返回值
     */
    private final ConcurrentMap<String, Object> results = new ConcurrentHashMap<>();

    /**
     * 自定义属性存储
     */
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 创建运行上下文。
     *
     * @param input 初始输入，允许为 null
     */
    public RunnerContext(Object input) {
        this.input = input;
    }

    /**
     * 获取初始输入。
     *
     * @param <T> 期望类型
     * @return 初始输入，可能为 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getInput() {
        return (T) input;
    }

    /**
     * 写入节点执行结果（由调度器调用，业务代码一般无需直接使用）。
     *
     * @param nodeId 节点 ID，不为 null
     * @param value  执行结果值
     */
    public void putResult(String nodeId, Object value) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        results.put(nodeId, value);
    }

    /**
     * 读取指定节点的执行结果原始值。
     *
     * @param nodeId 节点 ID，不为 null
     * @return 结果值；节点未执行成功时为 null
     */
    public Object get(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return results.get(nodeId);
    }

    /**
     * 按类型读取指定节点的执行结果。
     *
     * @param nodeId 节点 ID，不为 null
     * @param type   期望类型，不为 null
     * @param <T>    期望类型
     * @return 类型化结果；节点未执行成功时为 null
     * @throws IllegalStateException 当实际类型与期望不一致时
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String nodeId, Class<T> type) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        var value = results.get(nodeId);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException(
                    "节点 " + nodeId + " 结果类型不符: 期望 " + type.getName()
                            + " 实际 " + value.getClass().getName());
        }
        return (T) value;
    }

    /**
     * 判断指定节点是否存在有效结果（非 null）。
     *
     * <p>{@code dependsNode} 数据依赖即基于此判定：前置节点无有效结果时本节点判失败。</p>
     *
     * @param nodeId 节点 ID，不为 null
     * @return true 表示存在非 null 结果
     */
    public boolean hasResult(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        var value = results.get(nodeId);
        return value != null;
    }

    /**
     * 写入自定义属性。
     *
     * @param key   属性键，不为 null
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        attributes.put(key, value);
    }

    /**
     * 读取自定义属性。
     *
     * @param key 属性键，不为 null
     * @return 属性值，不存在时为 null
     */
    public Object getAttribute(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return attributes.get(key);
    }

    /**
     * 删除自定义属性。
     *
     * @param key 属性键，不为 null
     * @return 被删除的属性值，不存在时为 null
     */
    public Object removeAttribute(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return attributes.remove(key);
    }
}
