package com.chua.common.support.task.pipeline.core;

import com.chua.common.support.task.retry.RetryConfig;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 流水线节点接口。
 *
 * <p>所有节点类型的统一抽象。task、decision、subPipeline、start、end 等都是节点的具体形态。</p>
 *
 * <p>这是一个函数式接口，可通过 Lambda 表达式实现自定义节点。
 * 同时提供 {@link #getType()} 和 {@link #getParams()} 默认方法，用于统一描述节点元信息。</p>
 *
 * <p><strong>返回值语义：</strong></p>
 * <ul>
 *   <li>返回 {@code null} — 按默认顺序继续执行（等同于 Consumer 模式）</li>
 *   <li>返回非 null 字符串 — 跳转到指定节点 ID（等同于 Function 路由模式，引擎自动设置 nextNodeId + action=JUMP）</li>
 * </ul>
 *
 * <p><strong>返回值与显式动作的优先级规则：</strong></p>
 * <ul>
 *   <li>显式动作（EXIT/WAIT/BREAK/REPLAY/PREV）> 返回值 > 默认 NEXT</li>
 *   <li>仅当 {@code ctx.getAction() == Action.NEXT} 时，返回值才触发 JUMP</li>
 *   <li>若节点已设置 {@code ctx.setAction(Action.EXIT)}，即使返回非 null 值也不会跳转</li>
 * </ul>
 *
 * <p><strong>路由策略（{@link RouteStrategy}）：</strong></p>
 * <p>当返回值指向的节点不存在时，引擎根据 {@link RouteStrategy} 处理：</p>
 * <ul>
 *   <li>{@link RouteStrategy#THROW} — 抛出 PipelineException（默认）</li>
 *   <li>{@link RouteStrategy#EXIT} — 优雅终止流水线</li>
 *   <li>{@link RouteStrategy#NEXT} — 跳过不存在的节点，按定义顺序继续</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 顺序执行（返回 null）
 * .task("step1", ctx -> {
 *     doWork(ctx);
 *     return null;
 * })
 *
 * // 动态路由（返回目标节点 ID）
 * .task("route", ctx -> {
 *     return condition ? "nodeA" : "nodeB";
 * })
 *
 * // 显式动作优先于返回值
 * .task("end", ctx -> {
 *     ctx.setAction(Action.EXIT);  // EXIT 生效，返回值被忽略
 *     return "somewhere";          // 不会跳转
 * })
 *
 * // 判断分支
 * .decision("check", ctx -> ctx.getData() != null ? "process" : "error")
 * }</pre>)
 * }</pre>
 *
 * <p><strong>内置节点实现：</strong></p>
 * <ul>
 *   <li>{@link com.chua.common.support.task.pipeline.node.TaskNode} — 执行节点（type = "task"）</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.DecisionNode} — 判断节点（type = "decision"）</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.StartNode} — 起始节点（type = "start"）</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.EndNode} — 终止节点（type = "end"）</li>
 *   <li>{@link com.chua.common.support.task.pipeline.node.SubPipelineNode} — 子流水线节点（type = "subPipeline"）</li>
 * </ul>
 *
 * <p><strong>节点元信息：</strong></p>
 * <ul>
 *   <li>{@link #getType()} — 节点类型标识，如 "task"、"decision"、"start"、"end"、"subPipeline"</li>
 *   <li>{@link #getParams()} — 节点参数映射，用于 JSON 构建时传递节点级配置，执行时注入到 {@code ctx.nodeLocalData}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
*/
@FunctionalInterface
public interface PipelineNode {

    /**
    * 获取节点 标识。
    *
    * <p>默认返回 null，具体节点类（TaskNode、DecisionNode 等）应覆盖此方法。</p>
    *
    * @return 节点 标识，lambda 实现时返回 空
    */
    default String getId() {
        return null;
    }

    /**
    * 执行节点逻辑并返回下一节点 标识。
    *
    * <p>节点在此方法中实现具体业务逻辑，可通过 {@link PipelineContext}
    * 读取/修改数据、控制执行流程。</p>
    *
    * <p><strong>返回值语义：</strong></p>
    * <ul>
    *   <li>返回 {@code null} — 按默认顺序继续执行</li>
    *   <li>返回非 null 字符串 — 跳转到指定节点 ID（引擎自动设置 nextNodeId + action=JUMP）</li>
    * </ul>
    *
    * <p>注意：如果节点通过 {@code ctx.setAction()} 设置了特殊动作（如 EXIT、BREAK、WAIT），
    * 则返回值的路由效果会被动作覆盖。</p>
    *
    * @param context 流水线上下文
    * @return 下一节点 标识，返回 空 表示按默认顺序执行
    */
    String execute(PipelineContext<?> context);

    /**
    * 获取节点类型标识。
    *
    * <p>所有节点都是节点，getType() 返回其具体形态的标识：</p>
    * <ul>
    *   <li>"task" — 执行节点</li>
    *   <li>"decision" — 判断节点</li>
    *   <li>"start" — 起始节点</li>
    *   <li>"end" — 终止节点</li>
    *   <li>"subPipeline" — 子流水线节点</li>
    * </ul>
    *
    * <p>Lambda 实现的节点默认返回 "task"。</p>
    *
    * @return 节点类型标识
    */
    default String getType() {
        return "task";
    }

    /**
    * 获取节点参数映射。
    *
    * <p>节点参数用于 JSON 构建时传递节点级配置。
    * 执行时，引擎会将 参数 中的所有键值对注入到 {@link PipelineContext#getNodeLocalData()}，
    * 节点内部可通过 {@code ctx.getNodeLocalValue("key")} 获取。</p>
    *
    * <p>默认返回空 Map。内置节点实现中，仅 JSON 构建的节点会携带 params。</p>
    *
    * @return 节点参数映射，不可变
    */
    default Map<String, Object> getParams() {
        return Collections.emptyMap();
    }

    /**
    * 获取节点环境参数映射。
    *
    * <p>环境参数与 {@link #getParams()} 的区别：</p>
    * <ul>
    *   <li><strong>params</strong> — JSON 构建时传入的静态参数，注入到 {@code ctx.nodeLocalData} 的根级</li>
    *   <li><strong>env</strong> — 节点定义时配置的运行时环境参数（如模型路径、阈值等），
    *       注入到 {@code ctx.nodeLocalData} 时以 {@code "env."} 前缀隔离，
    *       通过 {@code ctx.getNodeLocalValue("env.modelPath")} 获取</li>
    * </ul>
    *
    * <p>默认返回空 Map。通过 Definition API 的 {@code .env()} 方法设置。</p>
    *
    * @return 节点环境参数映射，不可变
    */
    default Map<String, Object> getEnv() {
        return Collections.emptyMap();
    }

    /**
    * 获取节点重试配置。
    *
    * <p>当节点配置了重试策略时，引擎在执行节点遇到异常会自动重试，
    * 而非直接触发错误恢复或终止流水线。</p>
    *
    * <p>重试使用 {@link RetryConfig} 配置，支持：</p>
    * <ul>
    *   <li>最大重试次数</li>
    *   <li>重试延迟与退避策略（固定/指数/斐波那契）</li>
    *   <li>异常过滤（仅对指定类型异常重试）</li>
    *   <li>重试监听回调</li>
    * </ul>
    *
    * <p>默认返回 null，表示不重试。通过 Definition API 的 {@code .retry()} 方法设置。</p>
    *
    * @return 重试配置，null 表示不重试
    */
    default RetryConfig getRetryConfig() {
        return null;
    }

    /**
    * 获取数据依赖声明 — 此节点需要哪些节点的输出数据。
    *
    * <p>引擎在执行此节点前，会校验依赖的节点输出是否已存在于 nodeOutputs 中。
    * 若依赖未满足（某个依赖节点的输出尚未产生），引擎将抛出异常。</p>
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
    *
    * <p>默认返回空集合，表示无数据依赖。通过 Definition API 的 {@code .unit()} 方法设置。</p>
    *
    * @return 依赖的节点 标识 集合，空集合表示无依赖
    */
    default Set<String> getUnits() {
        return Collections.emptySet();
    }
}
