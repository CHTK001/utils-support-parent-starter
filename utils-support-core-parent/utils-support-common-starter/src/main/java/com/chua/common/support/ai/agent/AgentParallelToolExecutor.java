package com.chua.common.support.ai.agent;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Agent 工具并行执行器契约。
 *
 * <p>一轮模型响应可能携带多个工具调用；其中「无副作用」的调用（只读类）可以并行
 * 执行以缩短时延。是否可并行由 {@link AgentToolSafetyPolicy} 判定，本执行器只负责
 * 「把一批任务并发跑完并按提交顺序返回结果」。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>结果顺序与任务顺序一一对应（按索引对位）；</li>
 *   <li>任一任务抛出异常时整体失败：包装为 {@link AgentToolExecutionException}
 *       抛出，由调用方决定降级（如退化为串行重试）；</li>
 *   <li>实现必须线程安全，可被多个运行共享。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentParallelToolExecutor extends AutoCloseable {

    /**
     * 并发执行一批任务，按提交顺序返回结果。
     *
     * @param tasks 任务列表，不能为 null；空列表返回空结果
     * @param <T>   单个任务结果类型
     * @return 与任务顺序对位的结果列表
     * @throws AgentToolExecutionException 任一任务失败时
     */
    <T> List<T> execute(List<Callable<T>> tasks);

    /**
     * 释放底层线程资源。
     *
     * <p>共享线程池实现可保留线程不释放（进程退出时由 JVM 回收）；
     * 独占池实现应在此关闭。</p>
     */
    @Override
    default void close() {
    }

    /**
     * 创建默认并行执行器。
     *
     * @param parallelism 最大并行度，非正数回退为 CPU 核数
     * @return 并行执行器实例
     */
    static AgentParallelToolExecutor of(int parallelism) {
        return new SimpleAgentParallelToolExecutor(parallelism);
    }
}
