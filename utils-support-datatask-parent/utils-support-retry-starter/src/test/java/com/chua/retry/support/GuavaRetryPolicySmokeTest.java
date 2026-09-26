package com.chua.retry.support;

import com.chua.common.support.task.retry.JdkRetryProvider;
import com.chua.common.support.task.retry.RetryConfig;
import com.chua.common.support.task.retry.RetryProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Guava 重试策略语义门（main 方法直跑，不依赖测试框架）。
 *
 * <p>以实测方式核对 {@link GuavaRetryProvider} 与 {@link JdkRetryProvider} 对同一份
 * {@link RetryConfig} 的行为是否一致：异常过滤器是否短路、指数退避是否按
 * {@code delay * multiplier^n} 增长、固定与斐波那契等待是否与参考实现同量。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 * java -cp <类路径> com.chua.retry.support.GuavaRetryPolicySmokeTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class GuavaRetryPolicySmokeTest {

    /**
     * 退避基准间隔（毫秒），留出可测的时间差
     */
    private static final long DELAY = 80L;

    /**
     * 指数退避倍率
     */
    private static final double MULTIPLIER = 3.0D;

    /**
     * 最大重试次数（不含首次执行）
     */
    private static final int MAX_RETRIES = 3;

    /**
     * 期望总执行次数
     */
    private static final int EXPECTED_CALLS = MAX_RETRIES + 1;

    /**
     * 时间测量容差（比例）
     */
    private static final double TOLERANCE = 0.45D;

    private static int pass;

    private static int fail;

    private GuavaRetryPolicySmokeTest() {
    }

    /**
     * 执行门校验。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        checkExceptionFilterShortCircuits();
        checkExceptionFilterAllowsRetry();
        checkExponentialGrowthMatchesConfig();
        checkFixedAndFibonacciParityWithJdk();
        System.out.println("结果: PASS=" + pass + ", FAIL=" + fail);
        System.out.println("RESULT: " + (fail == 0 ? "PASS" : "FAIL"));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 配置了异常过滤器时，未命中的异常不得消耗重试次数。
     */
    private static void checkExceptionFilterShortCircuits() {
        var guava = attempt(config(null).setRetryOnException(e -> e instanceof IOException),
                new IllegalStateException("不可重试"));
        var jdk = attemptJdk(config(null).setRetryOnException(e -> e instanceof IOException));
        observe("过滤器未命中 guava 调用次数=" + guava.calls() + " 抛出=" + rootCause(guava.error()));
        observe("过滤器未命中 jdk   调用次数=" + jdk.calls() + " 抛出=" + rootCause(jdk.error()));
        check("未命中过滤器只执行一次", guava.calls() == 1);
        check("未命中时根因仍为原异常", rootCause(guava.error()).endsWith("IllegalStateException"));
        check("两种提供者都不重试不可重试异常", guava.calls() == jdk.calls());
    }

    /**
     * 命中过滤器的异常仍按配置重试；未配置过滤器时全部异常都重试。
     */
    private static void checkExceptionFilterAllowsRetry() {
        var hit = attempt(config(null).setRetryOnException(e -> e instanceof IllegalStateException),
                new IllegalStateException("可重试"));
        var unfiltered = attempt(config(null), new IllegalStateException("无过滤器"));
        var exhausted = attemptJdk(config(null));
        observe("命中过滤器 调用次数=" + hit.calls() + " 未配置过滤器 调用次数=" + unfiltered.calls());
        observe("重试用尽 guava 抛出=" + rootCause(unfiltered.error()) + " jdk 抛出=" + rootCause(exhausted.error()));
        check("命中过滤器重试到上限", hit.calls() == EXPECTED_CALLS);
        check("未配置过滤器时全部异常重试", unfiltered.calls() == EXPECTED_CALLS);
        check("重试用尽抛出同一异常类型", unfiltered.error().getClass() == exhausted.error().getClass());
    }

    /**
     * 指数退避应严格按配置的倍率增长，且总时长与参考实现同量。
     */
    private static void checkExponentialGrowthMatchesConfig() {
        var guava = attempt(config(RetryConfig.BackoffStrategy.EXPONENTIAL), new IllegalStateException("boom"));
        var jdk = attemptJdk(config(RetryConfig.BackoffStrategy.EXPONENTIAL));
        List<Long> gaps = gaps(guava.stamps());
        observe("guava 指数退避 各轮间隔=" + gaps + " 总耗时=" + guava.elapsedMs() + "ms");
        observe("jdk   指数退避 各轮间隔=" + gaps(jdk.stamps()) + " 总耗时=" + jdk.elapsedMs() + "ms");
        check("指数退避轮数正确", gaps.size() == MAX_RETRIES);
        for (int i = 1; i < gaps.size(); i++) {
            double ratio = gaps.get(i).doubleValue() / gaps.get(i - 1);
            check("第 " + (i + 1) + " 轮增长倍率≈" + MULTIPLIER + "（实测 " + fmt(ratio) + "）",
                    Math.abs(ratio - MULTIPLIER) <= MULTIPLIER * TOLERANCE);
        }
        check("指数退避总时长与参考实现同量", withinTolerance(guava.elapsedMs(), jdk.elapsedMs()));
    }

    /**
     * 固定等待与斐波那契等待应与参考实现同量。
     */
    private static void checkFixedAndFibonacciParityWithJdk() {
        var fixed = config(RetryConfig.BackoffStrategy.FIXED);
        var guavaFixed = attempt(fixed, new IllegalStateException("boom"));
        var jdkFixed = attemptJdk(fixed);
        observe("固定等待 guava 总耗时=" + guavaFixed.elapsedMs() + "ms jdk=" + jdkFixed.elapsedMs() + "ms"
                + " 理论=" + (DELAY * MAX_RETRIES) + "ms");
        check("固定等待与参考实现同量", withinTolerance(guavaFixed.elapsedMs(), jdkFixed.elapsedMs()));
        check("固定等待轮数正确", gaps(guavaFixed.stamps()).size() == MAX_RETRIES);

        var fib = config(RetryConfig.BackoffStrategy.FIBONACCI);
        var guavaFib = attempt(fib, new IllegalStateException("boom"));
        var jdkFib = attemptJdk(fib);
        observe("斐波那契 guava 各轮间隔=" + gaps(guavaFib.stamps()) + " 总耗时=" + guavaFib.elapsedMs()
                + "ms jdk=" + jdkFib.elapsedMs() + "ms");
        check("斐波那契与参考实现同量", withinTolerance(guavaFib.elapsedMs(), jdkFib.elapsedMs()));
    }

    /**
     * 构造测试配置。
     *
     * @param strategy 退避策略，传 {@code null} 使用配置默认值
     * @return 重试配置
     */
    private static RetryConfig config(RetryConfig.BackoffStrategy strategy) {
        var config = new RetryConfig()
                .setMaxRetries(MAX_RETRIES)
                .setDelay(DELAY)
                .setMultiplier(MULTIPLIER);
        if (strategy != null) {
            config.setBackoffStrategy(strategy);
        }
        return config;
    }

    /**
     * 用 Guava 提供者执行必然失败的任务。
     *
     * @param config 重试配置
     * @param error  任务抛出的异常
     * @return 执行记录
     */
    private static Run attempt(RetryConfig config, Exception error) {
        return run(new GuavaRetryProvider(), config, error);
    }

    /**
     * 用 JDK 参考实现执行必然失败的任务。
     *
     * @param config 重试配置
     * @return 执行记录
     */
    private static Run attemptJdk(RetryConfig config) {
        return run(new JdkRetryProvider(), config, new IllegalStateException("boom"));
    }

    /**
     * 执行并记录调用次数与时间戳。
     *
     * @param provider 重试提供者
     * @param config   重试配置
     * @param error    任务抛出的异常
     * @return 执行记录
     */
    private static Run run(RetryProvider provider, RetryConfig config,
                           Exception error) {
        var start = System.nanoTime();
        var stamps = new ArrayList<Long>();
        var calls = new int[] {0};
        Callable<Object> body = () -> {
            stamps.add(System.nanoTime() - start);
            calls[0]++;
            throw error;
        };
        Exception caught = null;
        try {
            provider.execute(body, config);
        } catch (Exception e) {
            caught = e;
        }
        var elapsed = (System.nanoTime() - start) / 1_000_000L;
        return new Run(calls[0], List.copyOf(stamps), elapsed, caught);
    }

    /**
     * 取根因类型名（两种提供者的异常包装方式不同，按根因判定语义）。
     *
     * @param error 异常，可为 空
     * @return 根因类型名
     */
    private static String rootCause(Exception error) {
        if (error == null) {
            return "无异常";
        }
        Throwable cur = error;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return error.getClass().getSimpleName() + " -> " + cur.getClass().getSimpleName();
    }

    /**
     * 计算相邻调用之间的等待时长（毫秒）。
     *
     * @param stamps 调用时间戳
     * @return 间隔列表
     */
    private static List<Long> gaps(List<Long> stamps) {
        var result = new ArrayList<Long>();
        for (int i = 1; i < stamps.size(); i++) {
            result.add((stamps.get(i) - stamps.get(i - 1)) / 1_000_000L);
        }
        return result;
    }

    /**
     * 判断两个耗时是否在同一量级。
     *
     * @param actual  实测值
     * @param base 参考值
     * @return 是否在容差内
     */
    private static boolean withinTolerance(long actual, long base) {
        if (base <= 0) {
            return actual <= 0;
        }
        return Math.abs(actual - base) <= base * TOLERANCE;
    }

    /**
     * 格式化数值。
     *
     * @param value 数值
     * @return 两位小数字符串
     */
    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    /**
     * 输出观察值。
     *
     * @param text 观察内容
     */
    private static void observe(String text) {
        System.out.println("OBSERVE " + text);
    }

    /**
     * 记录单项校验结果。
     *
     * @param name 校验名
     * @param ok 是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name);
        } else {
            fail++;
            System.out.println("  [失败] " + name);
        }
    }

    /**
     * 一次执行记录。
     *
     * @param calls 任务被调用次数
     * @param stamps 每次调用相对开始的纳秒偏移
     * @param elapsedMs 总耗时（毫秒）
     * @param error 最终抛出的异常，可为空
     */
    private record Run(int calls, List<Long> stamps, long elapsedMs, Exception error) {

        /**
         * 规范构造器：对时间戳列表做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。调用方传入的是逐次累加的可变
         * {@link ArrayList}，若直接持有，测量结束后的任何追加都会改写已发布的结果。</p>
         *
         * @param calls     任务被调用次数
         * @param stamps    每次调用相对开始的纳秒偏移
         * @param elapsedMs 总耗时（毫秒）
         * @param error     最终抛出的异常，可为空
         */
        private Run {
            stamps = List.copyOf(stamps);
        }
    }
}
