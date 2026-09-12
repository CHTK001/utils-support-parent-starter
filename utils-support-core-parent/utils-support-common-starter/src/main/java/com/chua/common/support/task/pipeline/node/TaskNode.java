package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.retry.RetryConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
* }</pre>  }
* 返回 空;  // 按默认顺序执行
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
* }</pre>     return "validate";  // 跳转到 validate 节点
* })
* }</pre>
*
* @author CH
* @since 4.0.0.42
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
    * 节点参数映射（JSON 构建时传入，执行时注入到 ctx.节点本地数据）
     */
    private Map<String, Object> params;

    /**
    * 节点环境参数映射（定义时配置，执行时以 "env." 前缀注入到 ctx.节点本地数据）
     */
    private Map<String, Object> env;

    /**
    * 重试配置，空 表示不重试
     */
    private RetryConfig retryConfig;

    /**
    * 数据依赖声明 — 声明此节点需要哪些节点的输出数据。
    *
    * <p>引擎在执行此节点前，会校验依赖的节点输出是否已存在于 nodeOutputs 中。
    * 若依赖未满足，根据策略处理（默认抛出异常）。</p>
    *
    * <p>在并行场景中，节点 C 需要节点 A 和节点 B 的数据，可通过 unit 声明式表达：</p>
    * <pre>{@code
    * .taskStart("merge")
    *     .unit("stepA", "stepB")  // 声明依赖 stepA 和 stepB 的输出
    *     .onStep(ctx -> {
    *         Object dataA = ctx.getData("stepA");
    *         Object dataB = ctx.getData("stepB");
    *         // 合并数据...
    *     })
    *     .taskEnd()
    * }</pre>
    *     })
    * .任务结束()
    * }</pre>
     */
    private Set<String> units;

    /**
    * 构造执行节点。
    *
    * @param id      节点唯一标识
    * @param handler 业务逻辑处理器，返回 空 按默认顺序执行，返回节点 标识 则跳转
     */
    public TaskNode(String id, PipelineNode handler) {
        this.id = id;
        this.handler = handler;
        this.params = Collections.emptyMap();
    }

    /**
    * 获取节点 标识。
    *
    * @return 节点 标识
     */
    public String getId() {
        return id;
    }

    /** 节点类型：任务。 */
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

    /** 返回任务参数表。 */
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
    * @param retryConfig 重试配置，空 表示不重试
     */
    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    /** 返回本节点的重试配置；未配置时由引擎按默认策略处理。 */
    @Override
    public RetryConfig getRetryConfig() {
        return retryConfig;
    }

    /**
    * 设置数据依赖声明。
    *
    * @param units 依赖的节点 标识 集合
     */
    public void setUnits(Set<String> units) {
        this.units = units != null ? units : Collections.emptySet();
    }

    /** 返回聚合结果的目标节点 标识 集合。 */
    @Override
    public Set<String> getUnits() {
        return units != null ? units : Collections.emptySet();
    }

    /** 执行任务处理器逻辑并返回下一节点 标识。 */
    @Override
    public String execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);
        return handler.execute(context);
    }
}
