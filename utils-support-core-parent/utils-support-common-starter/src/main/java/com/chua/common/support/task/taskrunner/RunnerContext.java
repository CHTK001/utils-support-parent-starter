package com.chua.common.support.task.taskrunner;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 运行上下文 — 贯穿一次 任务runner 执行的数据载体。
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
     * 空 结果哨兵：允许任务成功但返回 空，同时保证 是否包含结果 语义正确
     */
    private static final Object NULL_VALUE = new Object();

    /**
     * 初始输入，可能为 空
     */
    private final Object input;

    /**
     * 节点结果存储：节点id -> 返回值（空 结果以哨兵存储）
     */
    private final ConcurrentMap<String, Object> results = new ConcurrentHashMap<>();

    /**
     * 自定义属性存储
     */
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 创建运行上下文。
     *
     * @param input 初始输入，允许为 空
     */
    public RunnerContext(Object input) {
        this.input = input;
    }

    /**
     * 获取初始输入。
     *
     * @param <T> 期望类型
     * @return 初始输入，可能为 空
     */
    @SuppressWarnings("unchecked")
    public <T> T getInput() {
        return (T) input;
    }

    /**
     * 写入节点执行结果（由调度器调用，业务代码一般无需直接使用）。
     *
     * @param nodeId 节点 标识，不为 空
     * @param value  执行结果值，允许为 空（以内部哨兵记录）
     */
    public void putResult(String nodeId, Object value) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        results.put(nodeId, value == null ? NULL_VALUE : value);
    }

    /**
     * 读取指定节点的执行结果原始值。
     *
     * @param nodeId 节点 标识，不为 空
     * @return 结果值；节点未执行或执行结果本身为 空 时返回 空
     */
    public Object get(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return unwrap(results.get(nodeId));
    }

    /**
     * 按类型读取指定节点的执行结果。
     *
     * @param nodeId 节点 标识，不为 空
     * @param type   期望类型，不为 空
     * @param <T>    期望类型
     * @return 类型化结果；节点未执行或结果为 空 时返回 空
     * @throws IllegalStateException 当实际类型与期望不一致时
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String nodeId, Class<T> type) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        var value = unwrap(results.get(nodeId));
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
     * 判断指定节点是否已有执行结果记录。
     *
     * <p>{@code dependsNode} 数据依赖基于此判定：前置节点执行成功即视为有结果，
     * 即使其返回值为 空。</p>
     *
     * @param nodeId 节点 标识，不为 空
     * @return true 表示该节点已成功执行并写入结果
     */
    public boolean hasResult(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        return results.containsKey(nodeId);
    }

    /**
     * 写入自定义属性。
     *
     * @param key   属性键，不为 空
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        attributes.put(key, value);
    }

    /**
     * 读取自定义属性。
     *
     * @param key 属性键，不为 空
     * @return 属性值，不存在时为 空
     */
    public Object getAttribute(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return attributes.get(key);
    }

    /**
     * 删除自定义属性。
     *
     * @param key 属性键，不为 空
     * @return 被删除的属性值，不存在时为 空
     */
    public Object removeAttribute(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return attributes.remove(key);
    }

    /**
     * 解包内部 空 哨兵。
     *
     * @param stored 存储值
     * @return 业务原始值
     */
    private static Object unwrap(Object stored) {
        return stored == NULL_VALUE ? null : stored;
    }
}
