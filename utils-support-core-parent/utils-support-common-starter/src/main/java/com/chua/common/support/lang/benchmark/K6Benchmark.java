package com.chua.common.support.lang.benchmark;

import com.chua.common.support.lang.document.BenchmarkDocumentData;
import com.chua.common.support.lang.document.BenchmarkHtmlProvider;
import com.chua.common.support.lang.document.DocumentProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 k6 CLI 的压测引擎实现。
 *
 * <p>通过 {@code ProcessBuilder} 调用本机 k6 可执行文件执行压测：</p>
 * <ul>
 *   <li>并发模式（默认）：{@code k6 run --vus N --iterations N} — N 个 VU 同时各发 1 次请求</li>
 *   <li>吞吐模式：{@code k6 run --vus N --iterations N*perVus}（或 {@code --duration}）— 固定连接持续吞吐</li>
 *   <li>每次运行导出 summary JSON（{@code --summary-export}），解析后汇总到 {@link BenchmarkResult}</li>
 * </ul>
 *
 * <p>报告通过 {@link BenchmarkHtmlProvider}（ECharts）一键生成。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * try (Benchmark benchmark = Benchmark.create("k6")) {
 *     benchmark.configure(BenchmarkConfig.builder()
 *             .targetUrl("http://127.0.0.1:8100/echo")
 *             .mode(BenchmarkConfig.Mode.CONCURRENCY)
 *             .concurrencyLevels(new int[]{100, 500, 1000})
 *             .implementation("nio")
 *             .reportPath("target/http-bench.html")
 *             .build());
 *     BenchmarkResult result = benchmark.run();
 *     benchmark.report();
 * }
 * }</pre>
 *
 * @author CH
 * @since 2026/08/15
 */
@Slf4j
@Spi("k6")
public class K6Benchmark implements Benchmark {

    /**
     * k6 压测脚本（通过环境变量注入目标 URL）。
     */
    private static final String K6_SCRIPT = """
            import http from 'k6/http';
            export const options = {
              vus: __ENV.VUS,
              iterations: __ENV.ITERATIONS,
            };
            export default function () {
              http.get(__ENV.TARGET_URL);
            }
            """;

    /** 配置对象 */
    private BenchmarkConfig config = BenchmarkConfig.builder().build();
    /** 结果对象 */
    private BenchmarkResult result;

    @Override
    public String getType() {
        return "k6";
    }

    @Override
    public Benchmark configure(BenchmarkConfig config) {
        this.config = config != null ? config : BenchmarkConfig.builder().build();
        return this;
    }

    @Override
    public BenchmarkConfig config() {
        return config;
    }

    /**
     * 解析 k6 可执行文件路径：配置 > 环境变量 K6_BIN > 常见安装路径 > PATH。
     *
     * @return k6 可执行文件路径
     */
    private String resolveK6Binary() {
        if (config.getK6Binary() != null && !config.getK6Binary().isEmpty()) {
            return config.getK6Binary();
        }
        String env = System.getenv("K6_BIN");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        // 常见安装路径（Windows: winget 装到 C:/Program Files/k6/k6.exe）
        String[] candidates = {
                "C:/Program Files/k6/k6.exe",
                "C:/Program Files (x86)/k6/k6.exe",
                "/usr/local/bin/k6",
                "/usr/bin/k6"
        };
        for (String c : candidates) {
            if (new File(c).exists()) {
                return c;
            }
        }
        return "k6";
    }

    @Override
    public BenchmarkResult run() throws Exception {
        String targetUrl = config.getTargetUrl();
        if (targetUrl == null || targetUrl.isEmpty()) {
            throw new IllegalArgumentException("targetUrl 不能为空");
        }
        int[] levels = config.getConcurrencyLevels();
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("concurrencyLevels 不能为空");
        }
        String k6 = resolveK6Binary();
        Path workDir = config.getWorkDir() != null
                ? Path.of(config.getWorkDir()) : Files.createTempDirectory("k6bench-");

        // 写入 k6 脚本
        Path script = workDir.resolve("bench.js");
        Files.writeString(script, K6_SCRIPT, StandardCharsets.UTF_8);

        BenchmarkResult result = new BenchmarkResult();
        result.setConfig(config);
        for (int vus : levels) {
            int iterations = config.getIterationsPerVus() > 0
                    ? vus * config.getIterationsPerVus() : vus;
            Path summary = workDir.resolve("summary-" + vus + ".json");
            runK6(k6, script, summary, vus, iterations, targetUrl);
            if (!Files.exists(summary)) {
                log.warn("k6 未生成 summary 文件，跳过 VUS={}", vus);
                continue;
            }
            String json = Files.readString(summary, StandardCharsets.UTF_8);
            BenchmarkDocumentData.BenchmarkRow row =
                    BenchmarkDocumentData.fromK6SummaryJson(json, config.getImplementation(), vus);
            if (row.getTotal() == 0) {
                log.warn("VUS={} 无有效数据，跳过", vus);
            } else {
                result.add(row);
            }
            if (!config.isKeepSummaryJson()) {
                Files.deleteIfExists(summary);
            }
        }
        this.result = result;
        if (!config.isKeepSummaryJson()) {
            Files.deleteIfExists(script);
        }
        return result;
    }

    /**
     * 调用 k6 CLI 执行单档压测。
     */
    private void runK6(String k6, Path script, Path summary,
                       int vus, int iterations, String targetUrl) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(k6);
        cmd.add("run");
        cmd.add("--vus");
        cmd.add(String.valueOf(vus));
        if (config.getDurationSeconds() > 0) {
            cmd.add("--duration");
            cmd.add(config.getDurationSeconds() + "s");
        } else {
            cmd.add("--iterations");
            cmd.add(String.valueOf(iterations));
        }
        cmd.add("--summary-trend-stats=avg,min,med,max,p(90),p(95),p(99)");
        cmd.add("--summary-export=" + summary.toAbsolutePath());
        cmd.add("--env");
        cmd.add("VUS=" + vus);
        cmd.add("--env");
        cmd.add("ITERATIONS=" + iterations);
        cmd.add("--env");
        cmd.add("TARGET_URL=" + targetUrl);
        cmd.add(script.toAbsolutePath().toString());

        log.info("执行 k6: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            log.warn("k6 退出码 {}，输出尾部:\n{}", exit,
                    output.length() > 800 ? output.substring(output.length() - 800) : output);
        }
    }

    @Override
    public File report(String reportPath) throws Exception {
        if (result == null || result.getRows().isEmpty()) {
            throw new IllegalStateException("请先执行 run() 获取压测结果");
        }
        DocumentProvider provider = DocumentProvider.create("benchmark-html");
        if (provider instanceof BenchmarkHtmlProvider htmlProvider) {
            File out = new File(reportPath);
            htmlProvider.export(result.toDocumentData(), out, null);
            return out;
        }
        throw new IllegalStateException("未找到 benchmark-html 报告提供者");
    }

    @Override
    public void close() {
        // k6 为外部 CLI 进程，无需释放本地资源
    }
}
