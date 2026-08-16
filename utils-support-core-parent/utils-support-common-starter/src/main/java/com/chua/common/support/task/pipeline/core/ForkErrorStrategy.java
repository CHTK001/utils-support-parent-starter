package com.chua.common.support.task.pipeline.core;

/**
 * 分叉节点错误处理策略。
 *
 * <p>当分叉执行的某个分支抛出异常时，决定如何处理其他分支：</p>
 * <ul>
 *   <li>{@link #WAIT_ALL} — 等待所有分支完成，然后汇总异常（默认）</li>
 *   <li>{@link #FAIL_FAST} — 第一个分支失败时立即取消其他分支并抛出异常</li>
 * </ul>
 *
 * <p><strong>用法示例：</strong></p>
 * <pre>{@code
 * Pipeline branchA = PipelineBuilder.newBuilder("branchA")
 *     .task("a1", ctx -> { doA1(ctx); return null; }).taskEnd()
 *     .build();
 *
 * Pipeline branchB = PipelineBuilder.newBuilder("branchB")
 *     .task("b1", ctx -> { doB1(ctx); return null; }).taskEnd()
 *     .build();
 *
 * Pipeline pipeline = PipelineBuilder.newBuilder("fork-demo")
 *     .fork("fork-group")
 *         .branch("a", branchA)
 *         .branch("b", branchB)
 *         .errorStrategy(ForkErrorStrategy.FAIL_FAST)
 *     .taskEnd()
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see com.chua.common.support.task.pipeline.node.ForkNode
 */
public enum ForkErrorStrategy {

    /**
     * 等待所有分支完成。
     *
     * <p>即使某个分支失败，也等待其余分支执行完毕。
     * 所有分支完成后，若有失败分支则抛出汇总异常。</p>
     *
     * <p>适用场景：需要收集所有分支结果，不希望因单个分支失败而中断其他分支。</p>
     */
    WAIT_ALL,

    /**
     * 快速失败。
     *
     * <p>第一个分支失败时，尝试取消其他尚未完成的分支，并立即抛出异常。
     * 已启动的分支会继续执行完毕（最佳努力取消）。</p>
     *
     * <p>适用场景：分支间有依赖关系，一个失败则其余无意义。</p>
     */
    FAIL_FAST
}
