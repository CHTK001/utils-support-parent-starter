package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.core.SubPipelineResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子流水线节点。
 *
 * <p>支持流水线嵌套，将另一个 {@link Pipeline} 作为子流程嵌入当前流水线。
 * 子流水线的输入为父上下文的当前数据，执行完毕后将子流程的结果数据写回父上下文。</p>
 *
 * <p>支持以下可选配置：</p>
 * <ul>
 *   <li>{@link #preHandler(PipelineNode)} — 前置处理器，在子流水线执行前调用</li>
 *   <li>{@link #start(String)} — 子流水线起始节点 ID，覆盖默认起始节点</li>
 *   <li>{@link #params(Map)} — 子流水线参数，注入到子上下文的 nodeLocalData</li>
 * </ul>
 *
 * <p>子流水线执行完毕后，结果以 {@link SubPipelineResult} 结构化对象存入父上下文的
 * {@code nodeOutputs}，key 为子流水线节点的 nodeId。结构化存储使 key 统一为 nodeId，
 * 与普通节点和并行节点保持一致。</p>
 *
 * <pre>
 * nodeOutputs["subStep"] = SubPipelineResult {
 *     nodeId: "subStep",
 *     output: data,               // 子流水线的最终输出
 *     history: ["subA", "subB"],  // 子流水线的执行历史
 *     pipelineId: "sub"           // 子流水线 ID
 * }
 * </pre>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * Pipeline subPipeline = PipelineBuilder.newBuilder("sub")
 *     .task("subA", ctx -> { return null; }).taskEnd()
 *     .task("subB", ctx -> { return null; }).taskEnd()
 *     .build();
 *
 * Pipeline mainPipeline = PipelineBuilder.newBuilder("main")
 *     .task("mainStart", ctx -> { return null; }).taskEnd()
 *     .pipeline("subStep", subPipeline)
 *     .task("mainEnd", ctx -> { return null; }).taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SubPipelineNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 子流水线实例
     */
    private final Pipeline subPipeline;

    /**
     * 前置处理器（在子流水线执行前调用，可选）
     */
    private PipelineNode preHandler;

    /**
     * 子流水线起始节点 ID（覆盖默认起始节点，可选）
     */
    private String startNode;

    /**
     * 子流水线参数（注入到子上下文的 nodeLocalData，可选）
     */
    private Map<String, Object> params;

    /**
     * 节点环境参数映射（定义时配置，运行时环境配置如模型路径、阈值等）
     */
    private Map<String, Object> env;

    /**
     * 构造子流水线节点。
     *
     * @param id          节点唯一标识
     * @param subPipeline 子流水线实例
     */
    public SubPipelineNode(String id, Pipeline subPipeline) {
        this.id = id;
        this.subPipeline = subPipeline;
    }

    /**
     * 获取节点 ID。
     *
     * @return 节点 ID
     */
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "subPipeline";
    }

    /**
     * 获取子流水线 ID。
     *
     * @return 子流水线 ID
     */
    public String getSubPipelineId() {
        return subPipeline.getId();
    }

    /**
     * 获取子流水线实例。
     *
     * @return 子流水线 Pipeline 实例
     */
    public Pipeline getSubPipeline() {
        return subPipeline;
    }

    /**
     * 获取子流水线起始节点虚拟 ID（用于树打印）。
     *
     * @return 起始节点 ID
     */
    public String getSubPipelineStartId() {
        return "sub:" + subPipeline.getId() + ":start";
    }

    /**
     * 获取子流水线终止节点虚拟 ID（用于树打印）。
     *
     * @return 终止节点 ID
     */
    public String getSubPipelineEndId() {
        return "sub:" + subPipeline.getId() + ":end";
    }

    /**
     * 设置前置处理器。
     *
     * @param preHandler 前置处理器
     * @return this
     */
    public SubPipelineNode preHandler(PipelineNode preHandler) {
        this.preHandler = preHandler;
        return this;
    }

    /**
     * 获取前置处理器。
     *
     * @return 前置处理器，未设置时返回 null
     */
    public PipelineNode getPreHandler() {
        return preHandler;
    }

    /**
     * 设置子流水线起始节点 ID。
     *
     * @param startNode 起始节点 ID
     * @return this
     */
    public SubPipelineNode start(String startNode) {
        this.startNode = startNode;
        return this;
    }

    /**
     * 获取子流水线起始节点 ID。
     *
     * @return 起始节点 ID，未设置时返回 null
     */
    public String getStartNode() {
        return startNode;
    }

    /**
     * 设置子流水线参数。
     *
     * @param params 参数映射
     * @return this
     */
    public SubPipelineNode params(Map<String, Object> params) {
        this.params = params != null ? new LinkedHashMap<>(params) : null;
        return this;
    }

    /**
     * 获取子流水线参数。
     *
     * @return 参数映射，未设置时返回空 Map
     */
    public Map<String, Object> getParams() {
        return params != null ? params : Collections.emptyMap();
    }

    /**
     * 设置节点环境参数（定义时调用）。
     *
     * @param env 环境参数映射
     */
    public void setEnv(Map<String, Object> env) {
        this.env = env != null ? env : Collections.emptyMap();
    }

    @Override
    public Map<String, Object> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);

        // 1. 执行前置处理器（如果配置）
        if (preHandler != null) {
            preHandler.execute(context);
        }

        // 2. 执行子流水线
        PipelineContext<Object> subCtx;
        if (startNode != null || (params != null && !params.isEmpty())) {
            // 需要自定义起始节点或注入参数，使用已有上下文模式
            subCtx = new PipelineContext<>(subPipeline.getId(), context.getCurrentData());
            if (startNode != null) {
                subCtx.setNextNodeId(startNode);
            }
            if (params != null && !params.isEmpty()) {
                Map<String, Object> localData = subCtx.getNodeLocalData();
                localData.putAll(params);
            }
            subCtx = subPipeline.execute(subCtx);
        } else {
            subCtx = subPipeline.execute(context.getCurrentData());
        }

        // 3. 将子流程结果以 SubPipelineResult 结构化对象存入父上下文 nodeOutputs
        SubPipelineResult result = new SubPipelineResult(
                id, subCtx.getCurrentData(), subCtx.getHistory(), subPipeline.getId());
        context.setNodeOutput(id, result);
        // 同时更新 currentData，保持向后兼容（后续节点可通过 currentData 获取子流程输出）
        @SuppressWarnings("unchecked")
        PipelineContext<Object> parentCtx = (PipelineContext<Object>) context;
        parentCtx.setCurrentData(subCtx.getCurrentData());
        return null;
    }
}
