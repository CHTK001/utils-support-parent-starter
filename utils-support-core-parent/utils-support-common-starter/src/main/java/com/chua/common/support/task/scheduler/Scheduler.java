package com.chua.common.support.task.scheduler;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 任务调度注解
 *
 * <p>用于标记需要定时调度的方法，支持 Cron 表达式和固定频率/延迟两种调度模式。
 * 通过该注解，开发者可以声明式地定义任务的调度策略，无需编写额外的调度代码。
 *
 * <p>调度模式说明：
 * <ul>
 *   <li><strong>Cron 模式</strong>：通过 {@link #cron()} 指定 Cron 表达式，支持标准的 6 字段格式
 *   （秒 分 时 日 月 周），可精确到秒级触发</li>
 *   <li><strong>固定频率模式</strong>：通过 {@link #fixedRate()} 指定两次任务开始执行的时间间隔，
 *   无论前一次任务是否执行完成，下一次任务都会按计划触发</li>
 *   <li><strong>固定延迟模式</strong>：通过 {@link #fixedDelay()} 指定前一次任务执行完成后到下一次
 *   任务开始执行的延迟时间，确保任务串行执行</li>
 * </ul>
 *
 * <p>优先级说明：当同时指定了 {@code cron}、{@code fixedRate} 和 {@code fixedDelay} 时，
   * 优先级为：cron {@literal >} fixedrate {@literal >} fixed延迟。
 *
 * <p>使用示例：
 * <pre>{@code
 * // Cron 表达式：每小时的 30 分 15 秒执行
 * @Scheduler(cron = "15 30 * * * ?")
 * public void hourlyTask() { }
 *
 * // 固定频率：每 5 秒执行一次（第一次延迟 1 秒）
 * @Scheduler(initialDelay = 1, fixedRate = 5, timeUnit = TimeUnit.SECONDS)
 * public void periodicTask() { }
 * }</pre></pre>
 *
 * @author CH
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Scheduler {

    /**
     * Cron 表达式
     *
     * <p>标准 6 字段 Cron 表达式，格式为：{@code 秒 分 时 日 月 周}。
     * 当此值非空时，优先使用 Cron 模式进行调度。
     *
     * <p>字段说明：
     * <ul>
     *   <li>秒（0-59）</li>
     *   <li>分（0-59）</li>
     *   <li>时（0-23）</li>
     *   <li>日（1-31，支持 L、W）</li>
     *   <li>月（1-12 或 JAN-DEC）</li>
     *   <li>周（0-7，0 和 7 均表示周日，支持 L、#）</li>
     * </ul>
     *
     * <p>支持的表达式示例：
     * <ul>
     *   <li>{@code 0 0 12 * * ?} — 每天中午 12 点</li>
     *   <li>{@code 0 0/5 * * * ?} — 每 5 分钟</li>
     *   <li>{@code 0 0 9-18 * * MON-FRI} — 工作日 9 点到 18 点每小时</li>
     *   <li>{@code 0 0 0 L * ?} — 每月最后一天午夜</li>
     * </ul>
     *
     * @return Cron 表达式，默认为空字符串（不使用 Cron 模式）
     */
    String cron() default "";

    /**
     * 固定频率（单位由 {@link #timeUnit()} 指定）
     *
     * <p>两次任务开始执行之间的时间间隔。无论前一次任务是否执行完成，
     * 下一次任务都会按固定间隔触发。适用于对时间精度要求较高、允许
     * 任务并发执行的场景。
     *
     * <p>例如：fixedRate = 5000, timeUnit = MILLISECONDS 表示每 5 秒执行一次。
     *
     * @return 固定频率值，-1 表示不使用固定频率模式
     */
    long fixedRate() default -1;

    /**
     * 固定延迟（单位由 {@link #timeUnit()} 指定）
     *
     * <p>前一次任务执行完成后到下一次任务开始执行的等待时间。
     * 确保任务串行执行，适用于需要避免任务重叠的场景。
     *
     * <p>例如：fixedDelay = 3000 表示每次任务执行完成后等待 3 秒再执行下一次。
     *
     * @return 固定延迟值，-1 表示不使用固定延迟模式
     */
    long fixedDelay() default -1;

    /**
     * 初始延迟时间（单位由 {@link #timeUnit()} 指定）
     *
     * <p>任务第一次执行前的等待时间。只在首次触发时生效，
     * 后续任务的触发间隔由 {@link #fixedRate()} 或 {@link #fixedDelay()} 决定。
     *
     * @return 初始延迟值，默认为 0（立即执行）
     */
    long initialDelay() default 0;

    /**
     * 时间单位
     *
     * <p>用于指定 {@link #initialDelay()}、{@link #fixedRate()} 和 {@link #fixedDelay()} 的时间单位。
     * 默认为毫秒（MILLISECONDS）。
     *
     * @return 时间单位，默认为 {@link TimeUnit#MILLISECONDS}
     */
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
}
