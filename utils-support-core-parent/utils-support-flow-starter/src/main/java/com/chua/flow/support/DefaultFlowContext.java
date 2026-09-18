package com.chua.flow.support;

import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.FlowProps;
import com.chua.common.support.task.flow.FlowTrace;
import com.chua.common.support.task.pipeline.core.Action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 默认流程运行上下文实现。
*
* <p>一次流程运行期间节点间共享的数据载体，持有当前数据、属性映射与流转状态。
* 上下文由流程实例持有，同一实例的多次运行复用同一上下文，
* 保证"运行必定同一个上下文"的编排约束。</p>
*
* <p>上下文与底层执行器通过 {@link Action} 动作标记交互：
* 节点调用 {@link #waitForResume()} 设置 {@link Action#WAIT}、
* {@link #exit()} 设置 {@link Action#EXIT}，执行器据此决定实例走向。</p>
*
* <p>上下文同时记录执行轨迹与节点执行次数，供回放、审计与防死循环使用：
* 单节点执行次数达到 {@link #getMaxLoopCount()} 时，执行器判定死循环并终止流程。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DefaultFlowContext implements FlowContext {

    /**
    * 默认单节点最大执行次数上限
    */
    private static final int DEFAULT_MAX_LOOP_COUNT = 100;

    /**
    * 所属流程 标识
    */
    private final String flowId;

    /**
    * 所属流程，用于读取节点配置属性
    */
    private final DefaultFlow flow;

    /**
    * 当前正在执行的节点 标识
    */
    private String currentNodeId;

    /**
    * 当前处理数据
    */
    private Object data;

    /**
    * 上下文属性映射，保持插入顺序
    */
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    /**
    * 手动指定的下一节点 标识
    */
    private String nextNodeId;

    /**
    * 当前流转动作
    */
    private Action action = Action.NEXT;

    /**
    * 执行轨迹：按序记录全部已执行节点 标识
    */
    private final List<String> executionTrace = new ArrayList<>();

    /**
    * 执行轨迹记录：按序记录每个节点的输入输出快照
    */
    private final List<FlowTrace> traces = new ArrayList<>();

    /**
    * 节点 标识 到累计执行次数的映射
    */
    private final Map<String, Integer> executeCounts = new LinkedHashMap<>();

    /**
    * 单节点最大执行次数上限
    */
    private int maxLoopCount = DEFAULT_MAX_LOOP_COUNT;

    /**
    * 挂起时待执行的后续节点快照
    */
    private final List<String> pendingSnapshot = new ArrayList<>();

    /**
    * 构造流程运行上下文。
    *
    * @param flowId 流程 标识
    * @param flow   所属流程
    */
    public DefaultFlowContext(String flowId, DefaultFlow flow) {
        this.flowId = flowId;
        this.flow = flow;
    }

    @Override
    /** 获取流标识 */
    public String getFlowId() {
        return flowId;
    }

    @Override
    /** 获取当前节点标识 */
    public String getCurrentNodeId() {
        return currentNodeId;
    }

    @Override
    /** 设置当前节点标识 */
    public void setCurrentNodeId(String nodeId) {
        this.currentNodeId = nodeId;
    }

    @Override
    /** 获取数据 */
    public Object getData() {
        return data;
    }

    @Override
    /** 设置数据 */
    public void setData(Object data) {
        this.data = data;
    }

    @Override
    /** 获取Attributes */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    /** 设置Attribute */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * 获取Attribute
    *
    * @param key 键
    * @return 获取attribute的结果
    */
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    @Override
    /** 当前节点props */
    public FlowProps currentNodeProps() {
        return flow.nodeProps(currentNodeId);
    }

    @Override
    /** 获取下一个节点标识 */
    public String getNextNodeId() {
        return nextNodeId;
    }

    @Override
    /** 设置下一个节点标识 */
    public void setNextNodeId(String nodeId) {
        this.nextNodeId = nodeId;
    }

    @Override
    /** waitfor恢复 */
    public void waitForResume() {
        this.action = Action.WAIT;
    }

    @Override
    /** Exit */
    public void exit() {
        this.action = Action.EXIT;
    }

    @Override
    /** 获取执行追踪 */
    public List<String> getExecutionTrace() {
        return new ArrayList<>(executionTrace);
    }

    @Override
    /** 获取追踪 */
    public List<FlowTrace> getTraces() {
        return new ArrayList<>(traces);
    }

    @Override
    /** 获取执行计算数量 */
    public int getExecuteCount(String nodeId) {
        return executeCounts.getOrDefault(nodeId, 0);
    }

    @Override
    /** 获取最大值循环计算数量 */
    public int getMaxLoopCount() {
        return maxLoopCount;
    }

    @Override
    /** 设置最大值循环计算数量 */
    public void setMaxLoopCount(int maxLoopCount) {
        if (maxLoopCount <= 0) {
            throw new IllegalArgumentException("maxLoopCount 必须大于 0");
        }
        this.maxLoopCount = maxLoopCount;
    }

    @Override
    /** 获取pending节点标识 */
    public List<String> getPendingNodeIds() {
        return new ArrayList<>(pendingSnapshot);
    }

    /**
    * 获取当前流转动作。
    *
    * @return 流转动作
    */
    public Action getAction() {
        return action;
    }

    /**
    * 清除流转动作，恢复为默认继续执行。
    */
    public void clearAction() {
        this.action = Action.NEXT;
    }

    /**
    * 清除手动指定的下一节点 标识。
    */
    public void clearNextNodeId() {
        this.nextNodeId = null;
    }

    /**
    * 记录节点执行一次。
    *
    * <p>追加执行轨迹并递增执行计数，供回放与防死循环判断。
    * 输入为执行前当前数据，输出为执行后当前数据。</p>
    *
    * @param nodeId 节点 标识
    * @param input  节点执行前当前数据
    * @param output 节点执行后当前数据
    */
    public void recordExecution(String nodeId, Object input, Object output) {
        executionTrace.add(nodeId);
        executeCounts.merge(nodeId, 1, Integer::sum);
        traces.add(FlowTrace.of(nodeId, input, output));
    }

    /**
    * 判断指定节点是否已达执行次数上限。
    *
    * <p>达到上限判定为死循环，执行器据此终止流程。</p>
    *
    * @param nodeId 节点 标识
    * @return 已达上限返回 true，否则返回 false
    */
    public boolean isLoopLimitReached(String nodeId) {
        return getExecuteCount(nodeId) >= maxLoopCount;
    }

    /**
    * 记录挂起时待执行的后续节点快照。
    *
    * <p>供暂停/恢复时查看当前节点之后的待执行目标。</p>
    *
    * @param pendingNodeIds 待执行节点 标识 列表
    */
    public void snapshotPending(List<String> pendingNodeIds) {
        pendingSnapshot.clear();
        pendingSnapshot.addAll(pendingNodeIds);
    }
}
