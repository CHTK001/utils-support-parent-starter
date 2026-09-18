package com.chua.quartz.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.scheduler.AbstractSchedulerProvider;
import com.chua.common.support.task.scheduler.CronTrigger;
import com.chua.common.support.task.scheduler.FixedTrigger;
import com.chua.common.support.task.scheduler.Trigger;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
* 基于 石英石 的调度器提供者实现
*
* <p>使用 Quartz {@link org.quartz.Scheduler} 提供企业级任务调度能力。
* 石英石 是一个功能强大的开源任务调度框架，支持复杂的调度策略、
* 持久化、集群部署等高级特性。
*
* <p>核心特性：
* <ul>
*   <li><strong>丰富的调度策略</strong>：Cron 表达式、SimpleTrigger、Calendar 等</li>
*   <li><strong>持久化</strong>：支持 JDBC JobStore，任务信息持久化到数据库</li>
*   <li><strong>集群</strong>：支持集群部署，避免任务重复执行</li>
*   <li><strong>错过触发补偿</strong>：支持 misfire 处理机制</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("quartz")
public class QuartzSchedulerProvider extends AbstractSchedulerProvider {

    /**
    * 石英石 调度器
    */
    private final Scheduler scheduler;

    /**
    * 任务运行器缓存（供 delegate作业 访问）
    */
    static final Map<String, Runnable> TASK_CACHE = new ConcurrentHashMap<>();

    /**
    * 创建默认配置的 石英石 调度器提供者
    */
    public QuartzSchedulerProvider() {
        try {
            this.scheduler = StdSchedulerFactory.getDefaultScheduler();
            this.running = false;
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to create Quartz scheduler", e);
        }
    }

    /**
    * 创建使用指定配置的 石英石 调度器提供者
    *
    * @param config 石英石 配置属性
    */
    public QuartzSchedulerProvider(Properties config) {
        try {
            var factory = new StdSchedulerFactory(config);
            this.scheduler = factory.getScheduler();
            this.running = false;
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to create Quartz scheduler", e);
        }
    }

    @Override
    /** 执行调度 */
    public synchronized void doSchedule(String id, Runnable task, Trigger trigger) {
        try {
            TASK_CACHE.put(id, task);

            JobDetail job = JobBuilder.newJob(DelegateJob.class)
                    .withIdentity(id, "default")
                    .build();

            org.quartz.Trigger quartzTrigger = toQuartzTrigger(id, trigger);

            if (!scheduler.isStarted()) {
                scheduler.start();
                running = true;
            }

            scheduler.scheduleJob(job, quartzTrigger);
        } catch (SchedulerException e) {
            TASK_CACHE.remove(id);
            throw new RuntimeException("Failed to schedule task: " + id, e);
        }
    }

    @Override
    /** 执行reschedule */
    public synchronized void doReschedule(String id, Trigger trigger) {
        try {
            var oldTriggerKey = TriggerKey.triggerKey(id, "default");
            if (!scheduler.checkExists(oldTriggerKey)) {
                return;
            }

            org.quartz.Trigger newTrigger = toQuartzTrigger(id, trigger);
            scheduler.rescheduleJob(oldTriggerKey, newTrigger);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to reschedule task: " + id, e);
        }
    }

    @Override
    /** 执行cancel */
    public synchronized void doCancel(String id) {
        try {
            scheduler.deleteJob(JobKey.jobKey(id, "default"));
            TASK_CACHE.remove(id);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to cancel task: " + id, e);
        }
    }

    @Override
    /** 执行关闭 */
    protected synchronized void doShutdown() {
        try {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown(true);
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to shutdown scheduler", e);
        }
        TASK_CACHE.clear();
    }

    /**
    * 将框架通用 Trigger 转换为 石英石 Trigger
    * @param id 标识
    * @param trigger trigger
    * @return 转为石英石trigger的结果
    */
    private org.quartz.Trigger toQuartzTrigger(String id, Trigger trigger) {
        if (trigger instanceof CronTrigger ct) {
            return TriggerBuilder.newTrigger()
                    .withIdentity(id, "default")
                    .withSchedule(CronScheduleBuilder.cronSchedule(ct.getCron()))
                    .build();
        }
        if (trigger instanceof FixedTrigger ft) {
            return TriggerBuilder.newTrigger()
                    .withIdentity(id, "default")
                    .startAt(toDate(ft.nextExecutionTime()))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInMilliseconds(ft.getTimeUnit().toMillis(ft.getInterval()))
                            .repeatForever())
                    .build();
        }
        throw new IllegalArgumentException("Unsupported trigger type: " + trigger.getClass());
    }

    /**
    * 转为日期
    *
    * @param ldt ldt
    * @return 转为日期的结果
    */
    private static Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
    * 石英石 委托任务
    * @author CH
    * @since 4.0.0
    */
    public static class DelegateJob implements Job {
        @Override
        /** 执行 */
        public void execute(JobExecutionContext context) {
            String taskId = context.getJobDetail().getKey().getName();
            Runnable task = TASK_CACHE.get(taskId);
            if (task != null) {
                task.run();
            }
        }
    }
}
