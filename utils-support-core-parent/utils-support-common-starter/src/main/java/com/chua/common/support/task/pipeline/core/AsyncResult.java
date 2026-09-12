package com.chua.common.support.task.pipeline.core;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
* 异步子流水线节点执行结果。
*
* <p>封装异步子流水线的输出数据，作为结构化结果存入父上下文的 {@code nodeOutputs}。</p>
*
* <p>与 {@link SubPipelineResult} 的关键区别：</p>
* <ul>
*   <li>SubPipelineResult — 同步执行，结果在节点返回时即已完成</li>
*   <li>AsyncResult — 异步执行，结果可能在主流程继续执行后才完成</li>
* </ul>
*
* <p><strong>存储模型：</strong></p>
* <pre>
* nodeOutputs["asyncStep"] = AsyncResult {
*     nodeId: "asyncStep",
*     output: data,               // 异步子流水线的最终输出（完成后可用）
*     history: ["a1", "a2"],      // 异步子流水线的执行历史
*     pipelineId: "asyncSub",     // 异步子流水线 ID
*     completed: true,            // 是否已完成
*     error: null                 // 异常（如果失败）
* }
* </pre>
*
* <p><strong>异步完成回调：</strong></p>
* <p>异步子流水线执行完毕后，结果自动合并到父上下文：</p>
* <ul>
*   <li>{@code nodeOutputs} 更新为完成状态的 AsyncResult</li>
*   <li>{@code currentData} 更新为异步输出（可选，由 mergeCurrentData 控制）</li>
*   <li>触发 completionHandler（如果配置）</li>
* </ul>
*
* <p><strong>用法示例：</strong></p>
* <pre>{@code
* // 获取异步结果
* AsyncResult result = ctx.getData("asyncStep", AsyncResult.class);
*
* // 检查是否完成
* if (result.isCompleted()) {
*     Object data = result.getOutput();
* } else {
*     // 等待完成（阻塞）
*     result.await();
* }
*
* // 非阻塞获取（返回null如果未完成）
* Object data = result.getOutput();
* }</pre>/ 非阻塞获取（返回空如果未完成）
* 对象 数据 = 结果.获取输出();
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see com.chua.common.support.task.pipeline.node.ParallelNode
 */
public class AsyncResult {

    /**
    * 异步子流水线节点 标识
     */
    private final String nodeId;

    /**
    * 异步子流水线的最终输出数据（完成后可用）
     */
    private volatile Object output;

    /**
    * 异步子流水线的执行历史（完成后可用）
     */
    private volatile List<String> history;

    /**
    * 异步子流水线 标识
     */
    private final String pipelineId;

    /**
    * 是否已完成
     */
    private volatile boolean completed;

    /**
    * 异常（如果执行失败）
     */
    private volatile Throwable error;

    /**
    * 完成信号锁 — 异步任务完成时 countdown，await() 等待此锁
     */
    private final CountDownLatch latch;

    /**
    * 构造异步结果（初始未完成状态）。
    *
    * @param nodeId     异步子流水线节点 标识
    * @param pipelineId 异步子流水线 标识
     */
    public AsyncResult(String nodeId, String pipelineId) {
        this.nodeId = nodeId;
        this.pipelineId = pipelineId;
        this.latch = new CountDownLatch(1);
        this.completed = false;
        this.output = null;
        this.history = new ArrayList<>();
        this.error = null;
    }

    /**
    * 标记异步结果为已完成（由引擎内部调用）。
    *
    * @param output 异步子流水线的最终输出
    * @param history 异步子流水线的执行历史
     */
    public void complete(Object output, List<String> history) {
        this.output = output;
        this.history = history != null ? new ArrayList<>(history) : new ArrayList<>();
        this.completed = true;
        this.latch.countDown();
    }

    /**
    * 标记异步结果为失败（由引擎内部调用）。
    *
    * @param error 执行异常
     */
    public void completeWithError(Throwable error) {
        this.error = error;
        this.completed = true;
        this.latch.countDown();
    }

    /**
    * 阻塞等待异步执行完成。
    *
    * <p>如果异步子流水线已完成，立即返回；否则阻塞当前线程直到完成。</p>
    *
    * @return this，支持链式调用
     */
    public AsyncResult await() {
        if (!completed) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for async result: " + nodeId, e);
            }
        }
        return this;
    }

    /**
    * 带超时的阻塞等待异步执行完成。
    *
    * @param timeout 超时时间
    * @param unit    时间单位
    * @return true 如果在超时前完成，false 如果超时
     */
    public boolean await(long timeout, TimeUnit unit) {
        if (completed) {
            return true;
        }
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for async result: " + nodeId, e);
        }
    }

    /**
    * 获取异步子流水线节点 标识。
    *
    * @return 节点 标识
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
    * 获取异步子流水线的最终输出数据。
    *
    * <p>如果异步子流水线尚未完成，返回 null。使用 {@link #await()} 等待完成后再获取。</p>
    *
    * @param <V> 数据值类型
    * @return 输出数据，未完成时返回 空
     */
    @SuppressWarnings("unchecked")
    public <V> V getOutput() {
        return (V) output;
    }

    /**
    * 获取异步子流水线的最终输出数据（带类型转换）。
    *
    * @param type 期望的数据类型
    * @param <V>  数据值类型
    * @return 输出数据，未完成或类型不匹配时返回 空
     */
    @SuppressWarnings("unchecked")
    public <V> V getOutput(Class<V> type) {
        return output != null ? (V) type.cast(output) : null;
    }

    /**
    * 获取异步子流水线的执行历史。
    *
    * @return 执行历史节点ID列表的不可变视图，未完成时返回空列表
     */
    public List<String> getHistory() {
        return history != null ? Collections.unmodifiableList(history) : Collections.emptyList();
    }

    /**
    * 获取异步子流水线 标识。
    *
    * @return 子流水线 标识
     */
    public String getPipelineId() {
        return pipelineId;
    }

    /**
    * 判断异步子流水线是否已完成。
    *
    * @return 已完成时返回 true
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
    * 判断异步子流水线是否执行失败。
    *
    * @return 失败时返回 true
     */
    public boolean isFailed() {
        return completed && error != null;
    }

    /**
    * 获取执行异常。
    *
    * @return 异常，未失败时返回 空
     */
    public Throwable getError() {
        return error;
    }

    /** 返回含 节点id 与状态的调试字符串。 */
    @Override
    public String toString() {
        return "AsyncResult{" +
                "nodeId='" + nodeId + '\'' +
                ", pipelineId='" + pipelineId + '\'' +
                ", completed=" + completed +
                (error != null ? ", error=" + error.getMessage() : "") +
                '}';
    }
}
