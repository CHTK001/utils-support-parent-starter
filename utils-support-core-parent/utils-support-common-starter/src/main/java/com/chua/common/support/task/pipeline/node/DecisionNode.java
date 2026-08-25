package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.retry.RetryConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判断节点。
 *
 * <p>支持分支逻辑的节点，根据条件判断结果选择不同的执行路径。
 * 统一使用 {@link PipelineNode} 回调，返回目标节点 ID 实现路由：</p>
 * <ul>
 *   <li><strong>返回节点 ID</strong> — 跳转到指定节点</li>
 *   <li><strong>返回 null</strong> — 按默认顺序执行</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 二路分支
 * .decision("check", ctx -> ctx.getCurrentData() != null ? "process" : "error")
 *
 * // 多路分支
 * .decision("route", ctx -> {
 *     String type = ctx.getAttribute("type");
 *     switch (type) {
 *         case "A": return "nodeA";
 *         case "B": return "nodeB";
 *         default: return "defaultNode";
 *     }
 * })
 * }</pre>
 *
 * <p>分支映射（可选，用于树打印可视化）可通过 {@link #branches(Map)} 设置。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DecisionNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 路由处理器（统一回调），返回目标节点 ID
     */
    private final PipelineNode router;

    /**
     * 分支映射（可选，用于树打印可视化）
     * key 为分支标签（如 "true"/"false" 或自定义名称），value 为目标节点 ID
     */
    private Map<String, String> branches;

    /**
     * 默认分支目标节点 ID（可选，当 handler 返回值不匹配任何分支时使用）
     */
    private String defaultBranch;

    /**
     * 节点参数映射（JSON 构建时传入，执行时注入到 ctx.nodeLocalData）
     */
    private Map<String, Object> params;

    /**
     * 节点环境参数映射（定义时配置，运行时环境配置如模型路径、阈值等）
     */
    private Map<String, Object> env;

    /**
     * 重试配置，null 表示不重试
     */
    private RetryConfig retryConfig;

    /**
     * 构造判断节点。
     *
     * @param id     节点唯一标识
     * @param router 路由处理器，返回目标节点 ID；返回 null 表示按默认顺序执行
     */
    public DecisionNode(String id, PipelineNode router) {
        this.id = id;
        this.router = router;
        this.params = Collections.emptyMap();
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
     * 获取分支映射（用于树打印可视化）。
     *
     * @return 分支标签 -> 目标节点 ID 的映射；未设置时返回空 Map
     */
    public Map<String, String> getBranches() {
        return branches != null ? branches : Collections.emptyMap();
    }

    /**
     * 设置分支映射（用于树打印可视化）。
     *
     * <p>分支映射不影响路由逻辑（路由由 router 回调决定），
     * 仅用于 {@link com.chua.common.support.task.pipeline.builder.DefaultPipeline#printTree} 等可视化场景。</p>
     *
     * @param branches 分支标签 -> 目标节点 ID 的映射
     * @return this
     */
    public DecisionNode branches(Map<String, String> branches) {
        this.branches = branches != null ? new LinkedHashMap<>(branches) : null;
        return this;
    }

    /**
     * 获取默认分支目标节点 ID。
     *
     * @return 默认分支目标节点 ID，未设置时返回 null
     */
    public String getDefaultBranch() {
        return defaultBranch;
    }

    /**
     * 设置默认分支目标节点 ID。
     *
     * <p>当 handler 返回值不匹配任何已配置的分支时，路由到默认节点。</p>
     *
     * @param defaultBranch 默认目标节点 ID
     * @return this
     */
    public DecisionNode defaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
        return this;
    }

    /** 节点类型：decision。 */
    @Override
    public String getType() {
        return "decision";
    }

    /**
     * 设置节点参数（JSON 构建时调用）。
     *
     * @param params 节点参数映射
     */
    public void setParams(Map<String, Object> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

    /** 返回路由参数表。 */
    @Override
    public Map<String, Object> getParams() {
        return params;
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

    /**
     * 设置重试配置。
     *
     * @param retryConfig 重试配置，null 表示不重试
     */
    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    /** 返回本节点的重试配置；未配置时由引擎按默认策略处理。 */
    @Override
    public RetryConfig getRetryConfig() {
        return retryConfig;
    }

    /** 执行路由判定，返回下一节点 ID。 */
    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);
        return router.execute(context);
    }
}
