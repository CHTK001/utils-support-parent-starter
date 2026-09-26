package com.chua.quartz.support;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.scheduler.CronTrigger;
import com.chua.common.support.task.scheduler.FixedTrigger;
import com.chua.common.support.task.scheduler.SchedulerProvider;
import org.quartz.CronExpression;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.impl.matchers.GroupMatcher;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 石英石 调度提供者语义门（main 方法直跑，不依赖测试框架）。
 *
 * <p>用真实调度实跑核对提供者的对外承诺：同一 Cron 表达式在框架解析器与 石英石 解析器上
 * 必须落到同一个触发时刻（星期字段编号口径不同）、空载与关闭后的状态必须可判定、
 * 同 标识 重复注册不得叠加触发链、重新调度后必须继续按新节奏触发、
 * 多个提供者实例之间不得互相回收任务、关闭不得无限等待在跑任务。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 * java -cp <类路径> com.chua.quartz.support.QuartzSchedulerProviderSmokeTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class QuartzSchedulerProviderSmokeTest {

    /**
     * 固定频率触发器的间隔（毫秒）
     */
    private static final long INTERVAL_MS = 200L;

    /**
     * 观察窗口（毫秒）
     */
    private static final long WINDOW_MS = 1400L;

    /**
     * 关闭窗口等待上限（毫秒），与提供者内部的兜底等待时间一致
     */
    private static final long SHUTDOWN_WAIT_MS = 5000L;

    /**
     * 阻塞在跑任务用于验证关闭不无限等待的时长（毫秒）
     */
    private static final long SLOW_JOB_MS = 8000L;

    /**
     * 两套解析器对时用的固定基准时间
     */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 21, 0, 0);

    private static int pass;

    private static int fail;

    /**
     * 仅用于资源回收、留待整轮结束后关闭的提供者
     */
    private static final List<QuartzSchedulerProvider> PENDING = new ArrayList<>();

    private QuartzSchedulerProviderSmokeTest() {
    }

    /**
     * 执行门校验。
     *
     * @param args 未使用
     * @throws Exception 线程等待被中断
     */
    public static void main(String[] args) throws Exception {
        run("Cron 星期字段双引擎一致", QuartzSchedulerProviderSmokeTest::checkCronParity);
        run("扩展点可达与空载状态", QuartzSchedulerProviderSmokeTest::checkFixedRateFiring);
        run("触发器属性与作业归属", QuartzSchedulerProviderSmokeTest::checkTriggerAttributes);
        run("同标识重复注册不叠加", QuartzSchedulerProviderSmokeTest::checkSameIdReplace);
        run("重新调度后继续触发", QuartzSchedulerProviderSmokeTest::checkRescheduleKeepsFiring);
        run("多实例互不回收", QuartzSchedulerProviderSmokeTest::checkInstanceIsolation);
        run("关闭后拒绝新请求", QuartzSchedulerProviderSmokeTest::checkScheduleAfterShutdown);
        run("关闭不无限等待在跑任务", QuartzSchedulerProviderSmokeTest::checkBoundedShutdown);
        for (QuartzSchedulerProvider provider : PENDING) {
            close(provider);
        }
        System.out.println("结果: PASS=" + pass + ", FAIL=" + fail);
        System.out.println("RESULT: " + (fail == 0 ? "PASS" : "FAIL"));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 同一 Cron 表达式在两套解析器上必须给出同一触发时刻与同一星期。
     *
     * @throws Exception 表达式解析失败
     */
    private static void checkCronParity() throws Exception {
        String[][] cases = {
                {"0 0 12 * * ?", null},
                {"0 0 12 * * 1", "MONDAY"},
                {"0 0 12 * * 2", "TUESDAY"},
                {"0 0 12 * * 0", "SUNDAY"},
                {"0 0 12 * * 7", "SUNDAY"},
                {"0 0 12 * * 6", "SATURDAY"},
                {"0 0 12 * * MON-FRI", null},
                {"0 0 12 * * SUN", "SUNDAY"},
                {"0 0 12 * * 1-5", null},
                {"0 0 12 * * 0-6", null},
                {"0 0 12 * * 1/2", null},
                {"0 0 12 ? * 5#3", "FRIDAY"},
                {"0 0 12 ? * 1L", "MONDAY"},
        };
        for (String[] item : cases) {
            String cron = item[0];
            LocalDateTime framework = new CronTrigger(cron).nextExecutionTime(BASE);
            String translated = toQuartzCron(cron);
            LocalDateTime quartz;
            try {
                quartz = toLocal(new CronExpression(translated).getNextValidTimeAfter(toDate(BASE)));
            } catch (Exception e) {
                quartz = null;
                observe("cron=" + cron + " 换算=" + translated + " 框架=" + framework + " 石英石异常="
                        + e.getClass().getSimpleName());
            }
            boolean same = framework != null && framework.equals(quartz);
            boolean dayOk = item[1] == null || (framework != null
                    && framework.getDayOfWeek() == DayOfWeek.valueOf(item[1]));
            observe("cron=" + cron + " 换算=" + translated + " 框架=" + framework + " 石英石=" + quartz
                    + " 星期=" + (framework == null ? "-" : framework.getDayOfWeek()));
            check("换算后双引擎同刻: " + cron, same);
            check("框架口径星期正确: " + cron, dayOk);
        }
    }

    /**
     * 固定频率任务应真实触发，且工作线程不得阻止虚拟机退出。
     *
     * @throws InterruptedException 等待触发被中断
     */
    private static void checkFixedRateFiring() throws InterruptedException {
        QuartzSchedulerProvider provider = new QuartzSchedulerProvider();
        try {
            check("扩展点 quartz 可达", ServiceProvider.of(SchedulerProvider.class).getExtension("quartz")
                    instanceof QuartzSchedulerProvider);
            check("空载调度器状态为运行中", provider.isRunning());
            var fires = new AtomicInteger();
            var daemon = new AtomicBoolean(false);
            provider.schedule("tick", () -> {
                daemon.set(Thread.currentThread().isDaemon());
                fires.incrementAndGet();
            }, new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
            Thread.sleep(WINDOW_MS);
            observe("固定频率 触发次数=" + fires.get() + " 守护线程=" + daemon.get()
                    + " 登记表数=" + provider.getScheduledTasks().size());
            check("固定频率任务持续触发", fires.get() >= 4);
            check("调度线程为守护线程", daemon.get());
        } finally {
            defer(provider);
        }
    }

    /**
     * 已登记触发器必须绑定作业归属，并按"跳过错过点"的策略处理错过触发。
     *
     * @throws Exception 反射或调度异常
     */
    private static void checkTriggerAttributes() throws Exception {
        QuartzSchedulerProvider provider = new QuartzSchedulerProvider();
        try {
            provider.schedule("cron-attr", () -> {
            }, new CronTrigger("0/1 * * * * ?"));
            provider.schedule("fixed-attr", () -> {
            }, new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
            Scheduler scheduler = schedulerOf(provider);
            if (scheduler == null) {
                observe("旧版无调度器访问器，触发器属性无法观测");
                return;
            }
            var cron = firstTrigger(scheduler, "cron-attr");
            var fixed = firstTrigger(scheduler, "fixed-attr");
            observe("cron 错过触发指令=" + (cron == null ? "-" : cron.getMisfireInstruction())
                    + " fixed 错过触发指令=" + (fixed == null ? "-" : fixed.getMisfireInstruction()));
            check("Cron 触发器跳过错过点", cron != null
                    && cron.getMisfireInstruction() == org.quartz.CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
            check("固定频率触发器跳过错过点", fixed != null && fixed.getMisfireInstruction()
                    == org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);
            check("触发器已绑定作业归属", cron != null && cron.getJobKey() != null
                    && fixed != null && JobKey.jobKey("fixed-attr", fixed.getJobKey().getGroup())
                    .equals(fixed.getJobKey()));
        } finally {
            defer(provider);
        }
    }

    /**
     * 同 标识 重复注册时旧任务须先失效，不得叠加两条触发链。
     *
     * @throws InterruptedException 等待触发被中断
     */
    private static void checkSameIdReplace() throws InterruptedException {
        QuartzSchedulerProvider provider = new QuartzSchedulerProvider();
        try {
            var first = new AtomicInteger();
            var second = new AtomicInteger();
            provider.schedule("dup", first::incrementAndGet,
                    new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
            String error = null;
            try {
                provider.schedule("dup", second::incrementAndGet,
                        new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName() + ":" + e.getMessage();
            }
            Thread.sleep(WINDOW_MS);
            observe("同标识 二次注册异常=" + error + " 旧任务次数=" + first.get()
                    + " 新任务次数=" + second.get() + " 登记表数=" + provider.getScheduledTasks().size());
            check("同 标识 重复注册不抛异常", error == null);
            check("重复注册后仅新任务触发", first.get() == 0 && second.get() >= 4);
            check("任务表按 标识 唯一", provider.getScheduledTasks().size() == 1);
        } finally {
            defer(provider);
        }
    }

    /**
     * 重新调度后任务必须继续按新节奏触发，且不得丢失作业归属。
     *
     * @throws InterruptedException 等待触发被中断
     */
    private static void checkRescheduleKeepsFiring() throws InterruptedException {
        QuartzSchedulerProvider provider = new QuartzSchedulerProvider();
        try {
            var fires = new AtomicInteger();
            provider.schedule("resched", fires::incrementAndGet,
                    new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
            Thread.sleep(WINDOW_MS);
            int before = fires.get();
            String error = null;
            try {
                provider.reschedule("resched", new FixedTrigger(0L, 100L, TimeUnit.MILLISECONDS));
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName() + ":" + e.getMessage();
            }
            Thread.sleep(WINDOW_MS);
            int after = fires.get() - before;
            observe("重新调度 前置次数=" + before + " 异常=" + error + " 后置次数=" + after);
            check("重新调度不抛异常", error == null);
            check("重新调度后按新节奏触发", after >= 8);
        } finally {
            defer(provider);
        }
    }

    /**
     * 一个提供者关闭不得回收另一个提供者的任务。
     *
     * @throws InterruptedException 等待触发被中断
     */
    private static void checkInstanceIsolation() throws InterruptedException {
        QuartzSchedulerProvider first = new QuartzSchedulerProvider();
        QuartzSchedulerProvider second = new QuartzSchedulerProvider();
        try {
            var firstFires = new AtomicInteger();
            var secondFires = new AtomicInteger();
            first.schedule("a", firstFires::incrementAndGet,
                    new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
            second.schedule("b", secondFires::incrementAndGet,
                    new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
            Thread.sleep(WINDOW_MS);
            int before = secondFires.get();
            observe("双实例并行 甲次数=" + firstFires.get() + " 乙次数=" + before);
            check("两个实例同时触发", firstFires.get() >= 4 && before >= 4);
            close(first);
            Thread.sleep(WINDOW_MS);
            observe("关闭甲之后 乙次数=" + secondFires.get() + " 乙状态=" + second.isRunning());
            check("关闭一个实例不影响另一个", secondFires.get() > before + 2);
        } finally {
            close(second);
        }
    }

    /**
     * 关闭后不得再接受调度请求，且必须给出可判定的状态异常。
     *
     * @throws InterruptedException 等待触发被中断
     */
    private static void checkScheduleAfterShutdown() throws InterruptedException {
        QuartzSchedulerProvider provider = new QuartzSchedulerProvider();
        provider.schedule("before", () -> {
        }, new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
        provider.shutdown();
        Throwable thrown = null;
        try {
            provider.schedule("after", () -> {
            }, new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
        } catch (Throwable e) {
            thrown = e;
        }
        observe("关闭后再调度异常=" + (thrown == null ? "无" : thrown.getClass().getName()));
        check("关闭后拒绝新的调度请求", thrown instanceof IllegalStateException);
        check("关闭后状态为停止", !provider.isRunning());
    }

    /**
     * 关闭须在限定时间内返回，不得被在跑任务拖住。
     *
     * @throws InterruptedException 等待触发被中断
     */
    private static void checkBoundedShutdown() throws InterruptedException {
        QuartzSchedulerProvider provider = new QuartzSchedulerProvider();
        var started = new AtomicInteger();
        provider.schedule("slow", () -> {
            started.incrementAndGet();
            sleep(SLOW_JOB_MS);
        }, new FixedTrigger(0L, INTERVAL_MS, TimeUnit.MILLISECONDS));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000L);
        while (started.get() == 0 && System.nanoTime() < deadline) {
            sleep(20L);
        }
        long begin = System.currentTimeMillis();
        close(provider);
        long elapsed = System.currentTimeMillis() - begin;
        observe("慢任务关闭 已开始次数=" + started.get() + " 关闭耗时=" + elapsed + "ms");
        check("关闭耗时受控", elapsed < SLOW_JOB_MS - 1000L);
    }

    /**
     * 反射取用提供者的调度器，旧版无该访问器时返回 {@code null}。
     *
     * @param provider 石英石 提供者
     * @return 底层调度器，不可用时为 {@code null}
     * @throws Exception 反射调用异常
     */
    private static Scheduler schedulerOf(QuartzSchedulerProvider provider) throws Exception {
        try {
            Method method = QuartzSchedulerProvider.class.getDeclaredMethod("scheduler");
            method.setAccessible(true);
            return (Scheduler) method.invoke(provider);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * 取指定 标识 在 石英石 中的触发器。
     *
     * @param scheduler 石英石 调度器
     * @param id        任务 标识
     * @return 触发器，未登记时为 {@code null}
     * @throws Exception 调度器查询异常
     */
    private static org.quartz.Trigger firstTrigger(Scheduler scheduler, String id) throws Exception {
        Set<JobKey> keys = scheduler.getJobKeys(GroupMatcher.anyJobGroup());
        for (JobKey key : keys) {
            if (id.equals(key.getName())) {
                var triggers = scheduler.getTriggersOfJob(key);
                return triggers.isEmpty() ? null : triggers.get(0);
            }
        }
        return null;
    }

    /**
     * 反射调用星期字段换算，旧版无该方法时按原样透传。
     *
     * @param cron 框架 Cron 表达式
     * @return 石英石 口径表达式
     */
    private static String toQuartzCron(String cron) {
        try {
            Method method = QuartzSchedulerProvider.class.getDeclaredMethod("toQuartzCron", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, cron);
        } catch (ReflectiveOperationException e) {
            return cron;
        }
    }

    /**
     * 登记待回收的提供者，资源回收延后到整轮校验结束。
     *
     * @param provider 石英石 提供者
     */
    private static void defer(QuartzSchedulerProvider provider) {
        PENDING.add(provider);
    }

    /**
     * 关闭提供者并吞掉关闭异常，避免污染断言计数。
     *
     * @param provider 石英石 提供者
     */
    private static void close(QuartzSchedulerProvider provider) {
        try {
            provider.shutdown();
        } catch (RuntimeException ignored) {
            // 关闭异常由对应断言单独体现
        }
    }

    /**
     * 执行单项校验，异常按失败计入而不中断整轮。
     *
     * @param name 校验名
     * @param body 校验体
     */
    private static void run(String name, Body body) {
        try {
            body.run();
        } catch (Throwable e) {
            fail++;
            System.out.println("[失败] " + name + " 异常: " + e);
        }
    }

    /**
     * 将 石英石 日期转换为本地时间。
     *
     * @param date 日期
     * @return 本地时间，入参为 {@code null} 时返回 {@code null}
     */
    private static LocalDateTime toLocal(Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    /**
     * 将本地时间转换为系统默认时区的日期。
     *
     * @param ldt 本地时间
     * @return 日期
     */
    private static Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 输出观测值。
     *
     * @param text 观测文本
     */
    private static void observe(String text) {
        System.out.println("OBSERVE " + text);
    }

    /**
     * 记录一条断言。
     *
     * @param name    断言名
     * @param ok      是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("[失败] " + name);
        }
    }

    /**
     * 休眠指定时长。
     *
     * @param millis 毫秒数
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 校验体
     */
    @FunctionalInterface
    private interface Body {

        /**
         * 执行校验。
         *
         * @throws Exception 校验过程中的任何异常
         */
        void run() throws Exception;
    }
}
