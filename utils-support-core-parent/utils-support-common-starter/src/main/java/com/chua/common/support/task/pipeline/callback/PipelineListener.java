package com.chua.common.support.task.pipeline.callback;

import com.chua.common.support.task.pipeline.core.PipelineContext;

/**
 * 流水线全局回调监听器。
 *
 * <p>提供流水线执行过程中的生命周期回调，支持在节点执行前后、异常时、完成时
 * 插入自定义逻辑。所有方法均为 default 实现，按需覆盖即可。</p>
 *
 * <p>通过 {@link com.chua.common.support.task.pipeline.builder.PipelineBuilder#addListener(PipelineListener)}
 * 注册到流水线。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * .addListener(new PipelineListener() {
 *     @Override
 *     public void beforeNode(PipelineContext<?> ctx) {
 *         System.out.println("进入节点: " + ctx.getCurrentNodeId());
 *     }
 *
 *     @Override
 *     public void onError(PipelineContext<?> ctx, Throwable e) {
 *         log.error("节点执行失败: " + ctx.getCurrentNodeId(), e);
 *     }
 * })
 * }</pre>
 *
 * @author CH
 */
public interface PipelineListener {

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
     * @param context 当前流水线上下文
     * @param e       异常信息
     */
    default void onError(PipelineContext<?> context, Throwable e) {
    }

    /**
     * 流水线执行完成回调。
     *
     * @param context 当前流水线上下文
     */
    default void onComplete(PipelineContext<?> context) {
    }
}
