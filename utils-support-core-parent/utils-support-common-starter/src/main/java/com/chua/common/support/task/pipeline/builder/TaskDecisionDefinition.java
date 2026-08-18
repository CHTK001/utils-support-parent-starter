package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.DecisionNode;
import com.chua.common.support.task.retry.RetryConfig;

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
 *   <li>{@link #exit()} — 执行后自动终止流水线（等价于 action=EXIT）</li>
 *   <li>{@link #end()} — 同 {@link #exit()}，执行后终止流水线</li>
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
 *     .exit()
 *     .taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see TaskDefinition#decision()
 */
public class TaskDecisionDefinition {

    /** ID */
    private final String id;
    /** 处理器 */
    private PipelineNode handler;
    /** 构建器 */
    private final PipelineBuilder builder;
    private final Map<String, String> branches = new LinkedHashMap<>();
    /** 默认branch */
    private String defaultBranch;
    /** 结束afterexecute */
    private boolean endAfterExecute;
    private Map<String, Object> params;
    private Map<String, Object> env;
    /** 开始节点 */
    private boolean startNode;
    /** 重试配置 */
    private RetryConfig retryConfig;

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
        if (params != null && !params.isEmpty()) {
            node.setParams(params);
        }
        if (env != null && !env.isEmpty()) {
            node.setEnv(env);
        }
        if (retryConfig != null) {
            node.setRetryConfig(retryConfig);
        }
        builder.addNodeInternal(node);
        if (startNode) {
            builder.start(id);
        }
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
     * 设置节点参数（JSON 构建时传入，执行时注入到 ctx.nodeLocalData）。
     *
     * @param params 节点参数映射
     * @return this
     */
    public TaskDecisionDefinition params(Map<String, Object> params) {
        this.params = params;
        return this;
    }

    /**
     * 设置节点环境参数（运行时环境配置，如模型路径、阈值等）。
     *
     * <p>环境参数与 {@link #params(Map)} 的区别：</p>
     * <ul>
     *   <li><strong>params</strong> — 静态参数，注入到 nodeLocalData 根级</li>
     *   <li><strong>env</strong> — 运行时环境参数，注入到 nodeLocalData 时以 {@code "env."} 前缀隔离，
     *       通过 {@code ctx.getNodeLocalValue("env.modelPath")} 获取</li>
     * </ul>
     *
     * @param env 环境参数映射
     * @return this
     */
    public TaskDecisionDefinition env(Map<String, Object> env) {
        this.env = env;
        return this;
    }

    /**
     * 设置节点环境参数（单个键值对）。
     *
     * <p>等价于先创建 Map 再调用 {@link #env(Map)}，适用于少量参数的场景。</p>
     *
     * @param key   参数键
     * @param value 参数值
     * @return this
     */
    public TaskDecisionDefinition env(String key, Object value) {
        if (this.env == null) {
            this.env = new LinkedHashMap<>();
        }
        this.env.put(key, value);
        return this;
    }

    /**
     * 便捷方法：标记当前节点为起始节点。
     *
     * <p>等价于在 PipelineBuilder 上调用 {@code .start(id)}。</p>
     *
     * @return this
     */
    public TaskDecisionDefinition start() {
        this.startNode = true;
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
    public TaskDecisionDefinition exit() {
        this.endAfterExecute = true;
        return this;
    }

    /**
     * 设置重试配置。
     *
     * <p>当判断节点执行抛出异常时，引擎根据重试配置自动重试，而非直接触发错误恢复或终止。</p>
     *
     * <p>用法示例：</p>
     * <pre>{@code
     * .taskStart("checkRoute", ctx -> condition ? "yes" : "no")
     *     .decision()
     *     .retry(new RetryConfig().setMaxRetries(3).setDelay(500))
     *     .branch("yes", "processNode")
     *     .taskEnd()
     * }</pre>
     *
     * @param retryConfig 重试配置，null 表示不重试
     * @return this
     * @see com.chua.common.support.task.retry.RetryConfig
     */
    public TaskDecisionDefinition retry(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
        return this;
    }

    /**
     * 便捷方法：执行后自动终止流水线。
     *
     * <p>与 {@link #exit()} 完全等价。</p>
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
