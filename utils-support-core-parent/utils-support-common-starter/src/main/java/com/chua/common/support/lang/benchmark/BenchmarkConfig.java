package com.chua.common.support.lang.benchmark;

import lombok.Builder;
import lombok.Data;

import java.util.Arrays;

/**
 * 压测配置：动态设置指标与场景（并发 / 吞吐双模式）。
 *
 * <p>支持两种通用压测模式：</p>
 * <ul>
 *   <li>{@link Mode#CONCURRENCY 并发} — N 个虚拟用户同时各发 1 次请求（flash），
 *       考验服务器同时接纳 N 个连接的能力（backlog / accept）。</li>
 *   <li>{@link Mode#THROUGHPUT 吞吐} — 固定并发连接数 × 每连接多请求（或固定时长），
 *       考验服务器高吞吐下的持续处理能力。</li>
 * </ul>
 *
 * <p>指标可动态配置：通过 {@link #metrics} 控制报告中展示哪些指标
 * （成功率 / RPS / p50 / p95 / p99 / p99.9 / max）。</p>
 *
 * @author CH
 * @since 2026/08/15
 */
@Data
@Builder
public class BenchmarkConfig {

    /**
     * 压测模式。
     */
    public enum Mode {
        /**
         * 并发模式：N 虚拟用户同时各发 1 次请求。
         */
        CONCURRENCY,
        /**
         * 吞吐模式：固定连接数 × 每连接多请求（持续吞吐）。
         */
        THROUGHPUT
    }

    /**
     * 报告指标项。
     */
    public enum Metric {
        SUCCESS_RATE, RPS, P50, P95, P99, P999, MAX
    }

    /**
     * 压测模式，默认并发。
     */
    @Builder.Default
    /**
     * 模式
    */
    private Mode mode = Mode.CONCURRENCY;

    /**
     * 目标 URL（必填），如 {@code http://127.0.0.1:8100/echo}。
     */
    private String targetUrl;

    /**
     * 并发等级数组（VUS / 连接数）。
     * <p>并发模式：VUS 数；吞吐模式：同时连接数。</p>
     */
    @Builder.Default
    /**
     * Concurrencylevels
    */
    private int[] concurrencyLevels = {100, 500, 1000, 2000, 5000};

    /**
     * 每 VU 迭代次数。
     * <ul>
     *   <li>并发模式：默认 1（flash，每 VU 各发 1 次）</li>
     *   <li>吞吐模式：每连接请求数，如 500</li>
     * </ul>
     */
    @Builder.Default
    /**
     * IterationsPERVUS
    */
    private int iterationsPerVus = 1;

    /**
     * 压测时长（秒），吞吐模式可选（>0 时按时长而非迭代数压测）。
     */
    @Builder.Default
    /**
     * 持续时间秒
    */
    private int durationSeconds = 0;

    /**
     * 报告指标列表，动态控制报告中展示的指标。
     */
    @Builder.Default
    /**
     * Metrics
    */
    private Metric[] metrics = {Metric.SUCCESS_RATE, Metric.RPS, Metric.P95, Metric.P99};

    /**
     * 服务器实现名称（报告分组用），如 jdk / nio / netty。
     */
    private String implementation;

    /**
     * 报告标题。
     */
    private String title;

    /**
     * 环境描述（OS / JDK / CPU / 内存）。
     */
    private String environment;

    /**
     * 压测工具描述。
     */
    private String tool;

    /**
     * 场景说明。
     */
    private String scenario;

    /**
     * 报告输出路径。
     */
    @Builder.Default
    /**
     * Report路径
    */
    private String reportPath = "target/http-server-benchmark.html";

    /**
     * k6 可执行文件路径；为空时使用系统 PATH 中的 "k6"。
     */
    private String k6Binary;

    /**
     * 输出目录（k6 summary JSON 临时文件目录），为空时使用系统临时目录。
     */
    private String workDir;

    /**
     * 是否保留 k6 summary JSON 中间文件。
     */
    @Builder.Default
    /**
     * KeepsummaryJSON
    */
    private boolean keepSummaryJson = false;

    /**
     * 判断是否展示指定指标。
     *
     * @param metric 指标
     * @return true 表示展示
     */
    public boolean shows(Metric metric) {
        if (metrics == null) {
            return true;
        }
        return Arrays.asList(metrics).contains(metric);
    }
}
