package com.chua.flow.support;

import com.chua.common.support.task.flow.ConditionNode;
import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.FlowDefinition;
import com.chua.common.support.task.flow.FlowException;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowNode;
import com.chua.common.support.task.flow.FlowStatus;
import com.chua.common.support.task.pipeline.core.Action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认流程实例实现。
 *
 * <p>一次流程运行的生命周期载体，内部持有唯一的 {@link DefaultFlowContext} 上下文。
 * 每次 {@link #run(Map)} 传入的运行参数合并写入同一上下文，
 * 挂起恢复同样复用该上下文，保证"运行必定同一个上下文"的编排约束。</p>
 *
 * <p>实例执行驱动：采用待执行队列模型，从起始节点开始按图连线调度节点。
 * 节点可产出一个或多个后续节点（条件节点按 {@link ConditionNode#test} 结果
 * 走 true/false 分支，vue-流 中一个 源处理 连多条边对应多目标串行执行），
 * 全部进入队列依次消费。节点可调用上下文 {@link FlowContext#waitForResume()} 挂起、
 * {@link FlowContext#exit()} 终止流程。</p>
 *
 * <p>实例状态流转：{@link FlowStatus#NEW} → {@link FlowStatus#RUNNING}
 * → {@link FlowStatus#WAITED}（节点挂起时）→ {@link FlowStatus#COMPLETED}；
 * 节点异常时进入 {@link FlowStatus#FAILED}，外部终止进入 {@link FlowStatus#TERMINATED}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultFlowInstance implements FlowInstance {

    /**
     * 上下文属性键：运行参数映射
     */
    private static final String PARAMS_ATTRIBUTE_KEY = "params";

    /**
     * 默认单节点最大执行次数上限
     */
    private static final int DEFAULT_MAX_LOOP_COUNT = 100;

    /**
     * 顺序边标签（空字符串表示默认顺序边）
     */
    private static final String DEFAULT_EDGE_LABEL = "";

    /**
     * 所属流程
     */
    private final DefaultFlow flow;

    /**
     * 起始节点 标识
     */
    private final String startNodeId;

    /**
     * 实例唯一标识
     */
    private final String instanceId;

    /**
     * 实例当前状态
     */
    private FlowStatus status;

    /**
     * 实例唯一执行上下文，首次运行时创建后不再重建
     */
    private DefaultFlowContext context;

    /**
     * 待执行节点队列，挂起时冻结，恢复后继续消费
     */
    private final Deque<String> pendingNodes = new ArrayDeque<>();

    /**
     * 创建实例时携带的初始参数
     */
    private final Map<String, Object> initialParams;

    /**
     * 单节点最大执行次数上限，运行 创建上下文时应用
     */
    private int maxLoopCount = DEFAULT_MAX_LOOP_COUNT;

    /**
     * 构造流程实例。
     *
     * @param flow          所属流程
     * @param startNodeId   起始节点 标识
     * @param initialParams 初始参数
     */
    public DefaultFlowInstance(DefaultFlow flow, String startNodeId,
                               Map<String, Object> initialParams) {
        this.flow = flow;
        this.startNodeId = startNodeId;
        this.instanceId = UUID.randomUUID().toString().replace("-", "");
        this.status = FlowStatus.NEW;
        this.context = null;
        this.initialParams = initialParams != null
                ? new LinkedHashMap<>(initialParams) : new LinkedHashMap<>();
    }

    /**
     * 设置单节点最大执行次数上限。
     *
     * <p>需在首次 run 前调用，run 创建上下文时应用该上限。
     * 上限值 <= 0 时恢复默认值。</p>
     *
     * @param maxLoopCount 执行次数上限
     * @return 当前实例
     */
    public FlowInstance maxLoopCount(int maxLoopCount) {
        this.maxLoopCount = maxLoopCount > 0 ? maxLoopCount : DEFAULT_MAX_LOOP_COUNT;
        return this;
    }

    @Override
    /**
     * 获取instanceid
    */
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    /**
     * 获取流标识
    */
    public String getFlowId() {
        return flow.getId();
    }

    @Override
    /**
     * 获取状态
    */
    public FlowStatus getStatus() {
        return status;
    }

    @Override
    /**
     * 运行
    */
    public FlowInstance run() {
        if (!initialParams.isEmpty()) {
            return run(initialParams);
        }
        return run(Collections.emptyMap());
    }

    @Override
    /**
     * 运行
    */
    public synchronized FlowInstance run(Map<String, Object> params) {
        checkRunnable();
        // 首次运行创建唯一上下文，并放入起始节点
        if (status == FlowStatus.NEW) {
            context = new DefaultFlowContext(flow.getId(), flow);
            context.setMaxLoopCount(maxLoopCount);
            pendingNodes.addLast(startNodeId);
            status = FlowStatus.RUNNING;
        }
        mergeParams(params);
        execute();
        return this;
    }

    @Override
    /**
     * 恢复
    */
    public synchronized FlowInstance resume() {
        if (status != FlowStatus.WAITED) {
            throw new IllegalStateException("实例未挂起，无法恢复: " + status);
        }
        status = FlowStatus.RUNNING;
        execute();
        return this;
    }

    @Override
    /**
     * Terminate
    */
    public synchronized void terminate() {
        if (status == FlowStatus.COMPLETED || status == FlowStatus.TERMINATED
                || status == FlowStatus.FAILED) {
            return;
        }
        pendingNodes.clear();
        status = FlowStatus.TERMINATED;
    }

    @Override
    /**
     * 是否Completed
    */
    public boolean isCompleted() {
        return status == FlowStatus.COMPLETED;
    }

    @Override
    /**
     * 获取上下文
    */
    public FlowContext getContext() {
        return context;
    }

    /**
     * 校验实例是否可运行。
     *
     * <p>已结束（完成/失败/终止）的实例不允许再次运行。</p>
     */
    private void checkRunnable() {
        if (status == FlowStatus.COMPLETED || status == FlowStatus.FAILED
                || status == FlowStatus.TERMINATED) {
            throw new IllegalStateException("实例已结束，无法继续运行: " + status);
        }
    }

    /**
     * 将运行参数合并写入上下文。
     *
     * <p>参数按键展开写入上下文属性，同时整体存入 {@value #PARAMS_ATTRIBUTE_KEY} 键，
     * 供节点按需读取。</p>
     *
     * @param params 运行参数
     */
    private void mergeParams(Map<String, Object> params) {
        if (params == null || context == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            context.setAttribute(entry.getKey(), entry.getValue());
        }
        context.setAttribute(PARAMS_ATTRIBUTE_KEY, new LinkedHashMap<>(params));
    }

    /**
     * 执行流程图。
     *
     * <p>从待执行队列依次取节点调度：条件节点按判断结果走分支，
     * 普通节点执行后按动作继续，产出后续节点全部入队。
     * 执行完成或遇到出口节点时进入完成状态，
     * 节点挂起时冻结队列进入挂起状态，异常时标记失败并向上抛出。
     * 每调度一个节点记录执行轨迹，达到单节点执行次数上限判定死循环终止。</p>
     */
    private void execute() {
        try {
            while (status == FlowStatus.RUNNING) {
                String currentNodeId = pendingNodes.pollFirst();
                if (currentNodeId == null) {
                    status = FlowStatus.COMPLETED;
                    return;
                }
                // 防死循环：记录执行并校验次数上限
                Object input = context.getData();
                if (context.isLoopLimitReached(currentNodeId)) {
                    context.recordExecution(currentNodeId, input, input);
                    throw new FlowException("检测到死循环，节点执行次数达到上限 "
                            + context.getMaxLoopCount() + ": " + currentNodeId);
                }
                context.setCurrentNodeId(currentNodeId);
                FlowNode node = flow.getNode(currentNodeId);
                if (node == null) {
                    throw new FlowException("节点未添加，无法执行: " + currentNodeId);
                }
                if (node instanceof ConditionNode conditionNode) {
                    // 条件节点无业务执行，输入输出一致，仅记录执行轨迹
                    context.recordExecution(currentNodeId, input, input);
                    boolean result = conditionNode.test(context);
                    enqueue(branchTargets(currentNodeId, result));
                    continue;
                }
                context.clearAction();
                node.execute(context);
                // 记录节点执行轨迹：输入为执行前数据，输出为执行后数据
                Object output = context.getData();
                context.recordExecution(currentNodeId, input, output);
                Action action = context.getAction();
                if (action == Action.EXIT) {
                    status = FlowStatus.COMPLETED;
                    return;
                }
                String explicit = context.getNextNodeId();
                context.clearNextNodeId();
                if (explicit != null) {
                    enqueue(Collections.singletonList(explicit));
                } else {
                    enqueue(orderTargets(currentNodeId));
                }
                if (action == Action.WAIT) {
                    // 记录挂起点与待执行快照，供恢复时查看
                    context.snapshotPending(new ArrayList<>(pendingNodes));
                    status = FlowStatus.WAITED;
                    return;
                }
            }
        } catch (Exception e) {
            status = FlowStatus.FAILED;
            throw e;
        }
    }

    /**
     * 将目标节点按顺序放入队列尾部。
     *
     * @param targets 目标节点 标识 列表
     */
    private void enqueue(List<String> targets) {
        for (String target : targets) {
            pendingNodes.addLast(target);
        }
    }

    /**
     * 查找条件节点按判断结果走向的全部目标节点。
     *
     * @param nodeId 条件节点 标识
     * @param result 判断结果
     * @return 目标节点 标识 列表，未配置分支时返回空列表
     */
    private List<String> branchTargets(String nodeId, boolean result) {
        String label = result ? "true" : "false";
        return targetsOf(nodeId, label);
    }

    /**
     * 查找普通节点的默认顺序边目标节点。
     *
     * @param nodeId 节点 标识
     * @return 目标节点 标识 列表，无顺序边时返回空列表
     */
    private List<String> orderTargets(String nodeId) {
        return targetsOf(nodeId, DEFAULT_EDGE_LABEL);
    }

    /**
     * 查找节点指定标签边对应的全部目标节点。
     *
     * @param nodeId 源节点 标识
     * @param label  边标签
     * @return 目标节点 标识 列表
     */
    private List<String> targetsOf(String nodeId, String label) {
        List<String> result = new ArrayList<>();
        for (FlowDefinition.FlowEdgeDef edge : flow.getDefinition().getEdges()) {
            if (edge.from().equals(nodeId) && label.equals(edge.label())) {
                result.add(edge.to());
            }
        }
        return result;
    }
}
