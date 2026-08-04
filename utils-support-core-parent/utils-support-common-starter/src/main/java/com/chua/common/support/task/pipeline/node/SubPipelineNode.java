package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import org.jspecify.annotations.NullUnmarked;

/**
 * 子流水线节点。
 *
 * <p>支持流水线嵌套，将另一个 {@link Pipeline} 作为子流程嵌入当前流水线。
 * 子流水线的输入为父上下文的当前数据，执行完毕后将子流程的结果数据写回父上下文。</p>
 *
 * <p>子流水线的执行上下文和历史可通过父上下文的 attributes 获取：</p>
 * <ul>
 *   <li>{@code subPipelineContext} — 子流程上下文</li>
 *   <li>{@code subPipelineHistory} — 子流程历史节点列表</li>
 * </ul>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * Pipeline subPipeline = PipelineBuilder.newBuilder("sub")
 *     .task("subA", ctx -> { })
 *     .task("subB", ctx -> { })
 *     .build();
 *
 * Pipeline mainPipeline = PipelineBuilder.newBuilder("main")
 *     .task("mainStart", ctx -> { })
 *     .pipeline("subStep", subPipeline)
 *     .task("mainEnd", ctx -> { })
 *     .build();
 * }</pre>
 *
 * @author CH
 */
@NullUnmarked
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

    /**
     * 获取子流水线 ID。
     *
     * @return 子流水线 ID
     */
    public String getSubPipelineId() {
        return subPipeline.getId();
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

    @Override
    public void execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);

        PipelineContext<Object> subCtx = subPipeline.execute(context.getCurrentData());

        @SuppressWarnings("unchecked")
        PipelineContext<Object> parentCtx = (PipelineContext<Object>) context;
        parentCtx.setCurrentData(subCtx.getCurrentData());
        parentCtx.setAttribute("subPipelineContext", subCtx);
        parentCtx.setAttribute("subPipelineHistory", subCtx.getHistory());
    }
}
