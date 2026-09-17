package com.chua.common.support.task.scheduler;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Cron 触发器
 *
 * <p>基于 Cron 表达式的触发器实现，支持标准 6 字段 Cron 表达式的解析和匹配。
 * 适用于需要按照固定时间点执行任务的场景，如"每天中午 12 点"、"每周一上午 9 点"等。
 *
 * <p>Cron 表达式格式：{@code 秒 分 时 日 月 周}
 *
 * <p>使用示例：
 * <pre>{@code
 * // 每天 12:00:00 执行
 * CronTrigger trigger = new CronTrigger("0 0 12 * * ?");
 * LocalDateTime next = trigger.nextExecutionTime();
 *
 * // 工作日每 5 分钟执行一次
 * CronTrigger trigger = new CronTrigger("0 0/5 * * * MON-FRI");
 * List<LocalDateTime> times = trigger.getFireTimes(5);
 * }</pre>ireTimes(5);
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
*/
public class CronTrigger implements Trigger {

    /**
    * Cron 表达式原始字符串
    */
    private final String cron;

    /**
    * Cron 表达式解析引擎
    */
    private final CronExpression cronExpression;

    /**
    * 基准时间（当前保留以供扩展使用）
    */
    private final LocalDateTime baseTime;

    /**
    * 根据 Cron 表达式创建触发器
    *
    * @param cron 标准 6 字段 Cron 表达式
    */
    public CronTrigger(String cron) {
        this.cron = cron;
        this.cronExpression = new CronExpression(cron);
        this.baseTime = LocalDateTime.now();
    }

    /**
    * 根据 Cron 表达式和基准时间创建触发器
    *
    * @param cron     标准 6 字段 Cron 表达式
    * @param baseTime 基准时间
    */
    public CronTrigger(String cron, LocalDateTime baseTime) {
        this.cron = cron;
        this.cronExpression = new CronExpression(cron);
        this.baseTime = baseTime;
    }

    /**
    * 计算从当前时间开始的下一次执行时间
    *
    * @return 下一次执行时间点
    */
    @Override
    public LocalDateTime nextExecutionTime() {
        return cronExpression.nextExecutionTime(LocalDateTime.now());
    }

    /**
    * 计算从指定时间开始的下一次执行时间
    *
    * @param from 基准时间点
    * @return 下一次执行时间点
    */
    @Override
    public LocalDateTime nextExecutionTime(LocalDateTime from) {
        return cronExpression.nextExecutionTime(from);
    }

    /**
    * 获取从当前时间开始的 N 条执行时间
    *
    * @param count 执行时间点数量
    * @return 按时间排序的执行时间点列表
    */
    @Override
    public List<LocalDateTime> getFireTimes(int count) {
        return cronExpression.getFireTimes(count, LocalDateTime.now());
    }

    /**
    * 获取从指定时间开始的 N 条执行时间
    *
    * @param count 执行时间点数量
    * @param from  基准时间点
    * @return 按时间排序的执行时间点列表
    */
    @Override
    public List<LocalDateTime> getFireTimes(int count, LocalDateTime from) {
        return cronExpression.getFireTimes(count, from);
    }

    /**
    * 获取 Cron 表达式原始字符串
    *
    * @return Cron 表达式
    */
    public String getCron() {
        return cron;
    }
}
