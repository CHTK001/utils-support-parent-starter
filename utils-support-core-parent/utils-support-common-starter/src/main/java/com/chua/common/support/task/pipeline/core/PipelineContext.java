package com.chua.common.support.task.pipeline.core;

import java.util.*;
import org.jspecify.annotations.NullUnmarked;

/**
 * 流水线上下文。
 *
 * <p>贯穿整个流水线执行过程的数据载体，在每个节点间传递。
 * 包含原始输入数据、当前处理数据、执行历史、控制动作等核心信息。</p>
 *
 * <p><strong>核心属性说明：</strong></p>
 * <ul>
 *   <li><strong>originalData</strong> — 流水线启动时的原始输入数据，不可变</li>
 *   <li><strong>currentData</strong> — 当前处理后的数据，节点间可传递修改</li>
 *   <li><strong>currentNodeId</strong> — 当前正在执行的节点 ID</li>
 *   <li><strong>nextNodeId</strong> — 下一个将要执行的节点 ID，节点可修改此值改变流程</li>
 *   <li><strong>history</strong> — 已执行节点 ID 列表，支持追溯和重播</li>
 *   <li><strong>action</strong> — 当前动作，控制引擎下一步行为</li>
 *   <li><strong>attributes</strong> — 扩展属性 Map，节点间共享自定义数据</li>
 * </ul>
 *
 * @param <T> 数据类型
 * @author CH
 */
@NullUnmarked
public class PipelineContext<T> {

    /**
     * 原始输入数据，流水线启动时传入，不可变
     */
    private final T originalData;

    /**
     * 当前处理后的数据，节点间可传递修改
     */
    private T currentData;

    /**
     * 流水线唯一标识
     */
    private final String pipelineId;

    /**
     * 当前正在执行的节点 ID
     */
    private String currentNodeId;

    /**
     * 下一个将要执行的节点 ID
     */
    private String nextNodeId;

    /**
     * 已执行节点 ID 历史列表，按执行顺序排列
     */
    private final List<String> history;

    /**
     * 当前动作，控制引擎下一步行为，默认为 NEXT
     */
    private Action action;

    /**
     * 扩展属性 Map，用于节点间共享自定义数据
     */
    private final Map<String, Object> attributes;

    /**
     * 构造流水线上下文。
     *
     * @param pipelineId   流水线 ID
     * @param originalData 原始输入数据
     */
    public PipelineContext(String pipelineId, T originalData) {
        this.pipelineId = pipelineId;
        this.originalData = originalData;
        this.currentData = originalData;
        this.history = new ArrayList<>();
        this.action = Action.NEXT;
        this.attributes = new LinkedHashMap<>();
    }

    /**
     * 获取原始输入数据。
     *
     * @return 原始输入数据，不可变
     */
    public T getOriginalData() {
        return originalData;
    }

    /**
     * 获取当前处理后的数据。
     *
     * @return 当前数据
     */
    public T getCurrentData() {
        return currentData;
    }

    /**
     * 设置当前处理后的数据。
     *
     * @param currentData 当前数据
     */
    public void setCurrentData(T currentData) {
        this.currentData = currentData;
    }

    /**
     * 获取流水线 ID。
     *
     * @return 流水线 ID
     */
    public String getPipelineId() {
        return pipelineId;
    }

    /**
     * 获取当前正在执行的节点 ID。
     *
     * @return 当前节点 ID
     */
    public String getCurrentNodeId() {
        return currentNodeId;
    }

    /**
     * 设置当前正在执行的节点 ID。
     *
     * @param currentNodeId 当前节点 ID
     */
    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    /**
     * 获取下一个将要执行的节点 ID。
     *
     * @return 下一节点 ID
     */
    public String getNextNodeId() {
        return nextNodeId;
    }

    /**
     * 设置下一个将要执行的节点 ID。
     *
     * <p>节点可通过此方法改变默认执行顺序，配合 {@link Action#JUMP} 使用可实现跳转。</p>
     *
     * @param nextNodeId 下一节点 ID
     */
    public void setNextNodeId(String nextNodeId) {
        this.nextNodeId = nextNodeId;
    }

    /**
     * 获取已执行节点 ID 历史列表。
     *
     * @return 历史节点 ID 列表，按执行顺序排列
     */
    public List<String> getHistory() {
        return history;
    }

    /**
     * 添加节点到执行历史。
     *
     * @param nodeId 已执行的节点 ID
     */
    public void addHistory(String nodeId) {
        this.history.add(nodeId);
    }

    /**
     * 获取当前动作。
     *
     * @return 当前动作
     */
    public Action getAction() {
        return action;
    }

    /**
     * 设置当前动作。
     *
     * <p>节点通过此方法控制引擎的下一步行为：</p>
     * <ul>
     *   <li>{@link Action#EXIT} — 终止流水线</li>
     *   <li>{@link Action#REPLAY} — 重播当前节点</li>
     *   <li>{@link Action#PREV} — 回退到上一节点</li>
     *   <li>{@link Action#JUMP} — 跳转到指定节点</li>
     *   <li>{@link Action#WAIT} — 挂起等待恢复</li>
     * </ul>
     *
     * @param action 动作枚举
     */
    public void setAction(Action action) {
        this.action = action;
    }

    /**
     * 获取扩展属性 Map。
     *
     * @return 扩展属性 Map
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * 设置扩展属性。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    /**
     * 获取扩展属性。
     *
     * @param key 属性键
     * @param <V> 属性值类型
     * @return 属性值，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <V> V getAttribute(String key) {
        return (V) attributes.get(key);
    }
}
