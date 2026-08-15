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
 *     .task("step1", ctx -> { doWork(ctx); return null; }).taskEnd()
 *     .decision("check", ctx -> ctx.getCurrentData() != null ? "step2" : "end")
 *     .task("step2", ctx -> { doMore(ctx); return null; }).taskEnd()
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
     * 恢复执行流水线 — 从 WAL 断点继续（无 WAL 数据时等同于 execute）。
     *
     * <p>恢复流程：</p>
     * <ol>
     *   <li>检查是否存在 WAL 数据</li>
     *   <li>有 WAL 数据 → 回放恢复上下文，从断点继续执行</li>
     *   <li>无 WAL 数据 → 等同于 {@link #execute(Object)}，从头开始执行</li>
     * </ol>
     *
     * @param input 输入数据（无 WAL 数据时作为初始输入）
     * @param <T>   数据类型
     * @return 执行完成后的上下文
     */
    default <T> PipelineContext<T> resume(T input) {
        throw new UnsupportedOperationException("当前流水线实现不支持 resume 恢复");
    }

    /**
     * 终止流水线并销毁 WAL 持久化数据。
     *
     * <p>与正常完成的区别：</p>
     * <ul>
     *   <li>正常完成 — WAL 文件保留（可用于审计）</li>
     *   <li>stop — 强制终止 + 删除 WAL 文件（不留痕迹）</li>
     * </ul>
     */
    default void stop() {
        throw new UnsupportedOperationException("当前流水线实现不支持 stop");
    }

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

    /**
     * 打印流水线 B+ 树拓扑结构，支持颜色和图标标记。
     *
     * <p>启用颜色时，不同节点类型使用不同 ANSI 颜色，已执行节点用 ✓ 标记，
     * 未执行节点用 ○ 标记。禁用颜色时降级为纯文本输出。</p>
     *
     * <p>子流水线和并行分支递归展开，遵循"自己管自己"原则 —
     * 每条 Pipeline 负责打印自己的节点树。</p>
     *
     * @param history      已执行节点 ID 列表，传 null 时不标记
     * @param colorEnabled 是否启用 ANSI 颜色输出
     */
    default void printTree(List<String> history, boolean colorEnabled) {
        printTree(history);
    }
}
