package com.chua.common.support.task.async;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
* 异步提供者接口
*
* <p>定义异步任务执行的规范，提供基于虚拟线程和 CompletableFuture 的异步能力。
* 支持单个任务和批量任务的异步执行。
*
* <p>核心能力：
* <ul>
*   <li><strong>单个异步任务</strong>：执行带返回值或不带返回值的异步任务</li>
*   <li><strong>批量异步任务</strong>：并发执行多个异步任务，汇总所有结果</li>
*   <li><strong>虚拟线程</strong>：底层使用 JDK 虚拟线程实现轻量级并发</li>
* </ul>
*
* @author CH
* @since 1.0.0
 */
public interface AsyncProvider {

    /**
    * 异步执行带返回值的任务
    *
    * @param <T>      返回值类型
    * @param supplier 任务提供者
    * @return 异步计算结果
     */
    <T> CompletableFuture<T> supply(Supplier<T> supplier);

    /**
    * 异步执行无返回值的任务
    *
    * @param runnable 待执行的任务
    * @return 异步计算标志
     */
    CompletableFuture<Void> run(Runnable runnable);

    /**
    * 批量异步执行多个任务并汇总结果
    *
    * @param <T>       返回值类型
    * @param suppliers 任务提供者数组
    * @return 所有任务结果的异步列表
     */
    @SuppressWarnings("unchecked")
    <T> CompletableFuture<List<T>> supplyAll(Supplier<T>... suppliers);

    /**
    * 批量异步执行多个任务并汇总结果
    *
    * @param <T>       返回值类型
    * @param suppliers 任务提供者列表
    * @return 所有任务结果的异步列表
     */
    <T> CompletableFuture<List<T>> supplyAll(List<Supplier<T>> suppliers);
}
