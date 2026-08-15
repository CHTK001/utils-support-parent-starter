package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

import java.util.Collections;
import java.util.Map;

/**
 * 执行节点。
 *
 * <p>最常用的节点类型，用于执行具体的业务逻辑。统一使用 {@link PipelineNode} 回调：</p>
 * <ul>
 *   <li><strong>返回 null</strong> — 按默认顺序继续执行</li>
 *   <li><strong>返回节点 ID</strong> — 跳转到指定节点（动态路由）</li>
 * </ul>
 *
 * <p><strong>顺序执行：</strong></p>
 * <pre>{@code
 * .task("validate", ctx -> {
 *     String data = ctx.getCurrentData();
 *     if (data == null) {
 *         ctx.setAction(Action.EXIT);
 *     }
 *     return null;  // 按默认顺序执行
 * })
 * }</pre>
 *
 * <p><strong>动态路由：</strong></p>
 * <pre>{@code
 * .task("process", ctx -> {
 *     Object result = doProcess(ctx.getCurrentData());
 *     ctx.setCurrentData(result);
 *     return "validate";  // 跳转到 validate 节点
 * })
 * }</pre>
 *
 * @author CH
 */
public class TaskNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 业务逻辑处理器（统一回调）
     */
    private final PipelineNode handler;

    /**
     * 节点参数映射（JSON 构建时传入，执行时注入到 ctx.nodeLocalData）
     */
    private Map<String, Object> params;

    /**
     * 节点环境参数映射（定义时配置，执行时以 "env." 前缀注入到 ctx.nodeLocalData）
     */
    private Map<String, Object> env;

    /**
     * 构造执行节点。
     *
     * @param id      节点唯一标识
     * @param handler 业务逻辑处理器，返回 null 按默认顺序执行，返回节点 ID 则跳转
     */
    public TaskNode(String id, PipelineNode handler) {
        this.id = id;
        this.handler = handler;
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

    @Override
    public String getType() {
        return "task";
    }

    /**
     * 设置节点参数（JSON 构建时调用）。
     *
     * @param params 节点参数映射
     */
    public void setParams(Map<String, Object> params) {
        this.params = params != null ? params : Collections.emptyMap();
    }

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

    @Override
    public Map<String, Object> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }

    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);
        return handler.execute(context);
    }
}