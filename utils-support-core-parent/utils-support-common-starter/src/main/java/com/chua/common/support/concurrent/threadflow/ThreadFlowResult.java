package com.chua.common.support.concurrent.threadflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* {@link ThreadFlow} 执行结果。
*
* <p>汇总所有任务的执行状态、成功结果、失败异常等信息。</p>
*
* @param <T> 任务返回值类型
* @author CH
* @since 2026/08/15
 */
public class ThreadFlowResult<T> {

    /**
    * 是否整体成功
    */
    private final boolean success;

    /**
    * 合并后的 Callable 结果列表（按完成顺序）
    */
    private final List<T> results;

    /**
    * 失败异常列表
    */
    private final List<Throwable> errors;

    /**
    * 任务总数
    */
    private final int totalCount;

    /**
    * 成功任务数
    */
    private final int successCount;

    /**
    * 失败任务数
    */
    private final int failCount;

    /**
    * 总耗时（毫秒）
    */
    private final long costMillis;

    /**
    * 触发整体完成的策略
    */
    private final ThreadStrategy strategy;

    /**
    * 创建 ThreadFlowResult 实例
    * @param success success
    * @param results results
    * @param errors errors
    * @param totalCount totalCount
    * @param successCount successCount
    * @param failCount failCount
    * @param costMillis costMillis
    * @param strategy strategy
    */
    public ThreadFlowResult(boolean success, List<T> results, List<Throwable> errors,
                            int totalCount, int successCount, int failCount,
                            long costMillis, ThreadStrategy strategy) {
        this.success = success;
        this.results = List.copyOf(results);
        this.errors = List.copyOf(errors);
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failCount = failCount;
        this.costMillis = costMillis;
        this.strategy = strategy;
    }

    /**
    * 是否整体成功。
    *
    * @return true 成功
    */
    public boolean isSuccess() {
        return success;
    }

    /**
    * 获取合并后的 Callable 结果列表。
    *
    * @return 结果列表
    */
    public List<T> getResults() {
        return results;
    }

    /**
    * 获取失败异常列表。
    *
    * @return 异常列表
    */
    public List<Throwable> getErrors() {
        return errors;
    }

    /**
    * 获取任务总数。
    *
    * @return 任务总数
    */
    public int getTotalCount() {
        return totalCount;
    }

    /**
    * 获取成功任务数。
    *
    * @return 成功任务数
    */
    public int getSuccessCount() {
        return successCount;
    }

    /**
    * 获取失败任务数。
    *
    * @return 失败任务数
    */
    public int getFailCount() {
        return failCount;
    }

    /**
    * 获取总耗时（毫秒）。
    *
    * @return 耗时
    */
    public long getCostMillis() {
        return costMillis;
    }

    /**
    * 获取触发整体完成的策略。
    *
    * @return 策略
    */
    public ThreadStrategy getStrategy() {
        return strategy;
    }

    /**
    * 获取首个失败异常。
    *
    * @return 首个异常，无失败时返回 null
    */
    public Throwable firstError() {
        return errors.isEmpty() ? null : errors.getFirst();
    }

    /**
    * 获取首个成功结果。
    *
    * @return 首个结果，无成功时返回 null
    */
    public T firstResult() {
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
    * 转为 Map 快照，便于日志输出。
    *
    * @return 快照 Map
    */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new ConcurrentHashMap<>(16);
        map.put("success", success);
        map.put("totalCount", totalCount);
        map.put("successCount", successCount);
        map.put("failCount", failCount);
        map.put("costMillis", costMillis);
        map.put("strategy", strategy);
        map.put("resultSize", results.size());
        map.put("errorSize", errors.size());
        return map;
    }
}
