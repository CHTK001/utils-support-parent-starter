package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.DecisionNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 判断节点定义 — 类型安全的分支配置构建器。
 *
 * <p>通过 {@link TaskDefinition#decision()} 从任务定义转换而来，
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>完整模式：task → decision → ... → taskEnd</strong></p>
 * <pre>{@code
 * .task("check", ctx -> condition ? "yes" : "no")
 *     .decision()                    // 转为判断定义
 *     .branch("yes", "processNode")  // 配置分支
 *     .branch("no", "errorNode")
 *     .taskEnd()                     // 结束定义
 * }</pre>
 *
 * <p><strong>便捷方法：</strong></p>
 * <ul>
 *   <li>{@link #onStep(Consumer)} — 无返回值的步骤（Consumer 模式）</li>
 *   <li>{@link #step(PipelineNode)} — 有返回值的步骤（Function 模式）</li>
 *   <li>{@link #ext()} — 执行后自动终止流水线（等价于 action=EXIT）</li>
 *   <li>{@link #end()} — 同 {@link #ext()}，执行后终止流水线</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 基本判断
 * PipelineBuilder.newBuilder("flow")
 *     .task("check", ctx -> isValid(ctx) ? "valid" : "invalid")
 *     .decision()
 *     .branch("valid", "processNode")
 *     .branch("invalid", "errorNode")
 *     .taskEnd()
 *     .build();
 *
 * // 链式风格（推荐，语义更清晰）
 * PipelineBuilder.newBuilder("flow")
 *     .task("check", ctx -> isValid(ctx) ? "valid" : "invalid")
 *     .decision()
 *     .branch("valid").toTask("processNode")
 *     .branch("invalid").toTask("errorNode")
 *     .taskEnd()
 *     .build();
 *
 * // 带默认分支
 * PipelineBuilder.newBuilder("flow")
 *     .task("route", ctx -> getTarget(ctx))
 *     .decision()
 *     .branch("A", "nodeA")
 *     .branch("B", "nodeB")
 *     .defaultBranch("fallbackNode")
 *     .taskEnd()
 *     .build();
 *
 * // 使用 ext 终止
 * PipelineBuilder.newBuilder("flow")
 *     .task("finalCheck", ctx -> checkResult(ctx))
 *     .decision()
 *     .branch("ok", "doneNode")
 *     .branch("fail", "errorNode")
 *     .ext()
 *     .taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @see TaskDefinition#decision()
 */
public class TaskDecisionDefinition {

    private final String id;
    private PipelineNode handler;
    private final PipelineBuilder builder;
    private final Map<String, String> branches = new LinkedHashMap<>();
    private String defaultBranch;
    private boolean endAfterExecute;

    /**
     * 构造判断节点定义。
     *
     * @param id      节点唯一标识
     * @param handler 路由逻辑处理器（返回目标节点 ID）
     * @param builder 流水线构建器
     */
    TaskDecisionDefinition(String id, PipelineNode handler, PipelineBuilder builder) {
        this.id = id;
        this.handler = handler;
        this.builder = builder;
    }

    /**
     * 完成定义，将判断节点添加到流水线，返回构建器继续链式配置。
     *
     * <p>与 {@link TaskDefinition#decision()} 配对使用，
     * 构成完整的判断定义：task → decision → ... → taskEnd。</p>
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        PipelineNode effectiveHandler = endAfterExecute ? wrapWithEnd(handler) : handler;
        DecisionNode node = new DecisionNode(id, effectiveHandler);
        if (!branches.isEmpty()) {
            node.branches(branches);
        }
        if (defaultBranch != null) {
            node.defaultBranch(defaultBranch);
        }
        builder.addNodeInternal(node);
        return builder;
    }

    /**
     * 结束当前节点定义并完成整个流水线构建。
     *
     * <p>等价于 {@code .taskEnd().end(id).build()}，一步完成三件事：</p>
     * <ol>
     *   <li>调用 {@link #taskEnd()} 完成当前判断节点定义</li>
     *   <li>将当前节点标记为流水线终止节点</li>
     *   <li>构建并返回 {@link Pipeline} 实例</li>
     * </ol>
     *
     * <p>适用于流水线最后一个节点是判断节点的场景。</p>
     *
     * @return 构建完成的 Pipeline 实例
     */
    public Pipeline pipelineEnd() {
        taskEnd();
        builder.end(id);
        return builder.build();
    }

    /**
     * 添加分支映射。
     *
     * <p>当 handler 返回值匹配 key 时，路由到 value 指定的节点。</p>
     *
     * @param key      handler 返回值
     * @param nodeType 目标节点 ID
     * @return this
     */
    public TaskDecisionDefinition branch(String key, String nodeType) {
        branches.put(key, nodeType);
        return this;
    }

    /**
     * 添加分支映射（链式风格）。
     *
     * <p>返回 {@link BranchDefinition}，支持语义化的目标节点配置：</p>
     * <pre>{@code
     * .branch("yes").toTask("processNode")       // 目标是执行节点
     * .branch("no").toDecision("errorCheck")     // 目标是判断节点
     * .branch("retry").toSubPipeline("retryFlow") // 目标是子流水线
     * .branch("fallback").toNode("defaultNode")  // 通用写法
     * }</pre>
     *
     * <p>与 {@link #branch(String, String)} 功能等价，但链式风格语义更清晰。</p>
     *
     * @param key handler 返回值
     * @return BranchDefinition 分支定义
     */
    public BranchDefinition branch(String key) {
        return new BranchDefinition(key, this);
    }

    /**
     * 设置默认分支。
     *
     * <p>当 handler 返回值不匹配任何已配置的分支时，路由到默认节点。</p>
     *
     * @param nodeType 默认目标节点 ID
     * @return this
     */
    public TaskDecisionDefinition defaultBranch(String nodeType) {
        this.defaultBranch = nodeType;
        return this;
    }

    /**
     * 设置无返回值的步骤处理器（Consumer 模式）。
     *
     * <p>适用于判断前需要执行副作用的场景。自动将 Consumer 包装为返回 null 的 PipelineNode。</p>
     *
     * @param action Consumer 回调
     * @return this
     */
    public TaskDecisionDefinition onStep(Consumer<PipelineContext<?>> action) {
        PipelineNode original = this.handler;
        this.handler = ctx -> {
            if (original != null) {
                original.execute(ctx);
            }
            action.accept(ctx);
            return null;
        };
        return this;
    }

    /**
     * 设置有返回值的步骤处理器（Function 模式）。
     *
     * <p>适用于需要根据执行结果路由到其他节点的场景。</p>
     *
     * @param handler PipelineNode 处理器
     * @return this
     */
    public TaskDecisionDefinition step(PipelineNode handler) {
        this.handler = handler;
        return this;
    }

    /**
     * 便捷方法：执行后自动终止流水线（等价于 action=EXIT）。
     *
     * <p>与 {@link #end()} 完全等价，提供更语义化的命名。</p>
     *
     * @return this
     */
    public TaskDecisionDefinition ext() {
        this.endAfterExecute = true;
        return this;
    }

    /**
     * 便捷方法：执行后自动终止流水线。
     *
     * <p>与 {@link #ext()} 完全等价。</p>
     *
     * @return this
     */
    public TaskDecisionDefinition end() {
        this.endAfterExecute = true;
        return this;
    }

    /**
     * 包装 handler：执行后设置 EXIT 动作。
     */
    private static PipelineNode wrapWithEnd(PipelineNode original) {
        return ctx -> {
            String result = original.execute(ctx);
            ctx.setAction(Action.EXIT);
            return null;
        };
    }
}