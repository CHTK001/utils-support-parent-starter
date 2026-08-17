package com.chua.common.support.task.pipeline.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li><strong>nodeOutputs</strong> — 节点输出数据 Map，引擎自动存储每个节点的输出，方便跨节点访问</li>
 *   <li><strong>nodeLocalData</strong> — 当前节点本地数据，节点间隔离，每进入新节点时清空</li>
 *   <li><strong>lastError</strong> — 最近一次节点执行异常，供错误恢复节点判断</li>
 * </ul>
 *
 * @param <T> 数据类型
 * @author CH
 * @since 4.0.0.42
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
     * 扩展属性 Map，用于节点间共享自定义数据。
     *
     * <p>在并行分支/异步并行子流程中，此 Map 通过引用共享，所有分支读写同一 Map。
     * 使用 ConcurrentHashMap 保证跨线程读写的线程安全（ParallelNode 的主干与异步子流程并发执行）。
     * 注意：复合操作（如先读后写同一 key）仍由调用方自行保证原子性。</p>
     */
    private Map<String, Object> attributes;

    /**
     * 节点输出数据 Map，引擎自动存储每个节点的输出数据。
     *
     * <p>每个节点执行完毕后，引擎自动将节点输出存入此 Map，key 为节点 ID。
     * 后续任意节点可通过 {@link #getNodeOutput(String)} 或 {@link #getData(String)} 获取之前节点的输出。</p>
     *
     * <p><strong>存储格式（统一为 nodeId 作为 key）：</strong></p>
     * <ul>
     *   <li><strong>普通节点</strong>（TaskNode/DecisionNode/StartNode/EndNode）：
     *       {@code nodeOutputs["task1"] = data}，值为节点的 currentData</li>
     *   <li><strong>分叉节点</strong>（ForkNode）：
     *       {@code nodeOutputs["fork1"] = ForkResult}，值为 {@link ForkResult} 结构化对象，
     *       内含各分支输出数据和历史</li>
     *   <li><strong>子流水线节点</strong>（SubPipelineNode）：
     *       {@code nodeOutputs["subStep"] = SubPipelineResult}，值为 {@link SubPipelineResult} 结构化对象，
     *       内含子流程输出数据和历史</li>
     *   <li><strong>并行节点</strong>（ParallelNode）：
     *       {@code nodeOutputs["parallelStep"] = AsyncResult}，值为 {@link AsyncResult} 结构化对象，
     *       内含异步执行结果句柄（完成后输出与历史可用）</li>
     * </ul>
     *
     * <p><strong>与 attributes 的区别：</strong></p>
     * <ul>
     *   <li><strong>nodeOutputs</strong> — 引擎自动管理，key 为节点ID，存储节点输出数据</li>
     *   <li><strong>attributes</strong> — 用户手动管理，key 自定义，存储任意共享数据</li>
     * </ul>
     */
    private Map<String, Object> nodeOutputs;

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
        // attributes 使用 ConcurrentHashMap：ParallelNode 等异步节点跨线程共享，需保证读写线程安全
        this.attributes = new ConcurrentHashMap<>();
        this.nodeOutputs = new ConcurrentHashMap<>();
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
     * <p>attributes 为 ConcurrentHashMap，不允许 null 值；
     * 传入 null 时忽略（与 {@link #setNodeOutput(String, Object)} 的 null 守卫行为一致）。
     * 如需清除属性，请使用 {@code getAttributes().remove(key)}。</p>
     *
     * @param key   属性键
     * @param value 属性值，null 时忽略
     */
    public void setAttribute(String key, Object value) {
        if (value != null) {
            this.attributes.put(key, value);
        }
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

    // ==================== 节点输出数据 ====================

    /**
     * 获取节点输出数据 Map。
     *
     * <p>每个节点执行完毕后，引擎自动将节点输出存入此 Map，key 为节点 ID。</p>
     *
     * <p><strong>存储格式：</strong></p>
     * <ul>
     *   <li>普通节点：值为 currentData</li>
     *   <li>分叉节点：值为 {@link ForkResult}（包含各分支输出）</li>
     *   <li>子流水线节点：值为 {@link SubPipelineResult}（包含子流程输出和历史）</li>
     *   <li>并行节点：值为 {@link AsyncResult}（异步执行结果句柄）</li>
     * </ul>
     *
     * <p><strong>存储契约：</strong>引擎自动写入遵循
     * {@link com.chua.common.support.task.pipeline.builder.DefaultPipeline} 类级 Javadoc 定义的
     * <strong>节点输出存储契约</strong>：{@code currentData} 为 null 时跳过存储；
     * 节点已自存结构化结果（{@code AsyncResult}/{@code ForkResult}/{@code SubPipelineResult}）时不覆盖。</p>
     *
     * @return 节点输出数据 Map（ConcurrentHashMap，线程安全）
     * @see com.chua.common.support.task.pipeline.builder.DefaultPipeline
     */
    public Map<String, Object> getNodeOutputs() {
        return nodeOutputs;
    }

    /**
     * 存储节点输出数据（由引擎自动调用）。
     *
     * <p>节点执行完毕后，引擎调用此方法将节点的 {@link #getCurrentData()} 存入 nodeOutputs，
     * 写入遵循 {@link com.chua.common.support.task.pipeline.builder.DefaultPipeline} 类级 Javadoc 定义的
     * <strong>节点输出存储契约</strong>（null 输出跳过、节点自存结构化结果不覆盖）。
     * 用户也可手动调用此方法存储自定义数据。</p>
     *
     * <p><strong>注意：</strong>本方法自身仅忽略 null 值，不执行引擎调用前的"不覆盖"检查；
     * 手动调用写入已存在的 key 时会覆盖原值（包括已存储的结构化结果）。</p>
     *
     * @param nodeId 节点 ID
     * @param data   节点输出数据，null 时忽略（nodeOutputs 为 ConcurrentHashMap，不允许 null 值）
     * @see com.chua.common.support.task.pipeline.builder.DefaultPipeline
     */
    public void setNodeOutput(String nodeId, Object data) {
        if (data != null) {
            this.nodeOutputs.put(nodeId, data);
        }
    }

    /**
     * 获取指定节点的输出数据。
     *
     * <p>通过节点 ID 获取之前节点的输出数据，方便跨节点访问。</p>
     *
     * <p><strong>返回值类型：</strong></p>
     * <ul>
     *   <li>普通节点：返回 currentData</li>
     *   <li>分叉节点：返回 {@link ForkResult}，可通过 {@code getData(nodeId, ForkResult.class).getBranch("branchA")} 获取分支数据</li>
     *   <li>子流水线节点：返回 {@link SubPipelineResult}，可通过 {@code getData(nodeId, SubPipelineResult.class).getOutput()} 获取子流程输出</li>
     *   <li>并行节点：返回 {@link AsyncResult}，可通过 {@code getData(nodeId, AsyncResult.class).await()} 阻塞等待完成</li>
     * </ul>
     *
     * @param nodeId 节点 ID
     * @param <V>    数据值类型
     * @return 节点输出数据，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <V> V getNodeOutput(String nodeId) {
        return (V) nodeOutputs.get(nodeId);
    }

    /**
     * 获取指定节点的输出数据（带类型转换）。
     *
     * <p>与 {@link #getNodeOutput(String)} 类似，但支持指定目标类型。
     * 如果节点输出数据不是指定类型，将抛出 ClassCastException。</p>
     *
     * @param nodeId 节点 ID
     * @param type   期望的数据类型
     * @param <V>    数据值类型
     * @return 节点输出数据，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public <V> V getNodeOutput(String nodeId, Class<V> type) {
        Object value = nodeOutputs.get(nodeId);
        return value != null ? (V) type.cast(value) : null;
    }

    /**
     * 获取指定节点的输出数据（便捷方法）。
     *
     * <p>等价于 {@link #getNodeOutput(String)}，提供更简洁的调用方式。</p>
     *
     * <p>用法示例：</p>
     * <pre>{@code
     * // 等价于 ctx.getNodeOutput("validate")
     * Object data = ctx.getData("validate");
     *
     * // 带类型转换
     * String result = ctx.getData("validate", String.class);
     * }</pre>
     *
     * @param taskId 节点 ID
     * @param <V>    数据值类型
     * @return 节点输出数据，不存在时返回 null
     */
    public <V> V getData(String taskId) {
        return getNodeOutput(taskId);
    }

    /**
     * 获取指定节点的输出数据（便捷方法，带类型转换）。
     *
     * <p>等价于 {@link #getNodeOutput(String, Class)}，提供更简洁的调用方式。</p>
     *
     * @param taskId 节点 ID
     * @param type   期望的数据类型
     * @param <V>    数据值类型
     * @return 节点输出数据，不存在时返回 null
     */
    public <V> V getData(String taskId, Class<V> type) {
        return getNodeOutput(taskId, type);
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
     * 创建并行分支上下文 — 共享只读数据和输出，隔离当前数据和控制状态。
     *
     * <p>为分叉节点（{@link com.chua.common.support.task.pipeline.node.ForkNode}）创建分支上下文。
     * 各分支独立修改 {@code currentData}，避免并发写入冲突；
     * 共享 {@code nodeOutputs} 和 {@code attributes}，方便跨分支/跨节点数据访问。</p>
     *
     * <p><strong>共享（引用传递）：</strong></p>
     * <ul>
     *   <li>{@code originalData} — 原始输入数据（只读）</li>
     *   <li>{@code attributes} — 扩展属性 Map（同一 Map 引用，所有分支读写同一 Map）</li>
     *   <li>{@code nodeOutputs} — 节点输出数据 Map（同一 ConcurrentHashMap 引用，各分支写入不同 key）</li>
     * </ul>
     *
     * <p><strong>隔离（各自独立）：</strong></p>
     * <ul>
     *   <li>{@code currentData} — 当前数据（独立副本，各分支修改互不影响，避免并发冲突）</li>
     *   <li>{@code currentNodeId} / {@code nextNodeId} — 当前/下一节点 ID</li>
     *   <li>{@code action} — 执行动作</li>
     *   <li>{@code history} — 执行历史</li>
     *   <li>{@code nodeLocalData} — 节点本地数据</li>
     *   <li>{@code lastError} — 最近异常</li>
     * </ul>
     *
     * <p><strong>并发安全说明：</strong></p>
     * <p>各分支的 {@code currentData} 完全独立，不存在并发写入冲突。
     * {@code nodeOutputs} 与 {@code attributes} 均为 ConcurrentHashMap，跨线程读写安全；
     * 但复合操作（如先读后写同一 key）的原子性需调用方自行保证。</p>
     *
     * @return 新的分支上下文，共享只读数据和输出，但当前数据和控制状态独立
     */
    public PipelineContext<T> createBranchContext() {
        PipelineContext<T> branch = new PipelineContext<>(this.pipelineId, this.originalData);
        // currentData 不共享引用 — 各分支独立修改，避免并发冲突
        branch.currentData = this.currentData;
        // attributes 共享引用 — 跨分支共享自定义数据
        branch.attributes = this.attributes;
        // nodeOutputs 共享引用 — 各分支通过不同 key 写入，ConcurrentHashMap 保证线程安全
        branch.nodeOutputs = this.nodeOutputs;
        return branch;
    }

    /**
     * 创建子流水线/并行分支上下文 — 共享属性与输出，隔离当前数据。
     *
     * <p>为子流水线节点（{@link com.chua.common.support.task.pipeline.node.SubPipelineNode}）和
     * 并行节点（{@link com.chua.common.support.task.pipeline.node.ParallelNode}）创建分支上下文，
     * 行为与 {@link #createBranchContext()} 一致：{@code attributes} 与 {@code nodeOutputs}
     * 共享引用，子流程写入的属性和节点输出对父流程可见；
     * {@code currentData} / {@code originalData} 为子流程的输入数据（独立副本）。</p>
     *
     * <p><strong>嵌套结构化并发：</strong>共享 {@code attributes} 意味着子流水线能感知父 Pipeline 的
     * {@code StructuredTaskScope}（{@code __pipelineScope__}），由 {@code DefaultPipeline.executeWith()}
     * 的嵌套场景分支直接复用父 scope 运行，与 {@code ForkNode} 分支行为一致。
     * {@code attributes} 为 ConcurrentHashMap，支持异步并行场景下主干与子流程的跨线程读写。</p>
     *
     * @param branchPipelineId 子流水线 ID（用于日志与结果结构化存储）
     * @param branchData       子流水线的输入数据（作为 originalData 与初始 currentData）
     * @param <U>              子流水线数据类型
     * @return 新的子流水线上下文，共享 attributes 与 nodeOutputs
     */
    public <U> PipelineContext<U> createBranchContext(String branchPipelineId, U branchData) {
        PipelineContext<U> branch = new PipelineContext<>(branchPipelineId, branchData);
        // attributes 共享引用 — 子流程与父流程读写同一 Map
        branch.attributes = this.attributes;
        // nodeOutputs 共享引用 — 子流程节点输出对父流程可见（ConcurrentHashMap 保证线程安全）
        branch.nodeOutputs = this.nodeOutputs;
        return branch;
    }
}
