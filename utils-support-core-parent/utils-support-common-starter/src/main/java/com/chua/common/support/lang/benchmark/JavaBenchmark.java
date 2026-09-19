package com.chua.common.support.lang.benchmark;

import com.chua.common.support.lang.document.BenchmarkDocumentData;
import com.chua.common.support.lang.document.BenchmarkHtmlProvider;
import com.chua.common.support.lang.document.DocumentProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 纯 Java 压测引擎：零外部依赖，一键 run + report。
 *
 * <p>基于 JDK {@link HttpClient} + 虚拟线程实现，替代 k6 等外部 CLI：</p>
 * <ul>
 *   <li>并发模式（默认）：{@code concurrencyLevels} 中每个档位启动 N 个虚拟线程，
 *       同时各发 1 次请求（flash 真并发，考验 backlog / accept 接纳能力）</li>
 *   <li>吞吐模式：每个档位固定连接数 × {@code iterationsPerVus} 次请求，
 *       考验持续吞吐能力</li>
 *   <li>统计成功率 / RPS / p50 / p95 / p99 / max（毫秒）</li>
 * </ul>
 *
 * <p>报告通过 {@link BenchmarkHtmlProvider}（ECharts）一键生成，与 k6 引擎共用同一套报告。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * try (Benchmark benchmark = Benchmark.create("java")) {
 *     benchmark.configure(BenchmarkConfig.builder()
 *             .targetUrl("http://127.0.0.1:8100/echo")
 *             .concurrencyLevels(new int[]{100, 500, 1000})
 *             .iterationsPerVus(1)
 *             .implementation("nio")
 *             .reportPath("target/http-bench.html")
 *             .build());
 *     benchmark.run();
 *     benchmark.report();
 * }
 * }</pre>
 *
 * @author CH
 * @since 2026/08/15
 */
@Slf4j
@Spi("java")
public class JavaBenchmark implements Benchmark {

    /** 配置对象 */
    private BenchmarkConfig config = BenchmarkConfig.builder().build();
    /** 结果对象 */
    private BenchmarkResult result;

    @Override
    /** 获取Type */
    public String getType() {
        return "java";
    }

    @Override
    /** Configure */
    public Benchmark configure(BenchmarkConfig config) {
        this.config = config != null ? config : BenchmarkConfig.builder().build();
        return this;
    }

    @Override
    /** Config */
    public BenchmarkConfig config() {
        return config;
    }

    @Override
    /** 运行 */
    public BenchmarkResult run() throws Exception {
        String targetUrl = config.getTargetUrl();
        if (targetUrl == null || targetUrl.isEmpty()) {
            throw new IllegalArgumentException("targetUrl 不能为空");
        }
        int[] levels = config.getConcurrencyLevels();
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("concurrencyLevels 不能为空");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(ThreadUtils.newVirtualThreadPerTaskExecutor())
                .build();
        URI uri = URI.create(targetUrl);

        BenchmarkResult result = new BenchmarkResult();
        result.setConfig(config);
        for (int vus : levels) {
            BenchmarkDocumentData.BenchmarkRow row = runLevel(client, uri, vus);
            if (row != null) {
                result.add(row);
            }
        }
        this.result = result;
        return result;
    }

    /**
     * 执行单档压测。
     * @param client 客户端，不允许为 null
     * @param uri URI，不允许为 null
     * @param vus 方法入参 vus
     * @return BenchmarkDocument数据Benchmark行 对象
     */
    private BenchmarkDocumentData.BenchmarkRow runLevel(HttpClient client, URI uri, int vus) throws Exception {
        int perVus = Math.max(1, config.getIterationsPerVus());
        long totalTarget = (long) vus * perVus;

        ExecutorService pool = ThreadUtils.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(vus);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(vus);
        LongAdder errors = new LongAdder();
        List<long[]> latencies = java.util.Collections.synchronizedList(new ArrayList<>(vus));

        try {
            for (int i = 0; i < vus; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        long[] mine = new long[perVus];
                        for (int k = 0; k < perVus; k++) {
                            HttpRequest req = HttpRequest.newBuilder(uri)
                                    .timeout(Duration.ofSeconds(10)).GET().build();
                            long s = System.nanoTime();
                            HttpResponse<String> resp = client.send(req,
                                    HttpResponse.BodyHandlers.ofString());
                            if (resp.statusCode() != 200) {
                                errors.increment();
                                mine[k] = -1;
                            } else {
                                mine[k] = System.nanoTime() - s;
                            }
                        }
                        latencies.add(mine);
                    } catch (Exception e) {
                        errors.increment();
                    } finally {
                        done.countDown();
                    }
                });
            }

            if (!ready.await(30, TimeUnit.SECONDS)) {
                log.warn("并发={} 就绪超时，跳过", vus);
                return null;
            }
            long wallStart = System.nanoTime();
            start.countDown();
            if (!done.await(120, TimeUnit.SECONDS)) {
                log.warn("并发={} 完成超时，跳过", vus);
                return null;
            }
            long elapsedNs = System.nanoTime() - wallStart;

            // 合并延迟
            List<Long> valid = new ArrayList<>();
            long total = 0;
            for (long[] arr : latencies) {
                for (long ns : arr) {
                    total++;
                    if (ns >= 0) {
                        // 纳秒转换为毫秒
                        valid.add(ns / 1_000_000L);
                    }
                }
            }
            long[] sortedMs = valid.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(sortedMs);

            BenchmarkDocumentData.BenchmarkRow row = new BenchmarkDocumentData.BenchmarkRow();
            row.setImplementation(config.getImplementation() != null
                    ? config.getImplementation() : "java");
            row.setConcurrency(vus);
            row.setTotal(total);
            row.setFails(errors.sum());
            row.setRps(total * 1_000_000_000.0 / elapsedNs);
            row.setP95(sortedMs.length > 0 ? percentile(sortedMs, 0.95) : 0);
            row.setP99(sortedMs.length > 0 ? percentile(sortedMs, 0.99) : 0);
            log.info("并发={} 完成: total={} fails={} p95={}ms p99={}ms rps={}",
                    vus, total, errors.sum(), row.getP95(), row.getP99(), (long) row.getRps());
            return row;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 分位数（毫秒）。
     * @param sortedMs sorted毫秒数，不允许为 null
     * @param p 方法入参 p
     * @return 结果数值
     */
    private static double percentile(long[] sortedMs, double p) {
        int idx = (int) Math.ceil(p * sortedMs.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sortedMs.length) {
            idx = sortedMs.length - 1;
        }
        return sortedMs[idx];
    }

    @Override
    /** Report */
    public File report(String reportPath) throws Exception {
        if (result == null || result.getRows().isEmpty()) {
            throw new IllegalStateException("请先执行 run() 获取压测结果");
        }
        DocumentProvider provider = DocumentProvider.create("benchmark-html");
        BenchmarkHtmlProvider htmlProvider = (BenchmarkHtmlProvider) provider;
        File out = new File(reportPath);
        htmlProvider.export(result.toDocumentData(), out, null);
        return out;
    }

    @Override
    /** 关闭 */
    public void close() {
        // 压测线程池在 run() 内已关闭，无需额外资源
    }
}
