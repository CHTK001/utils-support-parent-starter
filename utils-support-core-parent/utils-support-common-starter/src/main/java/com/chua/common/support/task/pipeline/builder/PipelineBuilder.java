package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.callback.LoggingListener;
import com.chua.common.support.task.pipeline.callback.PipelineListener;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.core.PipelineWal;
import com.chua.common.support.task.pipeline.core.RouteStrategy;
import com.chua.common.support.task.pipeline.node.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * 流水线构建器。
 *
 * <p>链式 API 构建流水线，支持添加执行节点、判断节点、子流水线节点，以及注册全局回调。</p>
 *
 * <p>所有节点统一使用 {@link PipelineNode} 函数式接口，无需类型强转：</p>
 * <ul>
 *   <li>{@code .task("id", ctx -> { doWork(ctx); return null; })} — 顺序执行</li>
 *   <li>{@code .task("id", ctx -> condition ? "nodeA" : "nodeB")} — 动态路由</li>
 *   <li>{@code .decision("id", ctx -> ctx.getData() != null ? "yes" : "no")} — 条件分支</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 顺序执行 + 条件分支
 * Pipeline pipeline = PipelineBuilder.newBuilder("order")
 *     .task("validate", ctx -> {
 *         validate(ctx.getCurrentData());
 *         return null;  // 按默认顺序执行
 *     }).taskEnd()
 *     .decision("check", ctx -> ctx.getCurrentData() != null ? "process" : "error")
 *     .task("process", ctx -> {
 *         process(ctx.getCurrentData());
 *         return null;
 *     }).taskEnd()
 *     .task("error", ctx -> {
 *         log.error("invalid data");
 *         return null;
 *     }).taskEnd()
 *     .addListener(new LoggingListener())
 *     .build();
 *
 * // 动态路由
 * Pipeline pipeline = PipelineBuilder.newBuilder("router")
 *     .task("process", ctx -> {
 *         doProcess(ctx.getCurrentData());
 *         return "validate";  // 跳转到 validate 节点
 *     }).taskEnd()
 *     .task("validate", ctx -> {
 *         validate(ctx.getCurrentData());
 *         return null;
 *     }).taskEnd()
 *     .build();
 *
 * // 便捷回调
 * Pipeline pipeline = PipelineBuilder.newBuilder("flow")
 *     .logging()                          // 启用日志
 *     .onStart(ctx -> log.info("start"))  // 启动回调
 *     .onComplete(ctx -> log.info("done"))// 完成回调
 *     .onError((ctx, e) -> {              // 异常回调（支持错误恢复路由）
 *         log.error("node {} failed", ctx.getCurrentNodeId(), e);
 *         return "error-handler";         // 返回恢复节点 ID，null 则终止
 *     })
 *     .task("step1", ctx -> { doStep1(ctx); return null; }).taskEnd()
 *     .task("step2", ctx -> { doStep2(ctx); return null; }).taskEnd()
 *     .build();
 *
 * // JSON 构建
 * Pipeline pipeline = PipelineBuilder.fromJson(jsonString).build();
 * }</pre>e pipeline = pipeline构建器.从json(json字符串).构建();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
*/
public class PipelineBuilder {

    /**
    * 流水线唯一标识
    */
    private final String id;

    /**
    * 按添加顺序排列的节点列表
    */
    private final List<PipelineNode> nodes;

    /**
    * 全局回调监听器列表
    */
    private final List<PipelineListener> listeners;

    /**
    * 节点 标识 -> 节点实例的映射
    */
    private final Map<String, PipelineNode> nodeMap;

    /**
    * 起始节点 标识
    */
    private String startNodeId;

    /**
    * 终止节点 标识
    */
    private String endNodeId;

    /**
    * 是否已构建，防止重复调用 构建()
    */
    private boolean built;

    /**
    * 路由策略：当目标节点不存在时的处理方式
    */
    private RouteStrategy routeStrategy;

    /**
    * WAL 持久化目录，空 表示不启用 WAL
    */
    private String walDir;

    /**
    * 私有构造器。
    *
    * @param id 流水线唯一标识
    */
    private PipelineBuilder(String id) {
        this.id = id;
        this.nodes = new ArrayList<>();
        this.listeners = new ArrayList<>();
        this.nodeMap = new LinkedHashMap<>();
        this.startNodeId = null;
        this.endNodeId = null;
    }

    /**
    * 创建流水线构建器，自动生成流水线 标识。
    *
    * @return PipelineBuilder
    */
    public static PipelineBuilder newBuilder() {
        return newBuilder("pipeline-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
    * 创建流水线构建器。
    *
    * @param id 流水线唯一标识
    * @return PipelineBuilder
    */
    public static PipelineBuilder newBuilder(String id) {
        return new PipelineBuilder(id);
    }

    /**
    * 从 JSON 字符串解析并创建流水线构建器。
    *
    * <p>JSON 格式详见 {@link PipelineJsonParser} 类注释。</p>
    *
    * @param json JSON 字符串
    * @return PipelineBuilder
    * @see PipelineJsonParser
    */
    public static PipelineBuilder fromJson(String json) {
        return PipelineJsonParser.parse(json);
    }

    /**
    * 添加执行节点（Definition API）。
    *
    * <p>返回 {@link TaskDefinition}，支持类型安全的链式配置：</p>
    * <ul>
    *   <li>{@code .task("id", handler).taskEnd()} — 等价于旧版 task()，完成定义返回 builder</li>
    *   <li>{@code .task("id", handler).end().taskEnd()} — 执行后终止流水线</li>
    *   <li>{@code .task("id", handler).start().taskEnd()} — 标记为起始节点</li>
    *   <li>{@code .task("id", handler).decision().branch("yes","process").taskEnd()} — 转为判断节点</li>
    *   <li>{@code .task("id", handler).subPipeline(sub).taskEnd()} — 转为子流水线节点</li>
    * </ul>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * // 顺序执行
    * .task("validate", ctx -> {
    *     validate(ctx.getCurrentData());
    *     return null;
    * }).taskEnd()
    *
    * // 执行后终止
    * .task("finalize", ctx -> {
    *     finalize(ctx.getCurrentData());
    *     return null;
    * }).end().taskEnd()
    *
    * // 条件分支
    * .task("check", ctx -> ctx.getData() != null ? "yes" : "no")
    *     .decision()
    *     .branch("yes", "process")
    *     .branch("no", "error")
    *     .taskEnd()
    * }</pre>")
    * .任务结束()
    * }</pre>
    *
    * @param id      节点唯一标识
    * @param handler 业务逻辑处理器，返回 空 按默认顺序执行，返回节点 标识 则跳转
    * @return TaskDefinition 任务节点定义
    */
    public TaskDefinition task(String id, PipelineNode handler) {
        return new TaskDefinition(id, handler, this);
    }

    /**
    * 添加执行节点（语义化别名，与 {@link #task(String, PipelineNode)} 一致）。
    *
    * <p>语义上表示"开始一个任务定义"，与 {@link TaskDefinition#taskEnd()} 配对使用：</p>
    * <pre>{@code
    * .taskStart("step1", ctx -> { doStep1(ctx); return null; }).taskEnd()
    * }</pre>  * }</pre>
    *
    * @param id      节点唯一标识
    * @param handler 业务逻辑处理器
    * @return TaskDefinition 任务节点定义
    */
    public TaskDefinition taskStart(String id, PipelineNode handler) {
        return task(id, handler);
    }

    /**
    * 添加执行节点（无 处理器 模式，配合 onstep/step 使用）。
    *
    * <p>创建一个空 handler 的任务定义，后续通过 {@link TaskDefinition#onStep}、
    * {@link TaskDefinition#step} 等便捷方法设置业务逻辑：</p>
    * <pre>{@code
    * // 使用 onStep（无返回值）
    * .taskStart("init")
    * .onStep(ctx -> init(ctx))
    * .taskEnd()
    *
    * // 使用 step（有返回值，可路由）
    * .taskStart("route")
    * .step(ctx -> condition ? "nodeA" : "nodeB")
    * .taskEnd()
    *
    * // 使用 ext（执行后终止）
    * .taskStart("finalize")
    * .onStep(ctx -> cleanup(ctx))
    * .exit()
    * .taskEnd()
    * }</pre>* .taskEnd()
    * }</pre>
    *
    * @param id 节点唯一标识
    * @return TaskDefinition 任务节点定义（处理器 为空实现）
    */
    public TaskDefinition taskStart(String id) {
        return new TaskDefinition(id, ctx -> null, this);
    }

    /**
    * 添加执行节点（无 处理器 模式，配合 onstep/step 使用）。
    *
    * <p>等价于 {@link #taskStart(String)}，提供更简洁的调用方式：</p>
    * <pre>{@code
    * // task(id) + onStep（无返回值）
    * .task("init")
    * .onStep(ctx -> init(ctx))
    * .taskEnd()
    *
    * // task(id) + step（有返回值，可路由）
    * .task("route")
    * .step(ctx -> condition ? "nodeA" : "nodeB")
    * .taskEnd()
    *
    * // task(id) + onStep + ext（执行后终止）
    * .task("finalize")
    * .onStep(ctx -> cleanup(ctx))
    * .exit()
    * .taskEnd()
    * }</pre>skEnd()
    * }</pre>
    *
    * @param id 节点唯一标识
    * @return TaskDefinition 任务节点定义（处理器 为空实现）
    */
    public TaskDefinition task(String id) {
        return taskStart(id);
    }

    /**
    * 添加判断节点。
    *
    * <p>统一使用 {@link PipelineNode} 函数式接口，路由回调返回目标节点 ID：</p>
    * <ul>
    *   <li>返回节点 ID — 跳转到指定节点</li>
    *   <li>返回 {@code null} — 按默认顺序继续执行</li>
    * </ul>
    *
    * <p>用法示例：</p>
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
    * }</pre>eB";
    *         default: return "defaultNode";
    *     }
    * })
    * }</pre>
    *
    * @param id     节点唯一标识
    * @param router 路由处理器，返回目标节点 标识；返回 空 表示按默认顺序执行
    * @return this
    */
    public PipelineBuilder decision(String id, PipelineNode router) {
        DecisionNode node = new DecisionNode(id, router);
        nodes.add(node);
        nodeMap.put(id, node);
        return this;
    }

    /**
    * 添加判断节点（Definition API，无 处理器 模式）。
    *
    * <p>返回 {@link TaskDefinition}，配合 {@link TaskDefinition#onStep}、
    * {@link TaskDefinition#step} 等便捷方法使用：</p>
    * <pre>{@code
    * // 无 handler 模式 + step（有返回值，可路由）
    * .decision("check")
    *     .step(ctx -> condition ? "yes" : "no")
    *     .decision()
    *     .branch("yes", "processNode")
    *     .branch("no", "errorNode")
    *     .taskEnd()
    *
    * // 无 handler 模式 + onStep（无返回值，需在 onStep 内路由）
    * .decision("route")
    *     .onStep(ctx -> {
    *         String target = determineTarget(ctx);
    *         ctx.setNextNodeId(target);
    *     })
    *     .taskEnd()
    * }</pre>   ctx.setNextNodeId(target);
    *     })
    * .任务结束()
    * }</pre>
    *
    * @param id 节点唯一标识
    * @return TaskDefinition 任务节点定义（处理器 为空实现，需配合 step/onstep 使用）
    */
    public TaskDefinition decision(String id) {
        return new TaskDefinition(id, ctx -> null, this);
    }

    /**
    * 添加子流水线节点。
    *
    * @param id          节点唯一标识
    * @param subPipeline 子流水线实例
    * @return this
    */
    public PipelineBuilder pipeline(String id, Pipeline subPipeline) {
        SubPipelineNode node = new SubPipelineNode(id, subPipeline);
        nodes.add(node);
        nodeMap.put(id, node);
        return this;
    }

    /**
    * 添加子流水线节点（Definition API）。
    *
    * <p>返回 {@link TaskSubPipelineDefinition}，支持类型安全的子流水线配置。
    * 与 {@link #pipeline(String, Pipeline)} 的区别：</p>
    * <ul>
    *   <li>{@link #pipeline(String, Pipeline)} — 直接添加节点，返回 builder（简单场景）</li>
    *   <li>{@link #subPipeline(String, Pipeline)} — 返回 Definition，支持链式配置 start/params/env 等</li>
    * </ul>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * Pipeline sub = PipelineBuilder.newBuilder("subFlow")
    *     .task("s1", ctx -> { ...; return null; }).taskEnd()
    *     .build();
    *
    * PipelineBuilder.newBuilder("mainFlow")
    *     .subPipeline("process", sub)      // Definition API
    *         .start("s1")                  // 指定子流水线起始节点
    *         .params(Map.of("key", "val")) // 设置参数
    *         .env("modelPath", "/models")  // 设置环境参数
    *     .taskEnd()
    *     .build();
    * }</pre> * .构建();
    * }</pre>
    *
    * @param id          节点唯一标识
    * @param subPipeline 子流水线实例
    * @return TaskSubPipelineDefinition 子流水线节点定义
    * @see TaskSubPipelineDefinition
    */
    public TaskSubPipelineDefinition subPipeline(String id, Pipeline subPipeline) {
        return new TaskSubPipelineDefinition(id, this, subPipeline);
    }

    /**
    * 添加并行子流水线节点（Definition API）。
    *
    * <p>返回 {@link TaskParallelDefinition}，支持类型安全的并行子流水线配置。
    * 并行子流水线在后台线程执行，不阻塞主流水线。</p>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * Pipeline parallelSub = PipelineBuilder.newBuilder("parallelSub")
    *     .task("a1", ctx -> { ...; return null; }).taskEnd()
    *     .build();
    *
    * PipelineBuilder.newBuilder("mainFlow")
    *     .parallel("bgTask", parallelSub)          // Definition API
    *         .onComplete((ctx, result) -> {         // 完成回调
    *             log.info("Parallel completed: {}", result.getOutput());
    *         })
    *         .env("modelPath", "/models")           // 设置环境参数
    *     .taskEnd()
    *     .build();
    * }</pre>束()
    * .构建();
    * }</pre>
    *
    * @param id          节点唯一标识
    * @param subPipeline 并行子流水线实例
    * @return TaskParallelDefinition 并行子流水线节点定义
    * @see TaskParallelDefinition
    * @see com.chua.common.support.task.pipeline.node.ParallelNode
    */
    public TaskParallelDefinition parallel(String id, Pipeline subPipeline) {
        return new TaskParallelDefinition(id, this, subPipeline);
    }

    /**
    * 添加分叉节点（Definition API）。
    *
    * <p>返回 {@link TaskForkDefinition}，支持类型安全的分叉分支配置。</p>
    *
    * <p>分叉节点对外是一个同步节点 — 父流水线阻塞等待所有分支完成后才继续。
    * 各分支通过独立上下文并发执行，结果存入 {@code nodeOutputs}。</p>
    *
    * <p><strong>方式1：内联定义分支（推荐）</strong></p>
    * <pre>{@code
    * Pipeline pipeline = PipelineBuilder.newBuilder("main")
    *     .fork("group")                            // 开始分叉定义
    *         .startFork("a")                       // 内联定义分支 "a"
    *             .step("a1", ctx -> { doA1(ctx); return null; })
    *             .step("a2", ctx -> { doA2(ctx); return null; })
    *         .endFork()                            // 结束分支 "a"
    *         .startFork("b")                       // 内联定义分支 "b"
    *             .step("b1", ctx -> { doB1(ctx); return null; })
    *         .endFork()                            // 结束分支 "b"
    *     .taskEnd()                                // 结束分叉定义
    *     .build();
    * }</pre>    // 结束分叉定义
    * .构建();
    * }</pre>
    *
    * <p><strong>方式2：预构建 Pipeline 传入</strong></p>
    * <pre>{@code
    * Pipeline branchA = PipelineBuilder.newBuilder("branchA")
    *     .task("a1", ctx -> { doA1(ctx); return null; }).taskEnd()
    *     .build();
    *
    * Pipeline pipeline = PipelineBuilder.newBuilder("main")
    *     .fork("group")
    *         .branch("a", branchA)
    *         .branch("b", branchB)
    *     .taskEnd()
    *     .build();
    * }</pre>      * .构建();
    * }</pre>
    *
    * @param id 节点唯一标识
    * @return TaskForkDefinition 分叉节点定义
    * @see TaskForkDefinition#startFork(String)
    * @see TaskForkDefinition#endFork()
    * @see ForkBranchBuilder
    * @see com.chua.common.support.task.pipeline.node.ForkNode
    */
    public TaskForkDefinition fork(String id) {
        return new TaskForkDefinition(id, this);
    }

    /**
    * 注册全局回调监听器。
    *
    * @param listener 监听器实例
    * @return this
    */
    public PipelineBuilder addListener(PipelineListener listener) {
        this.listeners.add(listener);
        return this;
    }

    /**
    * 启用日志监听器（便捷方法）。
    *
    * <p>等价于 {@code addListener(new LoggingListener())}，一行代码启用流水线日志。</p>
    *
    * <p>日志级别：节点执行前/后 FINE，完成 INFO，异常 SEVERE。</p>
    *
    * @return this
    */
    public PipelineBuilder logging() {
        this.listeners.add(new LoggingListener());
        return this;
    }

    /**
    * 注册流水线启动回调（便捷方法）。
    *
    * <p>在第一个节点执行前触发，仅触发一次。</p>
    *
    * @param onStart 启动回调
    * @return this
    */
    public PipelineBuilder onStart(Consumer<PipelineContext<?>> onStart) {
        this.listeners.add(new PipelineListener() {
            @Override
            public void onStart(PipelineContext<?> ctx) {
                onStart.accept(ctx);
            }
        });
        return this;
    }

    /**
    * 注册流水线完成回调（便捷方法）。
    *
    * <p>流水线正常执行完毕时触发（到达终止节点或 action=EXIT）。</p>
    *
    * @param onComplete 完成回调
    * @return this
    */
    public PipelineBuilder onComplete(Consumer<PipelineContext<?>> onComplete) {
        this.listeners.add(new PipelineListener() {
            @Override
            public void onComplete(PipelineContext<?> ctx) {
                onComplete.accept(ctx);
            }
        });
        return this;
    }

    /**
    * 注册节点切换回调（便捷方法）。
    *
    * <p>每完成一个节点并确定下一节点时触发，可用于监控执行进度。</p>
    *
    * @param onNextStep 回调函数，参数为 (上下文, 当前节点标识, 下一个节点标识)
    * @return this
    */
    public PipelineBuilder onNextStep(java.util.function.BiConsumer<PipelineContext<?>, String[]> onNextStep) {
        this.listeners.add(new PipelineListener() {
            @Override
            public void afterNode(PipelineContext<?> ctx) {
                onNextStep.accept(ctx, new String[]{ctx.getCurrentNodeId(), ctx.getNextNodeId()});
            }
        });
        return this;
    }

    /**
    * 注册节点绘制回调（便捷方法）。
    *
    * <p>每个节点执行完毕后触发，可用于实时刷新管线拓扑树。回调内可调用
    * {@link Pipeline#printTree(List, boolean)} 输出带执行标记的拓扑树。</p>
    *
    * <p>与 {@code .addListener(afterNode)} 的区别：{@code onDraw} 语义明确，
    * 专用于可视化绘制场景，不与监控/日志等通用回调混用。</p>
    *
    * <p>用法示例 — 终端实时刷新管线树：</p>
    * <pre>{@code
    * Pipeline pipeline = PipelineBuilder.newBuilder("demo")
    *     .onDraw(ctx -> {
    *         // drawTree: 原地刷新模式 — ANSI 上移光标 + 重绘，同一棵树实时更新
    *         pipeline.drawTree(ctx.getHistory(), true);
    *     })
    *     .task("step1", ctx -> { doWork(ctx); return null; }).taskEnd()
    *     .task("step2", ctx -> { doMore(ctx); return null; }).taskEnd()
    *     .build();
    * }</pre>).任务结束()
    * .构建();
    * }</pre>
    *
    * @param onDraw 绘制回调，参数为当前流水线上下文
    * @return this
    */
    public PipelineBuilder onDraw(Consumer<PipelineContext<?>> onDraw) {
        this.listeners.add(new PipelineListener() {
            @Override
            public void onDraw(PipelineContext<?> ctx) {
                onDraw.accept(ctx);
            }
        });
        return this;
    }

    /**
    * 注册节点异常回调（便捷方法）。
    *
    * <p>当节点执行抛出异常时触发。回调返回值决定流水线后续行为：</p>
    * <ul>
    *   <li><strong>返回节点 ID</strong> — 引擎路由到该节点继续执行（错误恢复路由），
    *       异常已存入 {@code ctx.getLastError()}，恢复节点可据此做条件判断</li>
    *   <li><strong>返回 null</strong> — 终止流水线，抛出 {@link com.chua.common.support.task.pipeline.exception.PipelineException}</li>
    * </ul>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * Pipeline pipeline = PipelineBuilder.newBuilder("flow")
    *     .onError((ctx, e) -> {
    *         log.error("Node {} failed", ctx.getCurrentNodeId(), e);
    *         return "error-handler";  // 路由到错误处理节点
    *     })
    *     .task("step1", ctx -> { doStep1(ctx); return null; }).taskEnd()
    *     .task("error-handler", ctx -> {
    *         Throwable err = ctx.getLastError();
    *         // 处理错误...
    *         ctx.clearLastError();
    *         return null;
    *     }).taskEnd()
    *     .build();
    * }</pre>)
    * .构建();
    * }</pre>
    *
    * @param onError 异常回调函数，参数为 (上下文, 异常)，返回恢复节点 标识 或 空
    * @return this
    */
    public PipelineBuilder onError(BiFunction<PipelineContext<?>, Throwable, String> onError) {
        this.listeners.add(new PipelineListener() {
            @Override
            public String onError(PipelineContext<?> ctx, Throwable e) {
                return onError.apply(ctx, e);
            }
        });
        return this;
    }

    /**
    * 指定起始节点 标识。
    *
    * <p>不指定时默认以第一个添加的节点作为起始节点。</p>
    *
    * @param id 起始节点 标识
    * @return this
    */
    public PipelineBuilder start(String id) {
        this.startNodeId = id;
        return this;
    }

    /**
    * 指定终止节点 标识。
    *
    * @param id 终止节点 标识
    * @return this
    */
    public PipelineBuilder end(String id) {
        this.endNodeId = id;
        return this;
    }

    /**
    * 配置路由策略：当目标节点不存在时的处理方式。
    *
    * <p>默认为 {@link RouteStrategy#THROW}（抛出异常）。</p>
    *
    * <p><strong>策略说明：</strong></p>
    * <ul>
    *   <li>{@link RouteStrategy#THROW} — 抛出 PipelineException（默认，最安全）</li>
    *   <li>{@link RouteStrategy#EXIT} — 优雅终止流水线，触发 onComplete 回调</li>
    *   <li>{@link RouteStrategy#NEXT} — 跳过不存在的节点，按定义顺序继续执行下一个</li>
    * </ul>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * // 动态路由场景：某些分支可能不存在
    * Pipeline pipeline = PipelineBuilder.newBuilder("flow")
    *     .routeStrategy(RouteStrategy.NEXT)
    *     .decision("route", ctx -> ctx.getAttribute("type"))
    *     .task("typeA", ctx -> null).taskEnd()
    *     .task("fallback", ctx -> null).taskEnd()
    *     .build();
    * }</pre>ctx -> null).taskEnd()
    *     .build();
    * }</pre>
    *
    * @param strategy 路由策略
    * @return this
    */
    public PipelineBuilder routeStrategy(RouteStrategy strategy) {
        this.routeStrategy = strategy;
        return this;
    }

    /**
    * 启用 WAL 持久化，指定 WAL 日志存储目录。
    * <p>
    * 启用后，Pipeline 执行过程中的上下文快照将被持久化到 WAL 日志，
    * 支持通过 {@link Pipeline#resume(Object)} 从断点恢复执行。
    * </p>
    *
    * @param walDir WAL 日志目录
    * @return this
    */
    public PipelineBuilder wal(String walDir) {
        this.walDir = walDir;
        return this;
    }

    /**
    * 添加节点（供其他构建器内部使用）。
    *
    * @param node 节点实例
    */
    void addNode(PipelineNode node) {
        if (node instanceof StartNode) {
            StartNode sn = (StartNode) node;
            nodes.add(sn);
            nodeMap.put(sn.getId(), sn);
        }
    }

    /**
    * 添加节点（供 Definition 类内部使用）。
    *
    * <p>包级私有方法，由 {@link TaskDefinition#taskEnd()}、
    * {@link TaskDecisionDefinition#taskEnd()}、{@link TaskSubPipelineDefinition#taskEnd()}
    * 调用，将配置完成的节点添加到流水线。</p>
    *
    * @param node 节点实例
    */
    void addNodeInternal(PipelineNode node) {
        String nid = DefaultPipeline.nodeId(node);
        nodes.add(node);
        nodeMap.put(nid, node);
    }

    /**
    * 构建流水线。
    *
    * <p>构建时执行以下验证：</p>
    * <ul>
    *   <li>至少定义一个节点</li>
    *   <li>起始节点 ID 必须存在于已注册的节点中</li>
    *   <li>终止节点 ID（如果指定）必须存在于已注册的节点中</li>
    *   <li>判断节点的分支目标必须存在于已注册的节点中</li>
    *   <li>子流水线节点的子流水线不能为 null</li>
    * </ul>
    *
    * @return 构建完成的 Pipeline 实例
    * @throws IllegalStateException 当验证失败或重复构建时抛出
    */
    public Pipeline build() {
        if (built) {
            throw new IllegalStateException("Pipeline already built");
        }
        built = true;

        if (nodeMap.isEmpty()) {
            throw new IllegalStateException("No nodes defined");
        }

        // 验证起始节点
        if (startNodeId == null) {
            startNodeId = nodeMap.keySet().iterator().next();
        } else if (!nodeMap.containsKey(startNodeId)) {
            throw new IllegalStateException(
                    "Start node not found: '" + startNodeId
                            + "'. Available nodes: " + nodeMap.keySet());
        }

        // 验证终止节点
        if (endNodeId != null && !nodeMap.containsKey(endNodeId)) {
            throw new IllegalStateException(
                    "End node not found: '" + endNodeId
                            + "'. Available nodes: " + nodeMap.keySet());
        }

        // 验证节点引用完整性
        for (PipelineNode node : nodes) {
            String nid = DefaultPipeline.nodeId(node);

            if (node instanceof DecisionNode) {
                DecisionNode dn = (DecisionNode) node;
                Map<String, String> branches = dn.getBranches();
                for (Map.Entry<String, String> entry : branches.entrySet()) {
                    String targetId = entry.getValue();
                    if (targetId != null && !targetId.isEmpty() && !nodeMap.containsKey(targetId)) {
                        throw new IllegalStateException(
                                "Decision node '" + nid + "' branch '" + entry.getKey()
                                        + "' references undefined node: '" + targetId
                                        + "'. Available nodes: " + nodeMap.keySet());
                    }
                }
            }

            if (node instanceof SubPipelineNode) {
                SubPipelineNode sn = (SubPipelineNode) node;
                try {
                    sn.getSubPipelineId();
                } catch (NullPointerException e) {
                    throw new IllegalStateException(
                            "SubPipeline node '" + nid + "' has null sub-pipeline");
                }
            }
        }

        RouteStrategy effectiveStrategy = routeStrategy != null ? routeStrategy : RouteStrategy.THROW;
        PipelineWal effectiveWal = null;
        if (walDir != null) {
            effectiveWal = new PipelineWal(id, walDir);
        }
        return new DefaultPipeline(id, startNodeId, endNodeId, nodeMap, nodes, listeners, effectiveStrategy, effectiveWal);
    }

    /**
    * 构建流水线（语义化别名）。
    *
    * <p>等价于 {@link #build()}，提供更语义化的命名，与 Definition 类的
    * {@code pipelineEnd()} 配对使用，使流水线定义的结束更加清晰。</p>
    *
    * <p>用法示例：</p>
    * <pre>{@code
    * // 方式1：Definition 的 pipelineEnd()（推荐，一步完成）
    * .taskStart("done", handler).pipelineEnd()
    *
    * // 方式2：Builder 的 pipelineEnd()
    * .taskStart("done", handler).taskEnd()
    * .pipelineEnd()
    * }</pre>lineEnd()
    * }</pre>
    *
    * @return 构建完成的 Pipeline 实例
    * @throws IllegalStateException 当验证失败或重复构建时抛出
    */
    public Pipeline pipelineEnd() {
        return build();
    }

    // ========== Getter（供 JSON 解析器使用） ==========

    /**
    * 获取流水线 标识。
    *
    * @return 流水线 标识
    */
    public String getId() {
        return id;
    }

    /**
    * 获取节点映射。
    *
    * @return 节点 标识 -> 节点实例的映射
    */
    Map<String, PipelineNode> getNodeMap() {
        return nodeMap;
    }

    /**
    * 获取按添加顺序排列的节点列表。
    *
    * @return 节点列表
    */
    List<PipelineNode> getNodes() {
        return nodes;
    }

    /**
    * 获取起始节点 标识。
    *
    * @return 起始节点 标识，未指定时返回 空
    */
    String getStartNodeId() {
        return startNodeId;
    }

    /**
    * 获取终止节点 标识。
    *
    * @return 终止节点 标识，未指定时返回 空
    */
    String getEndNodeId() {
        return endNodeId;
    }
}
