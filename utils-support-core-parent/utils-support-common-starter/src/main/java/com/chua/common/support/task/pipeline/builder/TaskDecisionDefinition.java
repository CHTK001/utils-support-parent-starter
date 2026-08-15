package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.DecisionNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判断节点定义 — 类型安全的分支配置构建器。
 *
 * <p>由 {@link TaskDefinition#decision()} 创建，支持链式配置分支映射后
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>与 TaskDefinition 的区别：</strong></p>
 * <ul>
 *   <li>拥有分支专属方法 {@link #branch(String, String)}、{@link #branches(Map)}</li>
 *   <li>拥有分支级便捷方法 {@link #end(String)} — 指定分支直接终止流水线</li>
 *   <li>可通过 {@link #withoutDecision()} 退回 {@link TaskDefinition}</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 二路分支
 * PipelineBuilder.newBuilder("flow")
 *     .task("check", ctx -> ctx.getCurrentData() != null ? "yes" : "no")
 *     .decision()                        // → TaskDecisionDefinition
 *     .branch("yes", "processNode")      // 分支映射
 *     .branch("no", "errorNode")
 *     .taskEnd()                         // 完成定义
 *     .build();
 *
 * // 带终止分支
 * PipelineBuilder.newBuilder("flow")
 *     .task("validate", ctx -> isValid ? "ok" : "fail")
 *     .decision()
 *     .branch("ok", "processNode")
 *     .end("fail")                       // "fail" 分支直接终止流水线
 *     .taskEnd()
 *     .build();
 *
 * // 退回 TaskDefinition
 * .task("check", ctx -> condition ? "yes" : "no")
 *     .decision()
 *     .branch("yes", "processNode")
 *     .withoutDecision()                 // → 退回 TaskDefinition
 *     .end()                             // 使用 TaskDefinition 的便捷方法
 *     .taskEnd()
 * }</pre>
 *
 * @author CH
 * @see TaskDefinition
 */
public class TaskDecisionDefinition {

    private final String id;
    private PipelineNode router;
    private final Map<String, String> branches = new LinkedHashMap<>();
    private final PipelineBuilder builder;

    /**
     * 构造判断节点定义。
     *
     * @param id      节点唯一标识
     * @param router  路由处理器（来自 TaskDefinition 的 handler）
     * @param builder 流水线构建器
     */
    TaskDecisionDefinition(String id, PipelineNode router, PipelineBuilder builder) {
        this.id = id;
        this.router = router;
        this.builder = builder;
    }

    /**
     * 完成定义，将判断节点添加到流水线，返回构建器继续链式配置。
     *
     * @return PipelineBuilder
     */
    public PipelineBuilder taskEnd() {
        DecisionNode node = new DecisionNode(id, router);
        if (!branches.isEmpty()) {
            node.branches(branches);
        }
        builder.addNodeInternal(node);
        return builder;
    }

    /**
     * 添加分支映射。
     *
     * <p>分支映射用于树打印可视化，路由逻辑由 router 回调决定。
     * router 返回的值应与 branch 的 key 匹配，引擎据此跳转到对应的目标节点。</p>
     *
     * @param key      分支标签（如 "true"/"false" 或自定义名称）
     * @param targetId 目标节点 ID
     * @return this
     */
    public TaskDecisionDefinition branch(String key, String targetId) {
        branches.put(key, targetId);
        return this;
    }

    /**
     * 批量设置分支映射。
     *
     * @param branches 分支标签 -> 目标节点 ID 的映射
     * @return this
     */
    public TaskDecisionDefinition branches(Map<String, String> branches) {
        if (branches != null) {
            this.branches.putAll(branches);
        }
        return this;
    }

    /**
     * 便捷方法：指定分支直接终止流水线。
     *
     * <p>当 router 返回值等于 {@code branchKey} 时，自动设置 {@code ctx.setAction(Action.EXIT)}，
     * 流水线优雅终止并触发 onComplete 回调。</p>
     *
     * <p>等价于创建一个虚拟终止节点，但无需显式定义该节点。</p>
     *
     * <p>用法示例：</p>
     * <pre>{@code
     * .task("validate", ctx -> isValid ? "ok" : "fail")
     * .decision()
     * .branch("ok", "processNode")
     * .end("fail")     // "fail" 分支直接终止流水线
     * .taskEnd()
     * }</pre>
     *
     * @param branchKey 分支标签，当 router 返回此值时终止流水线
     * @return this
     */
    public TaskDecisionDefinition end(String branchKey) {
        PipelineNode originalRouter = this.router;
        this.router = ctx -> {
            String result = originalRouter.execute(ctx);
            if (branchKey.equals(result)) {
                ctx.setAction(Action.EXIT);
                return null;
            }
            return result;
        };
        return this;
    }

    /**
     * 退回任务节点定义。
     *
     * <p>放弃判断节点配置，将 router 回退为普通任务的 handler，
     * 返回 {@link TaskDefinition} 继续配置。</p>
     *
     * @return TaskDefinition
     */
    public TaskDefinition withoutDecision() {
        return new TaskDefinition(id, router, builder);
    }
}