package com.chua.common.support.task.pipeline.builder;

/**
 * 分支定义 — 支持链式风格的分支配置。
 *
 * <p>由 {@link TaskDecisionDefinition#branch(String)} 创建，提供语义化的目标节点配置方法。</p>
 *
 * <p><strong>与 {@code branch(key, nodeId)} 的关系：</strong></p>
 * <ul>
 *   <li>{@code .branch("yes", "processNode")} — 直接指定目标节点 ID</li>
 *   <li>{@code .branch("yes").toTask("processNode")} — 链式风格，语义更清晰</li>
 * </ul>
 *
 * <p>两者功能完全等价，链式风格在分支目标类型明确时更具可读性。</p>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 链式风格（推荐，语义清晰）
 * .task("check", ctx -> condition ? "yes" : "no")
 *     .decision()
 *     .branch("yes").toTask("processNode")
 *     .branch("no").toTask("errorNode")
 *     .taskEnd()
 *
 * // 混合使用
 * .task("route", ctx -> getTarget(ctx))
 *     .decision()
 *     .branch("A").toTask("nodeA")
 *     .branch("B").toDecision("nodeB")       // 目标是判断节点
 *     .branch("C").toSubPipeline("nodeC")    // 目标是子流水线节点
 *     .branch("default").toNode("fallback")  // 通用写法
 *     .taskEnd()
 *
 * // 传统风格（等价）
 * .task("check", ctx -> condition ? "yes" : "no")
 *     .decision()
 *     .branch("yes", "processNode")
 *     .branch("no", "errorNode")
 *     .taskEnd()
 * }</pre> "yes" : "no")
 *     .decision()
 *     .branch("yes", "processNode")
 *     .branch("no", "errorNode")
 *     .taskEnd()
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see TaskDecisionDefinition#branch(String)
 */
public class BranchDefinition {

    /**
     * 分支标签（处理器 返回值匹配的 键）
     */
    private final String key;

    /**
     * 父级判断节点定义
     */
    private final TaskDecisionDefinition parent;

    /**
     * 构造分支定义。
     *
     * @param key    分支标签
     * @param parent 父级判断节点定义
     */
    BranchDefinition(String key, TaskDecisionDefinition parent) {
        this.key = key;
        this.parent = parent;
    }

    /**
     * 指定分支目标为执行节点（语义化方法）。
     *
     * <p>功能上与 {@link #toNode(String)} 完全等价，语义上表示目标节点是执行节点（task）。</p>
     *
     * @param nodeId 目标执行节点 标识
     * @return 父级判断节点定义，继续配置其他分支
     */
    public TaskDecisionDefinition toTask(String nodeId) {
        return toNode(nodeId);
    }

    /**
     * 指定分支目标为判断节点（语义化方法）。
     *
     * <p>功能上与 {@link #toNode(String)} 完全等价，语义上表示目标节点是判断节点（decision）。</p>
     *
     * @param nodeId 目标判断节点 标识
     * @return 父级判断节点定义，继续配置其他分支
     */
    public TaskDecisionDefinition toDecision(String nodeId) {
        return toNode(nodeId);
    }

    /**
     * 指定分支目标为子流水线节点（语义化方法）。
     *
     * <p>功能上与 {@link #toNode(String)} 完全等价，语义上表示目标节点是子流水线节点（subPipeline）。</p>
     *
     * @param nodeId 目标子流水线节点 标识
     * @return 父级判断节点定义，继续配置其他分支
     */
    public TaskDecisionDefinition toSubPipeline(String nodeId) {
        return toNode(nodeId);
    }

    /**
     * 指定分支目标节点（通用方法）。
     *
     * <p>将分支标签映射到目标节点 ID，等价于 {@code parent.branch(key, nodeId)}。</p>
     *
     * @param nodeId 目标节点 标识
     * @return 父级判断节点定义，继续配置其他分支
     */
    public TaskDecisionDefinition toNode(String nodeId) {
        parent.branch(key, nodeId);
        return parent;
    }
}
