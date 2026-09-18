package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.node.TaskNode;
import com.chua.common.support.task.retry.RetryConfig;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 任务节点定义 — 类型安全的流水线节点构建器。
 *
 * <p>由 {@link PipelineBuilder#task(String, PipelineNode)} 或
 * {@link PipelineBuilder#taskStart(String, PipelineNode)} 创建（开始定义），
 * 通过 {@link #taskEnd()} 完成定义并返回 {@link PipelineBuilder}。</p>
 *
 * <p><strong>完整模式：taskStart → ... → taskEnd</strong></p>
 * <pre>{@code
 * .taskStart("name", handler)   // 开始定义
 *     .onStep(...)              // 配置步骤
 *     .exit()                    // 便捷方法
 *     .taskEnd()                // 结束定义，返回 builder
 * }</pre>回 builder
 * }</pre>
 *
 * <p><strong>核心设计：</strong></p>
 * <ul>
 *   <li>所有节点统一通过 {@code task()/taskStart()} 创建，避免类型混乱</li>
 *   <li>通过类型转换方法（{@link #decision()}、{@link #subPipeline(Pipeline)}）切换到专属定义</li>
 *   <li>每种定义有专属便捷方法，防止用户写错</li>
 * </ul>
 *
 * <p><strong>类型转换：</strong></p>
 * <ul>
 *   <li>{@link #decision()} → {@link TaskDecisionDefinition}（判断节点定义，支持分支配置）</li>
 *   <li>{@link #subPipeline(Pipeline)} → {@link TaskSubPipelineDefinition}（子流水线定义）</li>
 * </ul>
 *
 * <p><strong>便捷方法：</strong></p>
 * <ul>
 *   <li>{@link #onStep(Consumer)} — 无返回值的步骤（Consumer 模式，自动返回 null）</li>
 *   <li>{@link #step(PipelineNode)} — 有返回值的步骤（Function 模式，可路由到其他节点）</li>
 *   <li>{@link #ext()} — 执行后自动终止流水线（等价于 action=EXIT）</li>
 *   <li>{@link #end()} — 同 {@link #ext()}，执行后终止流水线</li>
 *   <li>{@link #start()} — 标记为起始节点</li>
 *   <li>{@link #params(Map)} — 设置节点参数（JSON 构建时传入）</li>
 *   <li>{@link #env(Map)} / {@link #env(String, Object)} — 设置环境参数（运行时配置，如模型路径、阈值）</li>
 *   <li>{@link #pipelineEnd()} — 结束当前节点定义并完成整个流水线构建</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 基本任务
 * PipelineBuilder.newBuilder("flow")
 *     .task("step1", ctx -> { doWork(ctx); return null; })
 *     .taskEnd()
 *     .build();
 *
 * // taskStart...taskEnd 完整模式
 * PipelineBuilder.newBuilder("flow")
 *     .taskStart("init", ctx -> { init(ctx); return null; })
 *     .taskEnd()
 *     .build();
 *
 * // 使用 onStep（无返回值）
 * PipelineBuilder.newBuilder("flow")
 *     .taskStart("init")
 *     .onStep(ctx -> init(ctx))
 *     .taskEnd()
 *     .build();
 *
 * // 使用 step（有返回值，可路由）
 * PipelineBuilder.newBuilder("flow")
 *     .taskStart("route")
 *     .step(ctx -> condition ? "nodeA" : "nodeB")
 *     .taskEnd()
 *     .build();
 *
 * // 带便捷方法
 * PipelineBuilder.newBuilder("flow")
 *     .task("init", ctx -> { init(ctx); return null; })
 *     .start()       // 标记为起始节点
 *     .taskEnd()
 *     .task("done", ctx -> { cleanup(ctx); return null; })
 *     .exit()         // 执行后终止流水线
 *     .taskEnd()
 *     .build();
 *
 * // 转为判断节点
 * PipelineBuilder.newBuilder("flow")
 *     .task("check", ctx -> condition ? "yes" : "no")
 *     .decision()    // → TaskDecisionDefinition
 *     .branch("yes", "processNode")
 *     .branch("no", "errorNode")
 *     .taskEnd()
 *     .build();
 * }</pre>   * .构建();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see TaskDecisionDefinition
 * @see TaskSubPipelineDefinition
*/
public class TaskDefinition {

    /** 标识 */
    private final String id;
    /** 处理器 */
    private PipelineNode handler;
    /** 构建器 */
    private final PipelineBuilder builder;
    /** 结束afterexecute */
    private boolean endAfterExecute;
    /** 开始节点 */
    private boolean startNode;
    /** env */
    private Map<String, Object> env;
    /** 重试配置 */
    private RetryConfig retryConfig;
    /** Units */
    private Set<String> units;

    /**
    * 构造任务定义。
    *
    * @param id      节点唯一标识
    * @param handler 业务逻辑处理器
    * @param builder 流水线构建器
    */
    TaskDefinition(String id, PipelineNode handler, PipelineBuilder builder) {
        this.id = id;
        this.handler = handler;
        this.builder = builder;
    }

    /**
    * 完成定义，将任务节点添加到流水线，返回构建器继续链式配置。
    *
    * <p>与 {@link PipelineBuilder#taskStart(String, PipelineNode)} 配对使用，
    * 构成完整的任务定义：任务启动 → ... → 任务结束。</p>
    *
    * @return PipelineBuilder
    */
    public PipelineBuilder taskEnd() {
        PipelineNode effectiveHandler = endAfterExecute ? wrapWithEnd(handler) : handler;
        TaskNode node = new TaskNode(id, effectiveHandler);
        if (env != null && !env.isEmpty()) {
            node.setEnv(env);
        }
        if (retryConfig != null) {
            node.setRetryConfig(retryConfig);
        }
        if (units != null && !units.isEmpty()) {
            node.setUnits(units);
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
    *   <li>调用 {@link #taskEnd()} 完成当前节点定义</li>
    *   <li>将当前节点标记为流水线终止节点</li>
    *   <li>构建并返回 {@link Pipeline} 实例</li>
    * </ol>
    *
    * <p>适用于流水线最后一个节点的定义，语义清晰：此节点既是当前定义的结束，
    * 也是整个流水线的结束。</p>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * Pipeline pipeline = PipelineBuilder.newBuilder("flow")
    *     .taskStart("init", ctx -> { init(ctx); return null; }).taskEnd()
    *     .taskStart("process", ctx -> { process(ctx); return null; }).taskEnd()
    *     .taskStart("done", ctx -> { cleanup(ctx); return null; }).pipelineEnd();
    *     // ↑ 等价于 .taskEnd().end("done").build()
    * }</pre>束("done").构建()
    * }</pre>
    *
    * @return 构建完成的 Pipeline 实例
    */
    public Pipeline pipelineEnd() {
        taskEnd();
        builder.end(id);
        return builder.build();
    }

    /**
    * 设置无返回值的步骤处理器（Consumer 模式）。
    *
    * <p>适用于只需执行副作用、不需要路由到其他节点的场景。
    * 自动将 Consumer 包装为返回 空 的 pipeline节点。</p>
    *
    * <p>等价于：</p>
    * <pre>{@code
    * .task("name", ctx -> { doWork(ctx); return null; })
    * }</pre> * }</pre>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * .taskStart("init")
    * .onStep(ctx -> {
    *     ctx.setAttribute("key", "value");
    *     processData(ctx.getCurrentData());
    * })
    * .taskEnd()
    * }</pre>)
    * .任务结束()
    * }</pre>
    *
    * @param action Consumer 回调，无返回值
    * @return this
    */
    public TaskDefinition onStep(Consumer<PipelineContext<?>> action) {
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
    * <p>适用于需要根据执行结果路由到其他节点的场景。
    * 返回值语义与 {@link PipelineNode#execute(PipelineContext)} 一致：</p>
    * <ul>
    *   <li>返回 null — 按默认顺序继续执行</li>
    *   <li>返回节点 ID — 跳转到指定节点</li>
    * </ul>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * .taskStart("route")
    * .step(ctx -> {
    *     String type = ctx.getAttribute("type");
    *     return "A".equals(type) ? "nodeA" : "nodeB";
    * })
    * .taskEnd()
    * }</pre>    * .任务结束()
    * }</pre>
    *
    * @param handler pipeline节点 处理器，返回值决定路由
    * @return this
    */
    public TaskDefinition step(PipelineNode handler) {
        this.handler = handler;
        return this;
    }

    /**
    * 便捷方法：执行后自动终止流水线（等价于 动作=EXIT）。
    *
    * <p>包装 handler，在执行完毕后设置 {@code ctx.setAction(Action.EXIT)}，
    * 无论 处理器 返回什么值，流水线都将终止。</p>
    *
    * <p>等价于：</p>
    * <pre>{@code
    * .task("done", ctx -> {
    *     cleanup(ctx);
    *     ctx.setAction(Action.EXIT);
    *     return null;
    * })
    * }</pre>    * })
    * }</pre>
    *
    * <p>与 {@link #end()} 完全等价，提供更语义化的命名。</p>
    *
    * @return this
    */
    public TaskDefinition exit() {
        this.endAfterExecute = true;
        return this;
    }

    /**
    * 设置重试配置。
    *
    * <p>当节点执行抛出异常时，引擎根据重试配置自动重试，而非直接触发错误恢复或终止。</p>
    *
    * <p>重试配置支持：</p>
    * <ul>
    *   <li>最大重试次数</li>
    *   <li>重试延迟与退避策略（固定/指数/斐波那契）</li>
    *   <li>异常过滤（仅对指定类型异常重试）</li>
    *   <li>重试监听回调</li>
    * </ul>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * .taskStart("callApi", ctx -> { ... })
    *     .retry(new RetryConfig().setMaxRetries(3).setDelay(1000))
    *     .taskEnd()
    * }</pre>pre>
    *
    * @param retryConfig 重试配置，空 表示不重试
    * @return this
    * @see com.chua.common.support.task.retry.RetryConfig
    * @see com.chua.common.support.task.retry.RetryFlow
    */
    public TaskDefinition retry(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
        return this;
    }

    /**
    * 声明数据依赖 — 指定此节点需要哪些节点的输出数据。
    *
    * <p>引擎在执行此节点前，会校验依赖的节点输出是否已存在于 nodeOutputs 中。
    * 若依赖未满足（某个依赖节点的输出尚未产生），引擎将抛出异常。</p>
    *
    * <p><strong>核心价值：</strong></p>
    * <ul>
    *   <li>在并行场景中，声明式表达数据依赖关系，避免手动检查 {@code ctx.getData()} 返回 null</li>
    *   <li>引擎自动校验依赖，提前发现数据缺失问题</li>
    *   <li>依赖数据自动注入到 nodeLocalData，通过 {@code ctx.getNodeLocalValue("unit:stepA")} 获取</li>
    * </ul>
    *
    * <p><strong>用法示例：</strong></p>
    * <pre>{@code
    * // 并行场景：merge 节点需要 stepA 和 stepB 的输出
    * .fork("parallel")
    *     .branch("a", branchA)
    *     .branch("b", branchB)
    * .taskEnd()
    * .taskStart("merge")
    *     .unit("a", "b")          // 声明依赖 fork 分支 a 和 b 的输出
    *     .onStep(ctx -> {
    *         Object dataA = ctx.getData("a");   // ForkResult 中分支 a 的数据
    *         Object dataB = ctx.getData("b");   // ForkResult 中分支 b 的数据
    *         // 合并处理...
    *     })
    *     .taskEnd()
    *
    * // 线性场景：validate 节点需要 parse 节点的输出
    * .taskStart("validate")
    *     .unit("parse")           // 声明依赖 parse 节点的输出
    *     .onStep(ctx -> {
    *         Object parsed = ctx.getData("parse");
    *         // 校验...
    *     })
    *     .taskEnd()
    * }</pre>.获取数据("解析");
    *         // 校验...
    *     })
    * .任务结束()
    * }</pre>
    *
    * @param unitIds 依赖的节点 标识 列表
    * @return this
    * @see PipelineContext#getData(String)
    * @see PipelineContext#getNodeOutput(String)
    */
    public TaskDefinition unit(String... unitIds) {
        this.units = new LinkedHashSet<>(Arrays.asList(unitIds));
        return this;
    }

    /**
    * 转为判断节点定义。
    *
    * <p>当前任务的 handler 成为判断节点的路由器（返回目标节点 ID），
    * 可通过 {@link TaskDecisionDefinition#branch(String, String)} 配置分支映射。</p>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * .task("check", ctx -> condition ? "yes" : "no")
    * .decision()                        // → TaskDecisionDefinition
    * .branch("yes", "processNode")      // 添加分支
    * .branch("no", "errorNode")
    * .taskEnd()                         // 完成定义
    * }</pre>          // 完成定义
    * }</pre>
    *
    * @return TaskDecisionDefinition
    */
    public TaskDecisionDefinition decision() {
        return new TaskDecisionDefinition(id, handler, builder);
    }

    /**
    * 转为子流水线定义。
    *
    * <p>当前任务的 handler 被替换为子流水线执行逻辑。</p>
    *
    * @param subPipeline 子流水线实例
    * @return TaskSubPipelineDefinition
    */
    public TaskSubPipelineDefinition subPipeline(Pipeline subPipeline) {
        return new TaskSubPipelineDefinition(id, builder, subPipeline);
    }

    /**
    * 转为分叉节点定义。
    *
    * <p>当前任务的 handler 成为分叉节点的前置处理器（在所有分叉分支启动前执行），
    * 可通过 {@link TaskForkDefinition#branch(String, Pipeline)} 添加分叉分支。</p>
    *
    * <p>分叉节点对外是一个同步节点 — 父流水线阻塞等待所有分支完成后才继续。
    * 各分支通过独立上下文并发执行，结果存入 {@code nodeOutputs}。</p>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * Pipeline branchA = PipelineBuilder.newBuilder("branchA")
    *     .task("a1", ctx -> { doA1(ctx); return null; }).taskEnd()
    *     .build();
    *
    * Pipeline branchB = PipelineBuilder.newBuilder("branchB")
    *     .task("b1", ctx -> { doB1(ctx); return null; }).taskEnd()
    *     .build();
    *
    * .taskStart("fork-group")
    *     .fork()                                // → TaskForkDefinition
    *     .branch("a", branchA)                  // 添加分支
    *     .branch("b", branchB)
    *     .errorStrategy(ForkErrorStrategy.WAIT_ALL)
    * .taskEnd()                                 // 完成定义
    * }</pre>       // 完成定义
    * }</pre>
    *
    * @return TaskForkDefinition
    * @see TaskForkDefinition
    * @see com.chua.common.support.task.pipeline.node.ForkNode
    */
    public TaskForkDefinition fork() {
        TaskForkDefinition def = new TaskForkDefinition(id, builder);
        if (handler != null) {
 // 处理器 作为前置处理器
            def.onStep(ctx -> handler.execute(ctx));
        }
        return def;
    }

    /**
    * 转为并行子流水线定义。
    *
    * <p>当前任务的 handler 被替换为并行子流水线执行逻辑。
    * 并行子流水线在后台线程执行，不阻塞主流水线。</p>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * Pipeline parallelSub = PipelineBuilder.newBuilder("parallelSub")
    *     .task("a1", ctx -> { ...; return null; }).taskEnd()
    *     .build();
    *
    * .taskStart("bgTask")
    *     .parallel(parallelSub)                  // → TaskParallelDefinition
    *     .onComplete((ctx, result) -> {          // 完成回调
    *         log.info("Parallel completed: {}", result.getOutput());
    *     })
    * .taskEnd()
    * }</pre>})
    * .任务结束()
    * }</pre>
    *
    * @param subPipeline 并行子流水线实例
    * @return TaskParallelDefinition
    * @see TaskParallelDefinition
    * @see com.chua.common.support.task.pipeline.node.ParallelNode
    */
    public TaskParallelDefinition parallel(Pipeline subPipeline) {
        return new TaskParallelDefinition(id, builder, subPipeline);
    }

    /**
    * 便捷方法：执行后自动终止流水线。
    *
    * <p>包装 handler，在执行完毕后设置 {@code ctx.setAction(Action.EXIT)}，
    * 无论 处理器 返回什么值，流水线都将终止。</p>
    *
    * <p>等价于：</p>
    * <pre>{@code
    * .task("done", ctx -> {
    *     cleanup(ctx);
    *     ctx.setAction(Action.EXIT);
    *     return null;
    * })
    * }</pre>    * })
    * }</pre>
    *
    * <p>与 {@link #ext()} 完全等价。</p>
    *
    * @return this
    */
    public TaskDefinition end() {
        this.endAfterExecute = true;
        return this;
    }

    /**
    * 便捷方法：标记当前节点为起始节点。
    *
    * <p>等价于在 PipelineBuilder 上调用 {@code .start(id)}。</p>
    *
    * @return this
    */
    public TaskDefinition start() {
        this.startNode = true;
        return this;
    }

    /**
    * 设置节点参数（JSON 构建时传入，执行时注入到 ctx.节点本地数据）。
    *
    * @param params 节点参数映射
    * @return this
    */
    public TaskDefinition params(Map<String, Object> params) {
        PipelineNode original = this.handler;
        this.handler = new PipelineNode() {
            /** 委托原处理器执行节点逻辑。 */
            @Override
            public String execute(com.chua.common.support.task.pipeline.core.PipelineContext<?> context) {
                return original.execute(context);
            }

            /** 返回任务注册的参数表。 */
            @Override
            public Map<String, Object> getParams() {
                return params;
            }
        };
        return this;
    }

    /**
            * 设置节点环境参数（运行时环境配置，如模型路径、阈值等）。
            *
            * <p>环境参数与 {@link #params(Map)} 的区别：</p>
            * <ul>
            *   <li><strong>params</strong> — JSON 构建时传入的静态参数，执行时注入到 {@code ctx.nodeLocalData}</li>
            *   <li><strong>env</strong> — 节点定义时配置的运行时环境参数，存储在节点自身的 env 属性中，
            *       执行时通过 {@code ctx.getNodeLocalValue("env")} 或 {@code node.getEnv()} 获取，
            *       适用于模型路径、阈值等需要根据上下文动态判断的环境配置</li>
            * </ul>
            *
            * <p>用法示例：</p>
            * <pre>{@code
            * .taskStart("ocr", ctx -> {
            *     String modelPath = ctx.getNodeLocalValue("env.modelPath");
            *     double threshold = ctx.getNodeLocalValue("env.threshold");
            *     // 根据环境参数执行不同逻辑
            *     return null;
            * })
            * .env(Map.of("modelPath", "/models/ocr-v3.onnx", "threshold", 0.85))
            * .taskEnd()
            * }</pre>路径", "/模型/ocr-v3.onnx", "阈值", 0.85))
            * .任务结束()
            * }</pre>
            *
            * @param env 环境参数映射
            * @return this
            */
    public TaskDefinition env(Map<String, Object> env) {
        this.env = env;
        return this;
    }

    /**
    * 设置节点环境参数（单个键值对）。
    *
    * <p>等价于先创建 Map 再调用 {@link #env(Map)}，适用于少量参数的场景。</p>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * .taskStart("ocr", ctx -> { ...; return null; })
    * .env("modelPath", "/models/ocr-v3.onnx")
    * .env("threshold", 0.85)
    * .taskEnd()
    * }</pre>  * }</pre>
    *
    * @param key   参数键
    * @param value 参数值
    * @return this
    */
    public TaskDefinition env(String key, Object value) {
        if (this.env == null) {
            this.env = new LinkedHashMap<>();
        }
        this.env.put(key, value);
        return this;
    }

    /**
    * 包装 处理器：执行后设置 EXIT 动作。
    * @param original 原始
    * @return wrapwith结束的结果
    */
    private static PipelineNode wrapWithEnd(PipelineNode original) {
        return ctx -> {
            String result = original.execute(ctx);
            ctx.setAction(Action.EXIT);
            return null;
        };
    }
}
