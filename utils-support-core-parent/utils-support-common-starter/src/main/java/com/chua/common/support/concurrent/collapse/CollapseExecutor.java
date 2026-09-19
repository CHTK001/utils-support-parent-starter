package com.chua.common.support.concurrent.collapse;

import java.io.Closeable;
import java.util.Collections;
import java.util.Map;

/**
 * 折叠执行器。
 *
 * <p>将同一时刻内不同线程发起的调用合并为一次批量调用执行，
 * 通过一次批量结果广播或回填，减少下游 I/O 次数、降低服务端线程占用。</p>
 *
 * <p>使用方仅需调用 {@link #execute(Object)} 提交单次调用，由实现根据配置
 * （见 {@link CollapseConfig}）决定何时将并发到达的调用合并为一批，
 * 并通过 {@link CollapseBatchFunction} 执行真实的批量逻辑。</p>
 *
 * @param <INPUT>  单次调用的入参类型
 * @param <OUTPUT> 单次调用的返回类型
 * @author CH
 * @since 2026/09/03
 */
public interface CollapseExecutor<INPUT, OUTPUT> extends Closeable {

    /**
     * 执行一次调用。
     *
     * <p>调用线程会阻塞至本次批量执行完成（阻塞模式）或立即返回结果句柄（异步模式），
     * 具体行为由实现决定。同一批内的多次调用共享一次批量执行。</p>
     *
     * @param input 单次调用的入参
     * @return 单次调用的返回结果
     * @throws Throwable 执行过程中发生的异常，异常同样按组广播
     */
    OUTPUT execute(INPUT input) throws Throwable;

    /**
     * 关闭执行器，释放收集队列等资源。
     */
    @Override
    default void close() {
        // 子类按需实现资源释放
    }

    /**
     * 折叠执行指标。
     *
     * <p>默认实现返回空指标；实现类可按需统计（如 {@code executedCount} 调用次数、
     * {@code batchExecutionCount} 真实执行次数、{@code mergeRate} 合并率等）。</p>
     *
     * @return 指标映射
     */
    default Map<String, Object> metrics() {
        return Collections.emptyMap();
    }
}
