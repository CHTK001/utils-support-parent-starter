package com.chua.flow.support;

import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowProps;
import com.chua.common.support.task.flow.FlowStatus;
import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 默认流程实例实现。
 *
 * <p>一次流程运行的生命周期载体，内部持有唯一的 {@link PipelineContext} 上下文。
 * 每次 {@link #run(Map)} 传入的运行参数合并写入同一上下文，
 * 挂起恢复同样复用该上下文，保证"运行必定同一个上下文"的编排约束。</p>
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
     * 所属流程
     */
    private final DefaultFlow flow;

    /**
     * 可执行流水线
     */
    private final Pipeline pipeline;

    /**
     * 起始节点 ID
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
    private PipelineContext<Object> context;

    /**
     * 创建实例时携带的初始参数
     */
    private final Map<String, Object> initialParams;

    /**
     * 构造流程实例。
     *
     * @param flow          所属流程
     * @param pipeline      可执行流水线
     * @param startNodeId   起始节点 ID
     * @param initialParams 初始参数
     */
    public DefaultFlowInstance(DefaultFlow flow, Pipeline pipeline, String startNodeId,
                               Map<String, Object> initialParams) {
        this.flow = flow;
        this.pipeline = pipeline;
        this.startNodeId = startNodeId;
        this.instanceId = UUID.randomUUID().toString().replace("-", "");
        this.status = FlowStatus.NEW;
        this.context = null;
        this.initialParams = initialParams != null
                ? new LinkedHashMap<>(initialParams) : new LinkedHashMap<>();
    }

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public String getFlowId() {
        return flow.getId();
    }

    @Override
    public FlowStatus getStatus() {
        return status;
    }

    @Override
    public FlowInstance run() {
        if (!initialParams.isEmpty()) {
            return run(initialParams);
        }
        return run(Collections.emptyMap());
    }

    @Override
    public synchronized FlowInstance run(Map<String, Object> params) {
        checkRunnable();
        // 首次运行创建唯一上下文，并设置起始节点与实例引用
        if (status == FlowStatus.NEW) {
            context = new PipelineContext<>(flow.getId(), null);
            context.setNextNodeId(startNodeId);
            context.setAttribute(DefaultFlow.INSTANCE_ATTRIBUTE_KEY, this);
            status = FlowStatus.RUNNING;
        }
        mergeParams(params);
        execute();
        return this;
    }

    @Override
    public synchronized FlowInstance resume() {
        if (status != FlowStatus.WAITED) {
            throw new IllegalStateException("实例未挂起，无法恢复: " + status);
        }
        execute();
        return this;
    }

    @Override
    public synchronized void terminate() {
        if (status == FlowStatus.COMPLETED || status == FlowStatus.TERMINATED
                || status == FlowStatus.FAILED) {
            return;
        }
        if (context != null) {
            context.setAction(Action.EXIT);
        }
        status = FlowStatus.TERMINATED;
    }

    @Override
    public boolean isCompleted() {
        return status == FlowStatus.COMPLETED;
    }

    @Override
    public FlowProps currentNodeProps() {
        if (context == null) {
            return FlowProps.EMPTY;
        }
        return flow.nodeProps(context.getCurrentNodeId());
    }

    @Override
    public String getCurrentNodeId() {
        if (context == null) {
            return null;
        }
        return context.getCurrentNodeId();
    }

    @Override
    public Object getCurrentData() {
        if (context == null) {
            return null;
        }
        return context.getCurrentData();
    }

    @Override
    public void setCurrentData(Object data) {
        if (context == null) {
            return;
        }
        context.setCurrentData(data);
    }

    @Override
    public Map<String, Object> getAttributes() {
        if (context == null) {
            return Collections.emptyMap();
        }
        return context.getAttributes();
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (context == null) {
            return;
        }
        context.setAttribute(key, value);
    }

    @Override
    public <T> T getAttribute(String key) {
        if (context == null) {
            return null;
        }
        return context.getAttribute(key);
    }

    @Override
    public void waitForResume() {
        if (context != null) {
            context.setAction(Action.WAIT);
        }
    }

    @Override
    public void exit() {
        if (context != null) {
            context.setAction(Action.EXIT);
        }
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
     * 执行流水线并更新实例状态。
     *
     * <p>复用唯一上下文执行；节点挂起时进入 {@link FlowStatus#WAITED}，
     * 否则视为完成；执行异常时标记失败并向上抛出。</p>
     */
    private void execute() {
        try {
            pipeline.execute(context);
        } catch (Exception e) {
            status = FlowStatus.FAILED;
            throw e;
        }
        if (context.getAction() == Action.WAIT) {
            status = FlowStatus.WAITED;
        } else {
            status = FlowStatus.COMPLETED;
        }
    }
}
