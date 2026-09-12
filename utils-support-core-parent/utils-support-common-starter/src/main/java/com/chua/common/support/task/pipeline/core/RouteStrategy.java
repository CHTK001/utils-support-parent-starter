package com.chua.common.support.task.pipeline.core;

/**
 * 路由策略枚举。
 *
 * <p>定义当目标节点不存在时的处理策略，可通过
 * {@link com.chua.common.support.task.pipeline.builder.PipelineBuilder#routeStrategy(RouteStrategy)}
 * 配置。</p>
 *
 * <p><strong>策略说明：</strong></p>
 * <ul>
 *   <li><strong>THROW</strong> — 抛出 {@link com.chua.common.support.task.pipeline.exception.PipelineException}（默认，最安全）</li>
 *   <li><strong>EXIT</strong> — 优雅终止流水线，触发 onComplete 回调</li>
 *   <li><strong>NEXT</strong> — 跳过不存在的节点，按定义顺序继续执行下一个节点</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * // 默认策略：节点不存在时抛异常
 * Pipeline pipeline = PipelineBuilder.newBuilder("flow")
 *     .task("step1", ctx -> { return "nonExistent"; }).taskEnd()  // 运行时抛异常
 *     .task("step2", ctx -> null).taskEnd()
 *     .build();
 *
 * // EXIT 策略：节点不存在时优雅退出
 * Pipeline pipeline = PipelineBuilder.newBuilder("flow")
 *     .routeStrategy(RouteStrategy.EXIT)
 *     .task("step1", ctx -> { return "nonExistent"; }).taskEnd()  // 优雅终止
 *     .task("step2", ctx -> null).taskEnd()
 *     .build();
 *
 * // NEXT 策略：节点不存在时跳过，继续下一个
 * Pipeline pipeline = PipelineBuilder.newBuilder("flow")
 *     .routeStrategy(RouteStrategy.NEXT)
 *     .task("step1", ctx -> { return "nonExistent"; }).taskEnd()  // 跳过，执行 step2
 *     .task("step2", ctx -> null).taskEnd()
 *     .build();
 * }</pre>* .构建();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum RouteStrategy {

    /**
     * 抛出异常（默认策略）。
     *
     * <p>当目标节点不存在时，抛出 {@link com.chua.common.support.task.pipeline.exception.PipelineException}，
      * 携带节点 标识 和可用节点列表等上下文信息。</p>
     *
     * <p>适用于大多数场景，确保流水线配置的正确性。</p>
     */
    THROW,

    /**
     * 优雅退出。
     *
     * <p>当目标节点不存在时，设置动作为 EXIT，流水线正常终止并触发 onComplete 回调。</p>
     *
     * <p>适用于容错场景，不希望因路由错误而中断整个流程。</p>
     */
    EXIT,

    /**
     * 跳过并继续下一个。
     *
     * <p>当目标节点不存在时，跳过该节点，按定义顺序执行当前节点之后的下一个节点。</p>
     *
     * <p>适用于动态路由场景，某些分支可能不存在但不应影响主流程。</p>
     */
    NEXT
}
