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
 * <p><strong>数据共享：</strong>子流水线上下文通过 {@link PipelineContext#createBranchContext(String, Object)}
 * 与父上下文共享 {@code attributes} 和 {@code nodeOutputs}（与 {@code ForkNode} 分支一致），
 * 子流程可直接读写父流程的共享属性。</p>
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
 * }</pre>* .构建();
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
    * 子流水线起始节点 标识（覆盖默认起始节点，可选）
    */
    private String startNode;

    /**
    * 子流水线参数（注入到子上下文的 节点本地数据，可选）
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
    * 获取节点 标识。
    *
    * @return 节点 标识
    */
    public String getId() {
        return id;
    }

    /** 节点类型：subpipeline。 */
    @Override
    public String getType() {
        return "subPipeline";
    }

    /**
    * 获取子流水线 标识。
    *
    * @return 子流水线 标识
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
    * 获取子流水线起始节点虚拟 标识（用于树打印）。
    *
    * @return 起始节点 标识
    */
    public String getSubPipelineStartId() {
        return "sub:" + subPipeline.getId() + ":start";
    }

    /**
    * 获取子流水线终止节点虚拟 标识（用于树打印）。
    *
    * @return 终止节点 标识
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
    * @return 前置处理器，未设置时返回 空
    */
    public PipelineNode getPreHandler() {
        return preHandler;
    }

    /**
    * 设置子流水线起始节点 标识。
    *
    * @param startNode 起始节点 标识
    * @return this
    */
    public SubPipelineNode start(String startNode) {
        this.startNode = startNode;
        return this;
    }

    /**
    * 获取子流水线起始节点 标识。
    *
    * @return 起始节点 标识，未设置时返回 空
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
    * @return 参数映射，未设置时返回空 映射
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

    /** 返回节点环境变量表。 */
    @Override
    public Map<String, Object> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }

    /** 执行子流水线并回传其结果数据。 */
    @SuppressWarnings("unchecked")
    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);

        // 1. 执行前置处理器（如果配置）
        if (preHandler != null) {
            preHandler.execute(context);
        }

 // 2. 执行子流水线 — 通过 创建分支上下文 共享 attributes/节点输出（与 fork节点 一致）
        PipelineContext<Object> subCtx =
                context.createBranchContext(subPipeline.getId(), context.getCurrentData());
        if (startNode != null) {
            subCtx.setNextNodeId(startNode);
        }
        if (params != null && !params.isEmpty()) {
            Map<String, Object> localData = subCtx.getNodeLocalData();
            localData.putAll(params);
        }
        subCtx = subPipeline.execute(subCtx);

 // 3. 将子流程结果以 subpipeline结果 结构化对象存入父上下文 节点输出
        SubPipelineResult result = new SubPipelineResult(
                id, subCtx.getCurrentData(), subCtx.getHistory(), subPipeline.getId());
        context.setNodeOutput(id, result);
 // 同时更新 当前数据，保持向后兼容（后续节点可通过 当前数据 获取子流程输出）
        @SuppressWarnings("unchecked")
        PipelineContext<Object> parentCtx = (PipelineContext<Object>) context;
        parentCtx.setCurrentData(subCtx.getCurrentData());
        return null;
    }
}
