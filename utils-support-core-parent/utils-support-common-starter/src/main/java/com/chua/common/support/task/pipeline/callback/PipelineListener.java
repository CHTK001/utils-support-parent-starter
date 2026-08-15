package com.chua.common.support.task.pipeline.callback;

import com.chua.common.support.task.pipeline.core.PipelineContext;

/**
 * 流水线全局回调监听器。
 *
 * <p>提供流水线执行过程中的生命周期回调，支持在流水线启动、节点执行前后、
 * 异常时、完成时插入自定义逻辑。所有方法均为 default 实现，按需覆盖即可。</p>
 *
 * <p>通过 {@link com.chua.common.support.task.pipeline.builder.PipelineBuilder#addListener(PipelineListener)}
 * 注册到流水线，或使用便捷方法 {@code logging()}、{@code onStart()}、{@code onComplete()}、{@code onNextStep()}。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * .addListener(new PipelineListener() {
 *     @Override
 *     public void onStart(PipelineContext<?> ctx) {
 *         System.out.println("Pipeline started: " + ctx.getPipelineId());
 *     }
 *
 *     @Override
 *     public void beforeNode(PipelineContext<?> ctx) {
 *         System.out.println("Enter node: " + ctx.getCurrentNodeId());
 *     }
 *
 *     @Override
 *     public String onError(PipelineContext<?> ctx, Throwable e) {
 *         log.error("Node failed: " + ctx.getCurrentNodeId(), e);
 *         return "error-handler";  // 路由到错误处理节点继续执行
 *         // return null;  // 终止流水线
 *     }
 * })
 * }</pre>
 *
 * @author CH
 */
public interface PipelineListener {

    /**
     * 流水线启动回调。
     *
     * <p>在第一个节点执行前触发，仅触发一次。</p>
     *
     * @param context 流水线上下文
     */
    default void onStart(PipelineContext<?> context) {
    }

    /**
     * 节点执行前回调。
     *
     * @param context 当前流水线上下文
     */
    default void beforeNode(PipelineContext<?> context) {
    }

    /**
     * 节点执行后回调。
     *
     * @param context 当前流水线上下文
     */
    default void afterNode(PipelineContext<?> context) {
    }

    /**
     * 节点执行异常回调。
     *
     * <p>当节点执行抛出异常时触发。返回值用于控制流水线的后续行为：</p>
     * <ul>
     *   <li><strong>返回节点 ID</strong> — 引擎将路由到该节点继续执行（错误恢复路由），
     *       异常已存入 {@code ctx.getLastError()}，恢复节点可据此做条件判断</li>
     *   <li><strong>返回 null</strong> — 终止流水线，抛出 {@link com.chua.common.support.task.pipeline.exception.PipelineException}</li>
     * </ul>
     *
     * <p>默认实现返回 null（终止流水线），与原 void 语义兼容。</p>
     *
     * @param context 当前流水线上下文
     * @param e       异常信息
     * @return 恢复节点 ID（继续执行），或 null（终止流水线）
     */
    default String onError(PipelineContext<?> context, Throwable e) {
        return null;
    }

    /**
     * 流水线执行完成回调。
     *
     * @param context 当前流水线上下文
     */
    default void onComplete(PipelineContext<?> context) {
    }
}
