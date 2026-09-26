package com.chua.quartz.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.scheduler.AbstractSchedulerProvider;
import com.chua.common.support.task.scheduler.CronTrigger;
import com.chua.common.support.task.scheduler.FixedTrigger;
import com.chua.common.support.task.scheduler.ScheduledTask;
import com.chua.common.support.task.scheduler.Trigger;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 石英石 的调度器提供者实现
 *
 * <p>使用 石英石 {@link Scheduler} 提供企业级任务调度能力，支持 Cron 表达式、固定频率、
 * 持久化 JobStore 与集群部署。
 *
 * <p>实例隔离：每个提供者独占一个作业分组与一份任务解析表，因此同一 JVM 内的多个提供者
 * 互不覆盖；无参构造创建的是本提供者独占的调度器（单线程、守护线程），不会占用
 * {@link StdSchedulerFactory#getDefaultScheduler()} 这一 JVM 单例，也就不会出现
 * "一个提供者关闭、其它提供者的任务全部停摆"。需要接入容器托管或集群调度器时，
 * 使用 {@link QuartzSchedulerProvider(Scheduler)}，此时关闭只回收本提供者名下的作业。
 *
 * <p>错过触发补偿：固定频率触发器使用"跳过错过的触发点、保持原节奏"的策略，Cron 触发器
 * 使用"错过即等下一次"的策略，与 {@code FixedTrigger#nextExecutionTime} 的"只算当前之后的
 * 下一个触发点、不补跑"语义一致；石英石 默认的 SMART_POLICY 会在调度器停顿后集中补跑，
 * 造成任务风暴。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("quartz")
public class QuartzSchedulerProvider extends AbstractSchedulerProvider {

    /**
     * 独占调度器实例名序号
     */
    private static final AtomicInteger INSTANCE_SEQUENCE = new AtomicInteger();

    /**
     * 调度器上下文中登记任务解析表的键前缀
     */
    private static final String CONTEXT_KEY_PREFIX = "com.chua.quartz.resolver.";

    /**
     * 石英石 配置项：调度器实例名
     */
    private static final String PROP_INSTANCE_NAME = "org.quartz.scheduler.instanceName";

    /**
     * 石英石 配置项：工作线程数
     */
    private static final String PROP_THREAD_COUNT = "org.quartz.threadPool.threadCount";

    /**
     * 石英石 配置项：工作线程是否为守护线程
     */
    private static final String PROP_DAEMON_THREAD = "org.quartz.threadPool.makeThreadsDaemons";

    /**
     * 自建调度器的工作线程数
     */
    private static final int DEFAULT_THREAD_COUNT = 1;

    /**
     * 关闭时等待在跑任务完成的最长时间（毫秒），超时后不再等待
     */
    private static final long SHUTDOWN_WAIT_MILLIS = 5000L;

    /**
     * Cron 表达式参与 石英石 解析的字段数
     */
    private static final int CRON_FIELD_COUNT = 6;

    /**
     * Cron 表达式中星期字段的下标
     */
    private static final int CRON_DAY_OF_WEEK_INDEX = 5;

    /**
     * Cron 表达式中日字段的下标
     */
    private static final int CRON_DAY_OF_MONTH_INDEX = 3;

    /**
     * 石英石 表示该字段不限定的写法
     */
    private static final String UNSPECIFIED_FIELD = "?";

    /**
     * 框架星期字段的最小值（0 为周日）
     */
    private static final int WEEKDAY_MIN = 0;

    /**
     * 框架星期字段的最大值（7 同样表示周日）
     */
    private static final int WEEKDAY_MAX = 7;

    /**
     * 星期字段允许出现的字符：数字与 {@code *}、{@code -}、{@code /}
     */
    private static final String WEEKDAY_NUMERIC_CHARS = "0123456789*/-";

    /**
     * 石英石 调度器
     */
    private final Scheduler scheduler;

    /**
     * 调度器是否由本提供者创建：只有自建调度器才允许关闭
     */
    private final boolean owned;

    /**
     * 本提供者独占的作业分组
     */
    private final String group;

    /**
     * 任务登记表，与父类共享同一实例，供委托作业回取任务体
     */
    private final Map<String, ScheduledTask> registry = taskMap;

    /**
     * 创建独占默认配置的 石英石 调度器提供者
     *
     * <p>使用单线程、守护线程的内存 JobStore 调度器，不读取类路径下的 {@code quartz.properties}，
     * 需要自定义线程数、JDBC JobStore 或集群时改用 {@link QuartzSchedulerProvider(Properties)}。
     */
    public QuartzSchedulerProvider() {
        this(dedicatedConfig());
    }

    /**
     * 创建使用指定配置的 石英石 调度器提供者
     *
     * @param config 石英石 配置属性
     */
    public QuartzSchedulerProvider(Properties config) {
        this(createScheduler(config), true);
    }

    /**
     * 使用外部托管的 石英石 调度器创建提供者
     *
     * <p>适用于调度器由容器（或集群）托管的场景：关闭本提供者只回收自己名下的作业，
     * 不会关闭传入的调度器。
     *
     * @param scheduler 外部托管的调度器，不能为 {@code null}
     */
    public QuartzSchedulerProvider(Scheduler scheduler) {
        this(scheduler, false);
    }

    /**
     * 绑定调度器并登记本提供者独占的任务解析表。
     *
     * @param scheduler 石英石 调度器
     * @param owned     调度器是否由本提供者创建
     */
    private QuartzSchedulerProvider(Scheduler scheduler, boolean owned) {
        this.scheduler = scheduler;
        this.owned = owned;
        this.group = "com-chua-quartz-" + INSTANCE_SEQUENCE.incrementAndGet();
        Map<String, ScheduledTask> registered = registry;
        TaskResolver resolver = taskId -> {
            ScheduledTask task = registered.get(taskId);
            return task == null ? null : task.getTask();
        };
        try {
            scheduler.getContext().put(contextKey(), resolver);
        } catch (SchedulerException e) {
            throw new IllegalStateException("注册 石英石 任务解析表失败", e);
        }
    }

    /**
     * 按触发器登记作业并提交给 石英石 调度。
     *
     * @param id      任务 标识
     * @param task    业务逻辑
     * @param trigger 触发器
     */
    @Override
    public synchronized void doSchedule(String id, Runnable task, Trigger trigger) {
        try {
            if (scheduler.isShutdown()) {
                throw new IllegalStateException("石英石 调度器已关闭，无法调度任务: " + id);
            }
            JobDetail job = JobBuilder.newJob(DelegateJob.class)
                    .withIdentity(jobKey(id))
                    .build();
            org.quartz.Trigger quartzTrigger = toQuartzTrigger(id, trigger);
            if (!scheduler.isStarted()) {
                scheduler.start();
            }
            scheduler.scheduleJob(job, quartzTrigger);
        } catch (SchedulerException e) {
            throw new RuntimeException("调度任务失败: " + id, e);
        }
    }

    /**
     * 以新触发器替换已登记的调度；底层作业已丢失时按登记表重建。
     *
     * @param id      任务 标识
     * @param trigger 新触发器
     */
    @Override
    public synchronized void doReschedule(String id, Trigger trigger) {
        try {
            JobKey jobKey = jobKey(id);
            if (!scheduler.checkExists(jobKey)) {
                doSchedule(id, registry.get(id).getTask(), trigger);
                return;
            }
            scheduler.rescheduleJob(TriggerKey.triggerKey(id, group), toQuartzTrigger(id, trigger));
        } catch (SchedulerException e) {
            throw new RuntimeException("重新调度任务失败: " + id, e);
        }
    }

    /**
     * 从 石英石 中移除作业并回收任务登记。
     *
     * @param id 任务 标识
     */
    @Override
    public synchronized void doCancel(String id) {
        try {
            scheduler.deleteJob(jobKey(id));
        } catch (SchedulerException e) {
            throw new RuntimeException("取消任务失败: " + id, e);
        }
    }

    /**
     * 关闭调度器：自建调度器最多等待在跑任务 5 秒，托管调度器只回收本提供者名下的作业。
     */
    @Override
    protected synchronized void doShutdown() {
        try {
            if (owned) {
                shutdownOwnedScheduler();
            } else {
                for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(group))) {
                    scheduler.deleteJob(key);
                }
                scheduler.getContext().remove(contextKey());
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("关闭 石英石 调度器失败", e);
        }
    }

    /**
     * 创建本提供者独占的 石英石 配置。
     *
     * @return 含独占实例名的配置属性
     */
    private static Properties dedicatedConfig() {
        Properties props = new Properties();
        props.setProperty(PROP_INSTANCE_NAME, "chua-quartz-" + INSTANCE_SEQUENCE.incrementAndGet());
        props.setProperty(PROP_THREAD_COUNT, String.valueOf(DEFAULT_THREAD_COUNT));
        props.setProperty(PROP_DAEMON_THREAD, Boolean.TRUE.toString());
        return props;
    }

    /**
     * 按配置创建 石英石 调度器。
     *
     * @param config 石英石 配置属性
     * @return 新建的调度器
     */
    private static Scheduler createScheduler(Properties config) {
        try {
            return new StdSchedulerFactory(config).getScheduler();
        } catch (SchedulerException e) {
            throw new IllegalStateException("创建 石英石 调度器失败", e);
        }
    }

    /**
     * 在限定时间内关闭自建调度器，超时则不再等待仍在执行的任务。
     *
     * @throws SchedulerException 调度器关闭失败时抛出
     */
    private void shutdownOwnedScheduler() throws SchedulerException {
        if (scheduler.isShutdown()) {
            return;
        }
        Thread waiter = Thread.ofVirtual().name("quartz-shutdown-" + group).start(() -> {
            try {
                scheduler.shutdown(true);
            } catch (SchedulerException ignored) {
                // 关闭结果统一由下方 isShutdown 判定
            }
        });
        try {
            waiter.join(SHUTDOWN_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!scheduler.isShutdown()) {
            scheduler.shutdown(false);
        }
        running = false;
    }

    /**
     * 本提供者使用的 石英石 调度器，供同包校验取用已登记触发器的属性。
     *
     * @return 石英石 调度器
     */
    Scheduler scheduler() {
        return scheduler;
    }

    /**
     * 本提供者在调度器上下文中的任务解析表键。
     *
     * @return 上下文键
     */
    private String contextKey() {
        return CONTEXT_KEY_PREFIX + group;
    }

    /**
     * 本提供者命名空间下的作业键。
     *
     * @param id 任务 标识
     * @return 石英石 作业键
     */
    private JobKey jobKey(String id) {
        return JobKey.jobKey(id, group);
    }

    /**
     * 将框架通用触发器转换为 石英石 触发器。
     *
     * @param id      任务 标识
     * @param trigger 框架触发器
     * @return 石英石 触发器
     */
    private org.quartz.Trigger toQuartzTrigger(String id, Trigger trigger) {
        TriggerBuilder<org.quartz.Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(TriggerKey.triggerKey(id, group))
                .forJob(jobKey(id));
        if (trigger instanceof CronTrigger ct) {
            return builder
                    .withSchedule(CronScheduleBuilder.cronSchedule(toQuartzCron(ct.getCron()))
                            .withMisfireHandlingInstructionDoNothing())
                    .build();
        }
        if (trigger instanceof FixedTrigger ft) {
            return builder
                    .startAt(toDate(ft.nextExecutionTime()))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInMilliseconds(ft.getTimeUnit().toMillis(ft.getInterval()))
                            .repeatForever()
                            .withMisfireHandlingInstructionNextWithRemainingCount())
                    .build();
        }
        throw new IllegalArgumentException("不支持的触发器类型: " + trigger.getClass());
    }

    /**
     * 将框架 Cron 表达式换算为 石英石 Cron 表达式。
     *
     * <p>两套解析器的星期字段编号不同：框架按 {@code 0/7=周日、1=周一…6=周六}，
     * 石英石按 {@code 1=周日、2=周一…7=周六}。原样透传会让"每周一"落到周日执行，
     * 数值 {@code 0} 更会被 石英石 直接拒绝。此处只换算星期字段的数字写法，
     * {@code SUN-SAT} 名称写法两套解析器口径一致，保持原样。
     *
     * @param cron 框架 Cron 表达式
     * @return 石英石 可解析的等价表达式；字段数不符时原样返回，交由 石英石 报错
     * @throws IllegalArgumentException 日与周两侧同时限定时抛出，石英石 无法表达框架的并集语义
     */
    static String toQuartzCron(String cron) {
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != CRON_FIELD_COUNT) {
            return cron;
        }
        String dayOfMonth = fields[CRON_DAY_OF_MONTH_INDEX];
        String dayOfWeek = fields[CRON_DAY_OF_WEEK_INDEX];
        if (isUnrestricted(dayOfWeek)) {
            fields[CRON_DAY_OF_WEEK_INDEX] = UNSPECIFIED_FIELD;
            return String.join(" ", fields);
        }
        if (!isUnrestricted(dayOfMonth)) {
            throw new IllegalArgumentException(
                    "石英石 不支持同时限定日与周，请将其中一侧写为 * 或 ?：" + cron);
        }
        fields[CRON_DAY_OF_MONTH_INDEX] = UNSPECIFIED_FIELD;
        fields[CRON_DAY_OF_WEEK_INDEX] = translateWeekday(dayOfWeek);
        return String.join(" ", fields);
    }

    /**
     * 判断日或周字段是否为不限定写法。
     *
     * @param field 字段值
     * @return 写 {@code *} 或 {@code ?} 时返回 {@code true}
     */
    private static boolean isUnrestricted(String field) {
        return "*".equals(field) || UNSPECIFIED_FIELD.equals(field);
    }

    /**
     * 换算星期字段，逐项处理列表后按升序拼接。
     *
     * @param field 框架口径的星期字段
     * @return 石英石 口径的星期字段
     */
    private static String translateWeekday(String field) {
        if ("*".equals(field) || "?".equals(field)) {
            return field;
        }
        Set<String> translated = new LinkedHashSet<>();
        for (String part : field.split(",")) {
            translated.add(translateWeekdayPart(part.trim()));
        }
        return String.join(",", translated);
    }

    /**
     * 换算星期字段中的单个写法，保留 {@code L}、{@code #} 修饰。
     *
     * @param part 单个星期写法
     * @return 石英石 口径的写法
     */
    private static String translateWeekdayPart(String part) {
        int hash = part.indexOf('#');
        if (hash > 0) {
            return translateWeekdayPart(part.substring(0, hash)) + part.substring(hash);
        }
        if (part.endsWith("L")) {
            String base = part.substring(0, part.length() - 1);
            return isNumericWeekday(base) ? toQuartzWeekdays(base) + "L" : part;
        }
        return isNumericWeekday(part) ? toQuartzWeekdays(part) : part;
    }

    /**
     * 判断星期写法是否为纯数字表达式。
     *
     * @param part 单个星期写法
     * @return 仅由数字与 {@code *}、{@code -}、{@code /} 组成且非空时返回 {@code true}
     */
    private static boolean isNumericWeekday(String part) {
        if (part.isEmpty()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            if (WEEKDAY_NUMERIC_CHARS.indexOf(part.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 展开数字星期写法并换算为 石英石 编号。
     *
     * <p>先按框架口径枚举命中的星期（{@code a}、{@code a-b}、{@code a/n}、{@code a-b/n}），
     * 再逐个换算为 {@code 1-7}，因此 {@code 0-7}、{@code 1/2} 这类回绕写法不会漏日。
     *
     * @param part 数字星期写法
     * @return 换算后的星期列表；无有效取值时原样返回，交由 石英石 报错
     */
    private static String toQuartzWeekdays(String part) {
        String range = part;
        String stepText = "";
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            stepText = part.substring(slash + 1);
        }
        int min;
        int max;
        if ("*".equals(range)) {
            min = WEEKDAY_MIN;
            max = WEEKDAY_MAX;
        } else {
            int dash = range.indexOf('-');
            min = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
            max = Integer.parseInt(dash < 0 ? range : range.substring(dash + 1));
        }
        int step = stepText.isEmpty() ? 1 : Integer.parseInt(stepText);
        if (!stepText.isEmpty() && min == max) {
            max = WEEKDAY_MAX;
        }
        Set<Integer> translated = new TreeSet<>();
        for (int weekday = min; weekday <= max; weekday += step) {
            translated.add(weekday % 7 + 1);
        }
        if (translated.isEmpty()) {
            return part;
        }
        StringBuilder text = new StringBuilder();
        for (Integer weekday : translated) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(weekday);
        }
        return text.toString();
    }

    /**
     * 将本地时间转换为 石英石 使用的日期。
     *
     * @param ldt 本地时间
     * @return 系统默认时区对应的日期
     */
    private static Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 委托作业回取任务体的函数
     */
    public interface TaskResolver {

        /**
         * 按任务 标识 回取任务体。
         *
         * @param taskId 任务 标识
         * @return 任务体，未登记时返回 {@code null}
         */
        Runnable resolve(String taskId);
    }

    /**
     * 石英石 委托任务
     *
     * <p>不持有任务体，而是通过作业分组回到所属提供者的任务登记表取用，
     * 因此同一 JVM 内的多个提供者互不干扰；登记表缺失 标识 时显式抛错，
     * 不再出现"作业按点触发却什么都不做"的静默空跑。
     *
     * @author CH
     * @since 4.0.0
     */
    public static class DelegateJob implements Job {

        /**
         * 回取并执行登记在案的任务体。
         *
         * @param context 作业执行上下文
         * @throws JobExecutionException 任务解析表缺失或任务体未登记时抛出
         */
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            String taskId = context.getJobDetail().getKey().getName();
            String key = CONTEXT_KEY_PREFIX + context.getJobDetail().getKey().getGroup();
            Object resolver;
            try {
                resolver = context.getScheduler().getContext().get(key);
            } catch (SchedulerException e) {
                throw new JobExecutionException("读取 石英石 任务解析表失败: " + taskId, e);
            }
            Runnable task = resolver instanceof TaskResolver tr ? tr.resolve(taskId) : null;
            if (task == null) {
                throw new JobExecutionException("石英石 任务解析表未登记任务: " + taskId, null, false);
            }
            task.run();
        }
    }
}
