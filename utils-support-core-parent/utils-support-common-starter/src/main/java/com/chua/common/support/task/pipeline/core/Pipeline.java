package com.chua.common.support.task.pipeline.core;

import java.util.List;

/**
 * 流水线入口接口。
 *
 * <p>流水线的核心抽象，定义了一条可执行的节点链路。通过 {@link PipelineBuilder} 构建，
 * 支持线性执行、条件分支、子流程嵌套、动作控制等能力。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * Pipeline pipeline = PipelineBuilder.newBuilder("demo")
 *     .task("step1", ctx -> { })
 *     .decision("check", ctx -> ctx.getCurrentData() != null)
 *         .when(true, "step2")
 *         .when(false, "end")
 *         .then()
 *     .task("step2", ctx -> { })
 *     .build();
 *
 * PipelineContext<String> ctx = pipeline.execute("input");
 * pipeline.printTree(ctx.getHistory());
 * }</pre>
 *
 * @author CH
 */
public interface Pipeline {

    /**
     * 获取流水线唯一标识。
     *
     * @return 流水线 ID
     */
    String getId();

    /**
     * 执行流水线。
     *
     * @param input 输入数据
     * @param <T>   数据类型
     * @return 执行完成后的上下文，包含历史、结果数据等
     */
    <T> PipelineContext<T> execute(T input);

    /**
     * 使用已有上下文执行流水线。
     *
     * <p>复用传入的上下文实例而不重建，满足"运行必定同一个上下文"的编排约束。
     * 默认实现不支持时抛出异常，由支持该能力的实现类覆写。</p>
     *
     * @param existingContext 已存在的上下文实例
     * @param <T>             数据类型
     * @return 执行完成后的上下文
     */
    default <T> PipelineContext<T> execute(PipelineContext<T> existingContext) {
        throw new UnsupportedOperationException("当前流水线实现不支持复用上下文执行");
    }

    /**
     * 恢复执行被 WAIT 挂起的流水线。
     *
     * @param context 之前执行返回的上下文
     * @param <T>     数据类型
     * @return 恢复执行后的上下文
     */
    <T> PipelineContext<T> resume(PipelineContext<T> context);

    /**
     * 打印流水线 B+ 树拓扑结构，不标记已执行节点。
     */
    default void printTree() {
        printTree(null);
    }

    /**
     * 打印流水线 B+ 树拓扑结构，已执行节点用 {@code *} 标记高亮。
     *
     * @param history 已执行节点 ID 列表，传 null 时不标记
     */
    void printTree(List<String> history);
}
