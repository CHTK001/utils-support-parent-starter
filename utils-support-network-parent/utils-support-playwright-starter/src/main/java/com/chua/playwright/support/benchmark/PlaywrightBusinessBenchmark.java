package com.chua.playwright.support.benchmark;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
* Playwright 业务压测框架。
*
* <p>用真实浏览器驱动执行业务流程（打开页面 / 填写 / 点击 / 等待 / 断言 / 截图），
* 支持配置并发用户数与每用户迭代次数，统计吞吐量与延迟分位数，输出包含
* 业务截图（基础64 内嵌）、测试点清单、测试参数的业务 HTML 压测报告。</p>
*
* <p>并发与吞吐量指标口径与接口压测一致：总执行数 / 错误率 / RPS / p50 / p95 / p99 / max。</p>
*
* <pre>{@code
* PlaywrightBusinessBenchmark benchmark = PlaywrightBusinessBenchmark.builder()
*         .baseUrl("https://example.com")
*         .step(BusinessStep.open("打开首页", "/login"))
*         .step(BusinessStep.fill("输入用户名", "#username", "admin"))
*         .step(BusinessStep.fill("输入密码", "#password", "123456"))
*         .step(BusinessStep.click("点击登录", "#login-btn"))
*         .step(BusinessStep.waitFor("等待首页加载", "#dashboard", 10000))
*         .step(BusinessStep.assertText("断言欢迎语", "#welcome", "欢迎"))
*         .step(BusinessStep.screenshot("登录成功截图"))
*         .concurrency(4)
*         .iterations(10)
*         .reportPath("target/playwright-business.html")
*         .build();
* benchmark.run();
* }</pre>rency(4)
*         .iterations(10)
*         .reportPath("target/playwright-business.html")
*         .build();
* benchmark.run();
* }</pre>
*
* @author CH
* @since 2026/08/15
 */
@Slf4j
public class PlaywrightBusinessBenchmark {

    /** 业务测试点类型 */
    public enum StepType {
        /** 打开页面（url 为相对路径，拼接 baseurl） */
        OPEN,
        /** 填充表单（selector + 值） */
        FILL,
        /** 点击（selector） */
        CLICK,
        /** 等待元素出现（selector + 超时ms） */
        WAIT_FOR,
        /** 断言文本包含（selector + 值） */
        ASSERT_TEXT,
        /** 截图（值 为截图说明） */
        SCREENSHOT
    }

    /** 单个业务测试点 */
    public static final class BusinessStep {
        /**
        * 名称
         */
        private final String name;
        /**
        * 类型
         */
        private final StepType type;
        /**
        * selector
         */
        private final String selector;
        /**
        * 值
         */
        private final String value;
        /**
        * 超时时间（毫秒）
         */
        private final long timeoutMs;

        /**
        * 创建 业务step 实例
        * @param name 名称
        * @param type step类型
        * @param name 字符串
        * @param name 字符串
        * @param timeoutMs long
        * @param type 类型
        * @param selector selector
        * @param value 值
        * @param timeoutMs 超时ms
        * @return 业务step的结果
         */
        private BusinessStep(String name, StepType type, String selector, String value, long timeoutMs) {
            this.name = name;
            this.type = type;
            this.selector = selector;
            this.value = value;
            this.timeoutMs = timeoutMs;
        }

        /**
        * 获取名称
        *
        * @return 获取名称的结果
         */
        public String getName() {
            return name;
        }

        /**
        * 获取类型
        *
        * @return 获取类型的结果
         */
        public StepType getType() {
            return type;
        }

        /**
        * 获取Selector
        *
        * @return 获取selector的结果
         */
        public String getSelector() {
            return selector;
        }

        /**
        * 获取值
        *
        * @return 获取值的结果
         */
        public String getValue() {
            return value;
        }

        /**
        * 获取超时ms
        *
        * @return 获取超时ms的结果
         */
        public long getTimeoutMs() {
            return timeoutMs;
        }

        /**
        * 打开
        *
        * @param name 名称
        * @param url url
        * @return 打开的结果
         */
        public static BusinessStep open(String name, String url) {
            return new BusinessStep(name, StepType.OPEN, null, url, 30000);
        }

        /**
        * Fill
        *
        * @param name 名称
        * @param selector selector
        * @param value 值
        * @return fill的结果
         */
        public static BusinessStep fill(String name, String selector, String value) {
            return new BusinessStep(name, StepType.FILL, selector, value, 10000);
        }

        /**
        * Click
        *
        * @param name 名称
        * @param selector selector
        * @return click的结果
         */
        public static BusinessStep click(String name, String selector) {
            return new BusinessStep(name, StepType.CLICK, selector, null, 10000);
        }

        /**
        * waitfor
        *
        * @param name 名称
        * @param selector selector
        * @param timeoutMs 超时ms
        * @return waitFor的结果
         */
        public static BusinessStep waitFor(String name, String selector, long timeoutMs) {
            return new BusinessStep(name, StepType.WAIT_FOR, selector, null, timeoutMs);
        }

        /**
        * 断言文本
        *
        * @param name 名称
        * @param selector selector
        * @param expected 期望
        * @return 断言文本的结果
         */
        public static BusinessStep assertText(String name, String selector, String expected) {
            return new BusinessStep(name, StepType.ASSERT_TEXT, selector, expected, 10000);
        }

        /**
        * Screenshot
        *
        * @param name 名称
        * @return screenshot的结果
         */
        public static BusinessStep screenshot(String name) {
            return new BusinessStep(name, StepType.SCREENSHOT, null, null, 0);
        }
    }

    /** 单个测试点执行结果 */
    public static final class StepResult {
        /**
        * 名称
         */
        private final String name;
        /**
        * 类型
         */
        private final StepType type;
        /**
        * 是否成功
         */
        private final boolean success;
        /**
        * 耗时（毫秒）
         */
        private final long latencyMs;
        /**
        * 详情说明
         */
        private final String detail;

        StepResult(String name, StepType type, boolean success, long latencyMs, String detail) {
            this.name = name;
            this.type = type;
            this.success = success;
            this.latencyMs = latencyMs;
            this.detail = detail;
        }

        /**
        * 获取名称
        *
        * @return 获取名称的结果
         */
        public String getName() {
            return name;
        }

        /**
        * 获取类型
        *
        * @return 获取类型的结果
         */
        public StepType getType() {
            return type;
        }

        /**
        * 是否成功
        *
        * @return 是否成功的结果
         */
        public boolean isSuccess() {
            return success;
        }

        /**
        * 获取延迟ms
        *
        * @return 获取延迟ms的结果
         */
        public long getLatencyMs() {
            return latencyMs;
        }

        /**
        * 获取Detail
        *
        * @return 获取detail的结果
         */
        public String getDetail() {
            return detail;
        }
    }

    /** 一个并发用户的一次完整业务流程执行结果 */
    public static final class BusinessRun {
        /**
        * 步骤执行结果列表
         */
        private final List<StepResult> stepResults;
        /**
        * 总耗时（毫秒）
         */
        private final long totalLatencyMs;
        /**
        * 是否成功
         */
        private final boolean success;
        /**
        * 截图 基础64 编码
         */
        private final String screenshotBase64;

        BusinessRun(List<StepResult> stepResults, long totalLatencyMs, boolean success, String screenshotBase64) {
            this.stepResults = stepResults;
            this.totalLatencyMs = totalLatencyMs;
            this.success = success;
            this.screenshotBase64 = screenshotBase64;
        }

        /**
        * 获取step结果
        *
        * @return 获取step结果的结果
         */
        public List<StepResult> getStepResults() {
            return stepResults;
        }

        /**
        * 获取总计延迟ms
        *
        * @return 获取total延迟ms的结果
         */
        public long getTotalLatencyMs() {
            return totalLatencyMs;
        }

        /**
        * 是否成功
        *
        * @return 是否成功的结果
         */
        public boolean isSuccess() {
            return success;
        }

        /**
        * 获取screenshotbase
        *
        * @return 获取screenshotbase64的结果
         */
        public String getScreenshotBase64() {
            return screenshotBase64;
        }
    }

    // ==================== 配置 ====================

    /**
    * 基础地址
     */
    private final String baseUrl;
    /**
    * 业务测试点列表
     */
    private final List<BusinessStep> steps;
    /**
    * 并发用户数
     */
    private final int concurrency;
    /**
    * 迭代次数
     */
    private final int iterations;
    /**
    * 报告输出路径
     */
    private final String reportPath;
    /**
    * 是否无头模式
     */
    private final boolean headless;
    /**
    * 浏览器可执行文件路径
     */
    private final String executablePath;
    /**
    * 步骤间等待时间（毫秒）
     */
    private final long waitAfterStepMs;

    /**
    * 创建 playwright业务benchmark 实例
    * @param baseUrl baseurl
    * @param steps steps
    * @param concurrency concurrency
    * @param iterations iterations
    * @param reportPath report路径
    * @param headless headless
    * @param executablePath executable路径
    * @param waitAfterStepMs wait之后stepms
     */
    private PlaywrightBusinessBenchmark(String baseUrl, List<BusinessStep> steps, int concurrency,
                                        int iterations, String reportPath, boolean headless,
                                        String executablePath, long waitAfterStepMs) {
        this.baseUrl = baseUrl;
        this.steps = steps;
        this.concurrency = concurrency;
        this.iterations = iterations;
        this.reportPath = reportPath;
        this.headless = headless;
        this.executablePath = executablePath;
        this.waitAfterStepMs = waitAfterStepMs;
    }

    /**
    * 构建器
    *
    * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        /**
        * 基础地址
         */
        private String baseUrl = "http://127.0.0.1:8080";
        /**
        * 业务测试点列表
         */
        private final List<BusinessStep> steps = new ArrayList<>();
        /**
        * 并发用户数
         */
        private int concurrency = 4;
        /**
        * 迭代次数
         */
        private int iterations = 10;
        /**
        * 报告输出路径
         */
        private String reportPath = "target/playwright-business.html";
        /**
        * 是否无头模式
         */
        private boolean headless = true;
        /**
        * 浏览器可执行文件路径
         */
        private String executablePath;
        /**
        * 步骤间等待时间（毫秒）
         */
        private long waitAfterStepMs = 0;

        /**
        * baseurl
        *
        * @param baseUrl baseurl
        * @return baseUrl的结果
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
        * Step
        *
        * @param step step
        * @return step的结果
         */
        public Builder step(BusinessStep step) {
            this.steps.add(step);
            return this;
        }

        /**
        * Steps
        *
        * @param steps steps
        * @return steps的结果
         */
        public Builder steps(List<BusinessStep> steps) {
            this.steps.addAll(steps);
            return this;
        }

        /**
        * Concurrency
        *
        * @param concurrency concurrency
        * @return concurrency的结果
         */
        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        /**
        * Iterations
        *
        * @param iterations iterations
        * @return iterations的结果
         */
        public Builder iterations(int iterations) {
            this.iterations = iterations;
            return this;
        }

        /**
        * report路径
        *
        * @param reportPath report路径
        * @return report路径的结果
         */
        public Builder reportPath(String reportPath) {
            this.reportPath = reportPath;
            return this;
        }

        /**
        * Headless
        *
        * @param headless headless
        * @return headless的结果
         */
        public Builder headless(boolean headless) {
            this.headless = headless;
            return this;
        }

        /**
        * executable路径
        *
        * @param executablePath executable路径
        * @return executable路径的结果
         */
        public Builder executablePath(String executablePath) {
            this.executablePath = executablePath;
            return this;
        }

        /**
        * wait之后stepms
        *
        * @param waitAfterStepMs wait之后stepms
        * @return wait之后stepms的结果
         */
        public Builder waitAfterStepMs(long waitAfterStepMs) {
            this.waitAfterStepMs = waitAfterStepMs;
            return this;
        }

        /**
        * 构建
        *
        * @return 构建的结果
         */
        public PlaywrightBusinessBenchmark build() {
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("至少需要一个业务测试点");
            }
            return new PlaywrightBusinessBenchmark(baseUrl, Collections.unmodifiableList(new ArrayList<>(steps)),
                    concurrency, iterations, reportPath, headless, executablePath, waitAfterStepMs);
        }
    }

    // ==================== 压测执行 ====================

    /**
    * 执行业务压测并输出 HTML 报告。
    *
    * @return 汇总结果
     */
    public BenchmarkSummary run() {
        List<BusinessRun> allRuns = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalElapsedMs = new AtomicLong();
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(headless);
            if (executablePath != null && !executablePath.isEmpty()) {
                launchOptions.setExecutablePath(Path.of(executablePath));
            }
            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                CountDownLatch ready = new CountDownLatch(concurrency);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(concurrency);
                AtomicLong startWallNanos = new AtomicLong();

                for (int i = 0; i < concurrency; i++) {
                    final int workerIdx = i;
                    pool.submit(() -> {
                        BrowserContext context = null;
                        try {
                            context = browser.newContext();
                            Page page = context.newPage();
                            page.setDefaultTimeout(30000);
                            page.setDefaultNavigationTimeout(30000);
                            ready.countDown();
                            start.await();
                            for (int k = 0; k < iterations; k++) {
                                BusinessRun run = executeBusiness(page, workerIdx, k);
                                if (run != null) {
                                    allRuns.add(run);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[playwright-bench] worker {} 异常: {}", workerIdx, e.getMessage());
                        } finally {
                            if (context != null) {
                                context.close();
                            }
                            done.countDown();
                        }
                    });
                }

                if (!ready.await(60, TimeUnit.SECONDS)) {
                    log.warn("[playwright-bench] 并发就绪超时");
                    start.countDown();
                }
                startWallNanos.set(System.nanoTime());
                start.countDown();
                if (!done.await(600, TimeUnit.SECONDS)) {
                    log.warn("[playwright-bench] 压测超时(10min)");
                }
                long elapsedNs = System.nanoTime() - startWallNanos.get();
                totalElapsedMs.set(elapsedNs / 1_000_000L);
                pool.shutdownNow();
            }
        } catch (Exception e) {
            log.error("[playwright-bench] 压测失败", e);
        }

        BenchmarkSummary summary = new BenchmarkSummary(allRuns, totalElapsedMs.get(), concurrency, iterations);
        writeReport(summary);
        log.info("[playwright-bench] 完成: total={} errors={} rps={:.0f} p50={}ms p95={}ms p99={}ms",
                summary.total, summary.errors, summary.rps(), summary.p50, summary.p95, summary.p99);
        return summary;
    }

    /**
    * 执行单个用户的单次业务流程。
    * @param page page
    * @param workerIdx 工人idx
    * @param iterIdx iteridx
    * @return 执行业务的结果
     */
    private BusinessRun executeBusiness(Page page, int workerIdx, int iterIdx) {
        List<StepResult> results = new ArrayList<>();
        long flowStart = System.nanoTime();
        boolean success = true;
        String shotBase64 = null;
        for (BusinessStep step : steps) {
            long stepStart = System.nanoTime();
            try {
                switch (step.getType()) {
                    case OPEN -> {
                        String url = step.getValue().startsWith("http") ? step.getValue() : baseUrl + step.getValue();
                        page.navigate(url, new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.LOAD));
                    }
                    case FILL -> page.fill(step.getSelector(), step.getValue());
                    case CLICK -> page.click(step.getSelector());
                    case WAIT_FOR -> page.waitForSelector(step.getSelector(),
                            new Page.WaitForSelectorOptions().setTimeout(step.getTimeoutMs()));
                    case ASSERT_TEXT -> {
                        String text = page.textContent(step.getSelector());
                        if (text == null || !text.contains(step.getValue())) {
                            throw new AssertionError("文本断言失败, 期望包含: " + step.getValue() + ", 实际: " + text);
                        }
                    }
                    case SCREENSHOT -> {
                        byte[] png = page.screenshot(new Page.ScreenshotOptions()
                                .setFullPage(true).setType(com.microsoft.playwright.options.ScreenshotType.PNG));
                        shotBase64 = Base64.getEncoder().encodeToString(png);
                    }
                    default -> throw new IllegalStateException("未知测试点类型: " + step.getType());
                }
                long latMs = (System.nanoTime() - stepStart) / 1_000_000L;
                results.add(new StepResult(step.getName(), step.getType(), true, latMs, "OK"));
                if (waitAfterStepMs > 0) {
                    Thread.sleep(waitAfterStepMs);
                }
            } catch (Throwable t) {
                long latMs = (System.nanoTime() - stepStart) / 1_000_000L;
                results.add(new StepResult(step.getName(), step.getType(), false, latMs,
                        String.valueOf(t.getMessage())));
                success = false;
                break;
            }
        }
        long totalLatMs = (System.nanoTime() - flowStart) / 1_000_000L;
        return new BusinessRun(results, totalLatMs, success, shotBase64);
    }

    // ==================== 报告 ====================

    /**
    * 压测汇总结果。
    *
    * <p>指标口径与接口压测一致：总执行 / 失败 / 成功率 / 吞吐量(RPS) /
    * 平均耗时 / p50 / p95 / p99 / p99.9 / 最大耗时 / 标准差。</p>
     */
    public static final class BenchmarkSummary {
        /**
        * 运行结果列表
         */
        private final List<BusinessRun> runs;
        /**
        * 总耗时（毫秒）
         */
        private final long elapsedMs;
        /**
        * 并发用户数
         */
        private final int concurrency;
        /**
        * 迭代次数
         */
        private final int iterations;
        /**
        * 总数
         */
        private final long total;
        /**
        * 错误次数
         */
        private final long errors;
        /**
        * p50 分位耗时（毫秒）
         */
        private final double p50;
        /**
        * p95 分位耗时（毫秒）
         */
        private final double p95;
        /**
        * p99 分位耗时（毫秒）
         */
        private final double p99;
        /**
        * p99.9 分位耗时（毫秒）
         */
        private final double p999;
        /**
        * 最大耗时（毫秒）
         */
        private final double max;
        /**
        * 平均耗时（毫秒）
         */
        private final double mean;
        /**
        * 耗时标准差（毫秒）
         */
        private final double stddev;

        BenchmarkSummary(List<BusinessRun> runs, long elapsedMs, int concurrency, int iterations) {
            this.runs = runs;
            this.elapsedMs = elapsedMs;
            this.concurrency = concurrency;
            this.iterations = iterations;
            this.total = runs.size();
            this.errors = runs.stream().filter(r -> !r.isSuccess()).count();
            long[] lat = runs.stream().mapToLong(BusinessRun::getTotalLatencyMs).sorted().toArray();
            this.p50 = percentile(lat, 0.50);
            this.p95 = percentile(lat, 0.95);
            this.p99 = percentile(lat, 0.99);
            this.p999 = percentile(lat, 0.999);
            this.max = lat.length > 0 ? lat[lat.length - 1] : 0;
            this.mean = lat.length > 0 ? Arrays.stream(lat).average().orElse(0) : 0;
            this.stddev = stddev(lat, mean);
        }

        /**
        * 获取运行
        *
        * @return 获取运行的结果
         */
        public List<BusinessRun> getRuns() {
            return runs;
        }

        /**
        * 获取elapsedms
        *
        * @return 获取elapsedms的结果
         */
        public long getElapsedMs() {
            return elapsedMs;
        }

        /**
        * 获取总计
        *
        * @return 获取total的结果
         */
        public long getTotal() {
            return total;
        }

        /**
        * 获取错误
        *
        * @return 获取错误的结果
         */
        public long getErrors() {
            return errors;
        }

        /**
        * 获取成功rate
        *
        * @return 获取成功rate的结果
         */
        public double getSuccessRate() {
            return total > 0 ? (total - errors) * 100.0 / total : 0;
        }

        /**
        * Rps
        *
        * @return rps的结果
         */
        public double rps() {
            return elapsedMs > 0 ? total * 1000.0 / elapsedMs : 0;
        }

        /**
        * 获取
        *
        * @return 获取p50的结果
         */
        public double getP50() {
            return p50;
        }

        /**
        * 获取
        *
        * @return 获取p95的结果
         */
        public double getP95() {
            return p95;
        }

        /**
        * 获取
        *
        * @return 获取p99的结果
         */
        public double getP99() {
            return p99;
        }

        /**
        * 获取
        *
        * @return 获取p999的结果
         */
        public double getP999() {
            return p999;
        }

        /**
        * 获取最大值
        *
        * @return 获取最大的结果
         */
        public double getMax() {
            return max;
        }

        /**
        * 获取Mean
        *
        * @return 获取mean的结果
         */
        public double getMean() {
            return mean;
        }

        /**
        * 获取Stddev
        *
        * @return 获取stddev的结果
         */
        public double getStddev() {
            return stddev;
        }

        /**
        * 获取Concurrency
        *
        * @return 获取concurrency的结果
         */
        public int getConcurrency() {
            return concurrency;
        }

        /**
        * 获取Iterations
        *
        * @return 获取iterations的结果
         */
        public int getIterations() {
            return iterations;
        }

        /**
        * Percentile
        *
        * @param sortedMs 排序ms
        * @param p p
        * @return percentile的结果
         */
        private static double percentile(long[] sortedMs, double p) {
            if (sortedMs.length == 0) {
                return 0;
            }
            int idx = (int) Math.ceil(p * sortedMs.length) - 1;
            idx = Math.max(0, Math.min(idx, sortedMs.length - 1));
            return sortedMs[idx];
        }

        /**
        * Stddev
        *
        * @param sortedMs 排序ms
        * @param meanMs meanms
        * @return stddev的结果
         */
        private static double stddev(long[] sortedMs, double meanMs) {
            if (sortedMs.length == 0) {
                return 0;
            }
            double sum = 0;
            for (long ms : sortedMs) {
                double d = ms - meanMs;
                sum += d * d;
            }
            return Math.sqrt(sum / sortedMs.length);
        }
    }

    /**
    * 生成业务压测 HTML 报告（含截图、测试点、测试参数、结果）。
    * @param summary summary
     */
    private void writeReport(BenchmarkSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<title>Playwright 业务压测报告</title>\n");
        sb.append("<style>\n")
                .append("body{font-family:'Microsoft YaHei',Arial,sans-serif;margin:24px;background:#f7f8fa;color:#333;}\n")
                .append("h1{color:#1a73e8;}h2{color:#333;border-bottom:2px solid #1a73e8;padding-bottom:4px;}\n")
                .append("table{border-collapse:collapse;width:100%;background:#fff;margin:8px 0 20px;}\n")
                .append("th,td{border:1px solid #ddd;padding:8px 10px;text-align:left;}\n")
                .append("th{background:#1a73e8;color:#fff;text-align:center;}\n")
                .append("tr:nth-child(even){background:#f1f5fb;}\n")
                .append(".ok{color:#188038;font-weight:bold;}.err{color:#d93025;font-weight:bold;}\n")
                .append(".shot{max-width:100%;border:1px solid #ccc;margin:8px 0 16px;}\n")
                .append(".env{background:#eef3ff;padding:10px 14px;border-left:4px solid #1a73e8;margin:8px 0 16px;}\n")
                .append("</style>\n</head>\n<body>\n");
        sb.append("<h1>Playwright 业务压测报告</h1>\n");
        sb.append("<p>生成时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>\n");

        // 测试参数
        sb.append("<h2>测试参数</h2>\n<table>\n");
        sb.append("<tr><th>参数</th><th>值</th></tr>\n");
        sb.append("<tr><td>业务地址</td><td>").append(esc(baseUrl)).append("</td></tr>\n");
        sb.append("<tr><td>并发用户数</td><td>").append(concurrency).append("</td></tr>\n");
        sb.append("<tr><td>每用户迭代次数</td><td>").append(iterations).append("</td></tr>\n");
        sb.append("<tr><td>测试点数量</td><td>").append(steps.size()).append("</td></tr>\n");
        sb.append("<tr><td>浏览器</td><td>Chromium (headless=").append(headless).append(")</td></tr>\n");
        sb.append("<tr><td>执行环境</td><td>").append(esc(System.getProperty("java.version") + " / "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch"))).append("</td></tr>\n");
        sb.append("</table>\n");

        // 测试点清单
        sb.append("<h2>业务测试点</h2>\n<table>\n");
        sb.append("<tr><th>#</th><th>测试点</th><th>类型</th><th>选择器</th><th>参数</th></tr>\n");
        int idx = 1;
        for (BusinessStep s : steps) {
            sb.append("<tr><td>").append(idx++).append("</td><td>").append(esc(s.getName()))
                    .append("</td><td>").append(s.getType())
                    .append("</td><td>").append(esc(String.valueOf(s.getSelector())))
                    .append("</td><td>").append(esc(String.valueOf(s.getValue()))).append("</td></tr>\n");
        }
        sb.append("</table>\n");

 // 压测结果（与接口压测对齐：总执行/成功率/RPS/p50/p95/p99/最大/平均）
        sb.append("<h2>压测结果</h2>\n<table>\n");
        sb.append("<tr><th>指标</th><th>值</th></tr>\n");
        sb.append("<tr><td>总执行次数</td><td>").append(summary.total).append("</td></tr>\n");
        sb.append("<tr><td>失败次数</td><td>").append(summary.errors).append("</td></tr>\n");
        sb.append("<tr><td>成功率</td><td>").append(String.format(Locale.ROOT, "%.2f%%", summary.getSuccessRate()))
                .append("</td></tr>\n");
        sb.append("<tr><td>总耗时</td><td>").append(summary.elapsedMs).append(" ms</td></tr>\n");
        sb.append("<tr><td>吞吐量</td><td>").append(String.format(Locale.ROOT, "%.2f", summary.rps()))
                .append(" 业务/秒</td></tr>\n");
        sb.append("<tr><td>平均耗时</td><td>").append(String.format(Locale.ROOT, "%.1f", summary.mean))
                .append(" ms</td></tr>\n");
        sb.append("<tr><td>耗时标准差</td><td>").append(String.format(Locale.ROOT, "%.1f", summary.stddev))
                .append(" ms</td></tr>\n");
        sb.append("<tr><td>p50</td><td>").append(String.format(Locale.ROOT, "%.1f", summary.p50))
                .append(" ms</td></tr>\n");
        sb.append("<tr><td>p95</td><td>").append(String.format(Locale.ROOT, "%.1f", summary.p95))
                .append(" ms</td></tr>\n");
        sb.append("<tr><td>p99</td><td>").append(String.format(Locale.ROOT, "%.1f", summary.p99))
                .append(" ms</td></tr>\n");
        sb.append("<tr><td>p99.9</td><td>").append(String.format(Locale.ROOT, "%.1f", summary.p999))
                .append(" ms</td></tr>\n");
        sb.append("<tr><td>最大耗时</td><td>").append(String.format(Locale.ROOT, "%.1f", summary.max))
                .append(" ms</td></tr>\n");
        sb.append("</table>\n");

        // 业务截图（取最近一次成功的运行）
        BusinessRun shotRun = null;
        for (int i = summary.runs.size() - 1; i >= 0; i--) {
            if (summary.runs.get(i).getScreenshotBase64() != null) {
                shotRun = summary.runs.get(i);
                break;
            }
        }
        sb.append("<h2>业务截图</h2>\n");
        if (shotRun != null && shotRun.getScreenshotBase64() != null) {
            sb.append("<img class=\"shot\" src=\"data:image/png;base64,")
                    .append(shotRun.getScreenshotBase64()).append("\" alt=\"业务截图\"/>\n");
        } else {
            sb.append("<p class=\"err\">未捕获到截图（请确认业务流程包含 screenshot 测试点且执行成功）</p>\n");
        }

        // 最近一次运行的测试点明细
        sb.append("<h2>最近一次运行测试点明细</h2>\n<table>\n");
        sb.append("<tr><th>测试点</th><th>类型</th><th>状态</th><th>耗时(ms)</th><th>说明</th></tr>\n");
        List<StepResult> lastSteps = summary.runs.isEmpty()
                ? Collections.emptyList() : summary.runs.get(summary.runs.size() - 1).getStepResults();
        for (StepResult r : lastSteps) {
            sb.append("<tr><td>").append(esc(r.getName())).append("</td><td>").append(r.getType())
                    .append("</td><td class=\"").append(r.isSuccess() ? "ok" : "err")
                    .append("\">").append(r.isSuccess() ? "通过" : "失败")
                    .append("</td><td>").append(r.getLatencyMs())
                    .append("</td><td>").append(esc(String.valueOf(r.getDetail()))).append("</td></tr>\n");
        }
        sb.append("</table>\n</body>\n</html>\n");

        try {
            Path path = Path.of(reportPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            log.info("[playwright-bench] 业务压测报告已写入: {}", reportPath);
        } catch (IOException e) {
            log.warn("[playwright-bench] 报告写入失败: {}", e.getMessage());
        }
    }

    /**
    * Esc
    *
    * @param s s
    * @return esc的结果
     */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
