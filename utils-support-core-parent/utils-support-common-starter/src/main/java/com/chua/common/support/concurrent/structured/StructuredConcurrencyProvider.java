package com.chua.common.support.concurrent.structured;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 结构化并发提供者 SPI 接口。
 *
 * <p>定义结构化并发（Structured Concurrency）的核心行为，基于 JDK 21 虚拟线程实现。</p>
 *
 * @author CH
 * @since 2026/07/24
 */
public interface StructuredConcurrencyProvider {

    /**
     * 提交任务。
     *
     * @param <T>  返回值类型
     * @param task 待执行任务
     * @return 任务结果
     * @throws Exception 任务执行异常
     */
    <T> T submit(Callable<T> task) throws Exception;

    /**
     * 提交任务（无返回值）。
     *
     * @param task 待执行任务
     * @throws Exception 任务执行异常
     */
    void submit(Runnable task) throws Exception;

    /**
     * 等待所有任务完成。
     *
     * @throws Exception 等待异常
     */
    void join() throws Exception;

    /**
     * 关闭并等待所有任务完成。
     *
     * @throws Exception 关闭异常
     */
    void close() throws Exception;
}