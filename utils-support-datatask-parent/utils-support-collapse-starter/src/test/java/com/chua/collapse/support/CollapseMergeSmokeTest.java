package com.chua.collapse.support;

import com.chua.common.support.concurrent.collapse.CollapseBatchFunction;
import com.chua.common.support.concurrent.collapse.CollapseConfig;
import com.chua.common.support.concurrent.collapse.CollapseResultMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * 折叠执行器合并与回填语义门（main 方法直跑，不依赖测试框架）。
 *
 * <p>用并发实跑核对折叠执行器的对外承诺：相同入参只批量执行一次并广播结果、
 * 不同入参不得混进同一批量调用、拆分回填模式下每个调用者拿到自己的结果、
 * 映射器缺项或批量函数异常时逐调用者显式失败而非静默成功，
 * 以及整条链路不得向标准错误输出写诊断文本。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 * java -cp <类路径> com.chua.collapse.support.CollapseMergeSmokeTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CollapseMergeSmokeTest {

    /**
     * 并发调用方数量
     */
    private static final int CALLERS = 24;

    /**
     * 等待调用方收尾的超时（毫秒）
     */
    private static final long JOIN_TIMEOUT = 10_000L;

    /**
     * 映射器缺项时的约定异常消息
     */
    private static final String MISSING_MESSAGE = "折叠结果中缺少调用者对应结果";

    private static int pass;

    private static int fail;

    private CollapseMergeSmokeTest() {
    }

    /**
     * 执行门校验。
     *
     * @param args 未使用
     * @throws Throwable 调用线程或执行器抛出的异常
     */
    public static void main(String[] args) throws Throwable {
        checkSameInputCollapses();
        checkGroupingKeepsInputsHomogeneous();
        checkMapperFillsEachCaller();
        checkMapperMissingEntryFailsOnlyThatCaller();
        checkBatchFailurePropagates();
        checkCloseRejectsNewCalls();
        System.out.println("结果: PASS=" + pass + ", FAIL=" + fail);
        System.out.println("RESULT: " + (fail == 0 ? "PASS" : "FAIL"));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 相同入参应折叠为少于调用数的批量执行，且全部调用者拿到同一结果。
     *
     * @throws InterruptedException 等待调用线程被中断
     */
    private static void checkSameInputCollapses() throws InterruptedException {
        var batchCalls = new AtomicInteger();
        try (var executor = new DefaultCollapseExecutor<String, Integer>(
                config("collapse-same", 0L, 6, false),
                (CollapseBatchFunction<String, Integer>) inputs -> {
                    batchCalls.incrementAndGet();
                    return inputs.size();
                })) {
            var results = callAll(executor, i -> "same");
            var metrics = executor.metrics();
            observe("相同入参 调用数=" + CALLERS + " 批量执行次数=" + batchCalls.get()
                    + " avgBatchSize=" + metrics.get("avgBatchSize") + " maxBatchSize=" + metrics.get("maxBatchSize"));
            check("全部调用者拿到结果", results.settled() == CALLERS);
            check("批量执行次数远少于调用数", batchCalls.get() * 2 <= CALLERS);
            check("平均批次规模体现合并收益", toDouble(metrics.get("avgBatchSize")) >= 2.0d);
            check("相同入参结果一致", results.byInput().size() == 1);
        }
    }

    /**
     * 不同入参不得被合进同一次批量调用（广播模式下结果按组分发）。
     *
     * @throws InterruptedException 等待调用线程被中断
     */
    private static void checkGroupingKeepsInputsHomogeneous() throws InterruptedException {
        var mixed = new AtomicReference<String>();
        try (var executor = new DefaultCollapseExecutor<String, String>(
                config("collapse-group", 0L, 6, false),
                (CollapseBatchFunction<String, String>) inputs -> {
                    if (new HashSet<>(inputs).size() > 1) {
                        mixed.compareAndSet(null, "混入不同入参: " + inputs);
                    }
                    return inputs.iterator().next();
                })) {
            var results = callAll(executor, i -> "key-" + (i % 4));
            boolean aligned = results.byInput().entrySet().stream().allMatch(e -> e.getKey().equals(e.getValue()));
            observe("分组隔离 混合批次=" + mixed.get() + " 结果种类=" + results.byInput().size()
                    + " 收尾调用数=" + results.settled());
            check("批量调用入参始终同组", mixed.get() == null);
            check("每个调用者拿到自身入参的结果", aligned && results.settled() == CALLERS);
        }
    }

    /**
     * 拆分回填模式下每个调用者拿到映射器为自己分配的结果，且不产生诊断输出。
     *
     * @throws InterruptedException 等待调用线程被中断
     */
    private static void checkMapperFillsEachCaller() throws InterruptedException {
        var results = new LinkedHashMap<String, String>();
        var captured = captureStderr(() -> {
            try (var executor = new DefaultCollapseExecutor<String, String>(
                    config("collapse-mapper", 20L, CALLERS, true),
                    (CollapseResultMapper<String, String>) inputs -> {
                        Map<String, String> mapped = new LinkedHashMap<>();
                        for (var input : inputs) {
                            mapped.put(input, "v:" + input);
                        }
                        return mapped;
                    })) {
                callAll(executor, i -> "m-" + i).drainInto(results);
            }
        });
        boolean aligned = results.entrySet().stream().allMatch(e -> ("v:" + e.getKey()).equals(e.getValue()));
        observe("映射器模式 回填数=" + results.size() + " stderr 行数=" + captured.lines().size());
        check("映射器逐调用者回填正确", aligned && results.size() == CALLERS);
        check("合并链路不向标准错误输出写诊断", captured.lines().isEmpty());
    }

    /**
     * 映射器缺某调用者结果时，仅该调用者失败，其余不受影响。
     *
     * @throws InterruptedException 等待调用线程被中断
     */
    private static void checkMapperMissingEntryFailsOnlyThatCaller() throws InterruptedException {
        var missing = "m-3";
        var errors = new LinkedHashMap<String, String>();
        var captured = captureStderr(() -> {
            try (var executor = new DefaultCollapseExecutor<String, String>(
                    config("collapse-missing", 20L, CALLERS, true),
                    (CollapseResultMapper<String, String>) inputs -> {
                        Map<String, String> mapped = new LinkedHashMap<>();
                        for (var input : inputs) {
                            if (!missing.equals(input)) {
                                mapped.put(input, "v:" + input);
                            }
                        }
                        return mapped;
                    })) {
                callAllExpectingFailure(executor, i -> "m-" + i).drainInto(errors);
            }
        });
        observe("缺项回填 失败调用者=" + errors.keySet() + " stderr 行数=" + captured.lines().size());
        check("缺项调用者显式失败", errors.size() == 1 && MISSING_MESSAGE.equals(errors.get(missing)));
        check("缺项路径不写诊断输出", captured.lines().isEmpty());
    }

    /**
     * 批量函数异常必须传给组内每个调用者。
     *
     * @throws InterruptedException 等待调用线程被中断
     */
    private static void checkBatchFailurePropagates() throws InterruptedException {
        try (var executor = new DefaultCollapseExecutor<String, String>(
                config("collapse-error", 0L, 6, false),
                (CollapseBatchFunction<String, String>) inputs -> {
                    throw new IllegalStateException("下游不可用");
                })) {
            var failures = callAllExpectingFailure(executor, i -> "e-" + i);
            observe("批量异常 失败数=" + failures.settled() + " 首个原因=" + failures.byInput().get("e-0"));
            check("全部调用者收到批量异常", failures.settled() == CALLERS);
            check("异常原因为下游异常", failures.byInput().values().stream().allMatch("下游不可用"::equals));
        }
    }

    /**
     * 关闭后不得再接受调用。
     *
     * @throws Throwable 执行异常
     */
    private static void checkCloseRejectsNewCalls() throws Throwable {
        var executor = new DefaultCollapseExecutor<String, String>(
                config("collapse-close", -1L, 1, false),
                (CollapseBatchFunction<String, String>) inputs -> inputs.toString());
        executor.execute("before-close");
        executor.close();
        String thrown = null;
        try {
            executor.execute("after-close");
        } catch (Exception e) {
            thrown = e.getClass().getSimpleName();
        }
        observe("关闭后调用抛出=" + thrown);
        check("关闭后拒绝新调用", "IllegalStateException".equals(thrown));
    }

    /**
     * 构造测试配置。
     *
     * @param name          执行器名称
     * @param collectingWait 补收等待毫秒数（小于 0 表示不等待）
     * @param threshold     批量收集阈值
     * @param mergeAll      是否整批合并
     * @return 折叠配置
     */
    private static CollapseConfig config(String name, long collectingWait, int threshold, boolean mergeAll) {
        var config = new CollapseConfig();
        config.setName(name);
        config.setCollectingWaitTime(collectingWait);
        config.setWaitThreshold(threshold);
        config.setMergeAll(mergeAll);
        config.setVirtualThread(false);
        return config;
    }

    /**
     * 并发发起调用并收集成功结果。
     *
     * @param executor 折叠执行器
     * @param inputAt  第 i 个调用者的入参
     * @return 入参到结果字符串的映射
     * @throws InterruptedException 等待调用线程被中断
     */
    private static Outcome callAll(DefaultCollapseExecutor<String, ?> executor,
                                   IntFunction<String> inputAt) throws InterruptedException {
        return invoke(executor, inputAt, false);
    }

    /**
     * 并发发起调用并收集失败调用者。
     *
     * @param executor 折叠执行器
     * @param inputAt  第 i 个调用者的入参
     * @return 失败入参到根因消息的映射
     * @throws InterruptedException 等待调用线程被中断
     */
    private static Outcome callAllExpectingFailure(DefaultCollapseExecutor<String, ?> executor,
                                                   IntFunction<String> inputAt)
            throws InterruptedException {
        return invoke(executor, inputAt, true);
    }

    /**
     * 并发发起 CALLERS 次调用。
     *
     * @param executor        折叠执行器
     * @param inputAt         第 i 个调用者的入参
     * @param collectFailures true 收集失败方，false 收集成功结果
     * @return 入参到结果或失败消息的映射
     * @throws InterruptedException 等待调用线程被中断
     */
    private static Outcome invoke(DefaultCollapseExecutor<String, ?> executor,
                                 IntFunction<String> inputAt,
                                 boolean collectFailures) throws InterruptedException {
        var sink = java.util.Collections.synchronizedSortedMap(new java.util.TreeMap<String, String>());
        var settled = new AtomicInteger();
        var started = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < CALLERS; i++) {
            var input = inputAt.apply(i);
            var thread = new Thread(() -> {
                awaitStarted(started);
                try {
                    var output = executor.execute(input);
                    if (!collectFailures) {
                        sink.merge(input, String.valueOf(output), (a, b) -> a);
                    }
                } catch (Throwable t) {
                    if (collectFailures) {
                        sink.merge(input, String.valueOf(rootMessage(t)), (a, b) -> a);
                    }
                } finally {
                    settled.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        started.countDown();
        for (var thread : threads) {
            thread.join(JOIN_TIMEOUT);
            if (thread.isAlive()) {
                observe("调用线程未在 " + JOIN_TIMEOUT + "ms 内结束，可能存在未完成的future");
            }
        }
        return new Outcome(sink, settled.get());
    }

    /**
     * 等待放行信号。
     *
     * @param latch 放行门闩
     */
    private static void awaitStarted(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 捕获一段代码写入标准错误输出的内容。
     *
     * @param body 被执行代码
     * @return 捕获结果
     * @throws InterruptedException 被测代码等待被中断
     */
    private static Captured captureStderr(Body body) throws InterruptedException {
        var original = System.err;
        var buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        var text = buffer.toString(StandardCharsets.UTF_8);
        return new Captured(text.lines().filter(l -> !l.isBlank()).toList());
    }

    /**
     * 允许抛出等待中断的被测代码块。
     */
    @FunctionalInterface
    private interface Body {

        /**
         * 执行被测代码。
         *
         * @throws InterruptedException 等待被中断
         */
        void run() throws InterruptedException;
    }

    /**
     * 数值化读取指标。
     *
     * @param value 指标值
     * @return 数值，取不到时返回 0
     */
    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    /**
     * 取根因消息。
     *
     * @param t 异常
     * @return 根因消息
     */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
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
     * @param ok   是否通过
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
     * 标准错误输出捕获结果。
     *
     * @param lines 非空行列表
     */
    private record Captured(List<String> lines) {

        /**
         * 规范构造器：非空行列表做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。</p>
         *
         * @param lines 非空行列表
         */
        public Captured {
            lines = List.copyOf(Objects.requireNonNull(lines, "lines 不能为 null"));
        }
    }

    /**
     * 一轮并发调用的收尾情况。
     *
     * @param byInput 入参到结果或失败消息的映射（相同入参只保留一条）
     * @param settled 完成调用的次数（相同入参会重复计数）
     */
    private record Outcome(Map<String, String> byInput, int settled) {

        /**
         * 规范构造器：入参到结果映射做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变。键来自入参、值经
         * {@code String.valueOf} 归一，均非空，故可用 {@code Map.copyOf}。</p>
         *
         * @param byInput 入参到结果或失败消息的映射（相同入参只保留一条）
         */
        public Outcome {
            byInput = Map.copyOf(Objects.requireNonNull(byInput, "byInput 不能为 null"));
        }

        /**
         * 把本次结果并入外部收集容器。
         *
         * @param target 目标容器
         */
        void drainInto(Map<String, String> target) {
            target.putAll(byInput);
        }
    }
}
