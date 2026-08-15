package com.chua.common.support.task.pipeline.core;

import java.util.*;

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
 *   <li><strong>nextNodeIdInOrder</strong> — 按添加顺序的下一个节点 ID（只读，供节点判断用）</li>
 *   <li><strong>history</strong> — 已执行节点 ID 列表，支持追溯和重播</li>
 *   <li><strong>action</strong> — 当前动作，控制引擎下一步行为</li>
 *   <li><strong>attributes</strong> — 扩展属性 Map，节点间共享自定义数据</li>
 *   <li><strong>nodeLocalData</strong> — 当前节点本地数据，节点间隔离，每进入新节点时清空</li>
 *   <li><strong>lastError</strong> — 最近一次节点执行异常，供错误恢复节点判断</li>
 * </ul>
 *
 * @param <T> 数据类型
 * @author CH
 */
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
     * 按添加顺序的下一个节点 ID（只读，由引擎注入，供节点判断逻辑使用）
     */
    private String nextNodeIdInOrder;

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
     * 当前节点本地数据，节点间隔离。
     * <p>每进入新节点时由引擎清空，仅供当前节点内部使用，
     * 不会传递到下一个节点（跨节点共享数据请用 attributes）。</p>
     */
    private Map<String, Object> nodeLocalData;

    /**
     * 最近一次节点执行异常。
     * <p>当节点执行抛出异常时，引擎将异常存入此字段，供错误恢复节点判断。
     * 调用 {@link #clearLastError()} 可清除此字段。</p>
     */
    private Throwable lastError;

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

    /**
     * 获取按添加顺序的下一个节点 ID。
     *
     * <p>此值为只读，由引擎在节点执行前注入，供节点判断逻辑使用。
     * 与 {@link #getNextNodeId()} 不同，本方法返回的是流水线定义时的顺序下一节点，
     * 而 {@code getNextNodeId()} 返回的是实际将要执行的下一节点（可能被修改）。</p>
     *
     * @return 按顺序的下一个节点 ID，若当前为末尾节点则返回 null
     */
    public String getNextNodeIdInOrder() {
        return nextNodeIdInOrder;
    }

    /**
     * 设置按添加顺序的下一个节点 ID（由引擎内部调用）。
     *
     * @param nextNodeIdInOrder 按顺序的下一个节点 ID
     */
    public void setNextNodeIdInOrder(String nextNodeIdInOrder) {
        this.nextNodeIdInOrder = nextNodeIdInOrder;
    }

    /**
     * 获取当前节点本地数据。
     *
     * <p>节点本地数据与 {@link #getAttributes()} 的区别：</p>
     * <ul>
     *   <li><strong>nodeLocalData</strong> — 节点间隔离，每进入新节点时由引擎清空，仅供当前节点内部使用</li>
     *   <li><strong>attributes</strong> — 节点间共享，整个流水线生命周期内持续存在</li>
     * </ul>
     *
     * @return 当前节点本地数据 Map，首次访问时自动创建
     */
    public Map<String, Object> getNodeLocalData() {
        if (nodeLocalData == null) {
            nodeLocalData = new LinkedHashMap<>();
        }
        return nodeLocalData;
    }

    /**
     * 设置当前节点本地数据。
     *
     * @param nodeLocalData 节点本地数据 Map
     */
    public void setNodeLocalData(Map<String, Object> nodeLocalData) {
        this.nodeLocalData = nodeLocalData;
    }

    /**
     * 获取节点本地数据中的值。
     *
     * @param key 数据键
     * @param <V> 数据值类型
     * @return 数据值，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <V> V getNodeLocalValue(String key) {
        return (V) getNodeLocalData().get(key);
    }

    /**
     * 设置节点本地数据中的值。
     *
     * @param key   数据键
     * @param value 数据值
     */
    public void setNodeLocalValue(String key, Object value) {
        getNodeLocalData().put(key, value);
    }

    /**
     * 清空节点本地数据（由引擎在进入新节点时调用）。
     */
    public void clearNodeLocalData() {
        if (nodeLocalData != null) {
            nodeLocalData.clear();
        }
    }

    // ==================== 异常上下文 ====================

    /**
     * 获取最近一次节点执行异常。
     *
     * <p>当节点执行抛出异常时，引擎将异常存入此字段。
     * 错误恢复节点可通过此方法获取异常信息做条件判断。</p>
     *
     * @return 最近一次异常，无异常时返回 null
     */
    public Throwable getLastError() {
        return lastError;
    }

    /**
     * 设置最近一次节点执行异常（由引擎内部调用）。
     *
     * @param lastError 异常信息
     */
    public void setLastError(Throwable lastError) {
        this.lastError = lastError;
    }

    /**
     * 获取最近一次异常的错误码。
     *
     * <p>如果最近异常为 {@link PipelineException}，返回其简短错误码；
     * 否则返回异常类名的简化形式。</p>
     *
     * @return 错误码字符串，无异常时返回 null
     */
    public String getLastErrorCode() {
        if (lastError == null) {
            return null;
        }
        if (lastError instanceof com.chua.common.support.task.pipeline.exception.PipelineException) {
            return "PIPELINE_" + lastError.getMessage().replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase();
        }
        String className = lastError.getClass().getSimpleName();
        // 移除常见后缀：Exception, Error
        if (className.endsWith("Exception")) {
            className = className.substring(0, className.length() - 9);
        } else if (className.endsWith("Error")) {
            className = className.substring(0, className.length() - 5);
        }
        return className.toUpperCase();
    }

    /**
     * 清除最近一次节点执行异常。
     *
     * <p>错误恢复节点处理完异常后可调用此方法清除异常标记，
     * 表示错误已被处理，后续节点不再需要感知此异常。</p>
     */
    public void clearLastError() {
        this.lastError = null;
    }

    // ==================== 并行分支上下文 ====================

    /**
     * 创建并行分支上下文 — 共享数据，隔离控制状态。
     *
     * <p>为并行节点（{@link com.chua.common.support.task.pipeline.node.ParallelNode}）创建分支上下文，
     * 分支间通过引用共享数据，但各自维护独立的执行状态：</p>
     *
     * <p><strong>共享（引用传递）：</strong></p>
     * <ul>
     *   <li>{@code originalData} — 原始输入数据（只读）</li>
     *   <li>{@code currentData} — 当前数据（同一对象引用，修改对象本身对所有分支可见）</li>
     *   <li>{@code attributes} — 扩展属性 Map（同一 Map 引用，所有分支读写同一 Map）</li>
     * </ul>
     *
     * <p><strong>隔离（各自独立）：</strong></p>
     * <ul>
     *   <li>{@code currentNodeId} / {@code nextNodeId} — 当前/下一节点 ID</li>
     *   <li>{@code action} — 执行动作</li>
     *   <li>{@code history} — 执行历史</li>
     *   <li>{@code nodeLocalData} — 节点本地数据</li>
     *   <li>{@code lastError} — 最近异常</li>
     * </ul>
     *
     * <p><strong>线程安全提示：</strong></p>
     * <p>并行分支共享 {@code attributes} Map 和 {@code currentData} 对象引用。
     * 如果多个分支同时写入共享数据，建议使用线程安全的数据结构（如 ConcurrentHashMap 的值、
     * AtomicReference 等），或通过 {@code attributes} 的不同 key 避免写入冲突。</p>
     *
     * @return 新的分支上下文，共享数据但控制状态独立
     */
    public PipelineContext<T> createBranchContext() {
        PipelineContext<T> branch = new PipelineContext<>(this.pipelineId, this.originalData);
        branch.currentData = this.currentData;
        branch.attributes = this.attributes;
        return branch;
    }
}
