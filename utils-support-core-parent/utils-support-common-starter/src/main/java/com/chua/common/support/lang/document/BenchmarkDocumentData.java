package com.chua.common.support.lang.document;

import com.chua.common.support.lang.json.Json;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 压测报告文档数据。
 *
 * <p>面向性能压测（k6 / wrk / ab 等）结果的可视化导出，
 * 包含环境信息与逐场景（实现 × 并发等级）的指标行。
 * 由 {@link BenchmarkHtmlProvider} 渲染为带图表的 HTML 报告。</p>
 *
 * <h2>数据来源</h2>
 * <p>可通过 {@link #fromK6SummaryJson(String, String, int)} 解析 k6 summary JSON
 * （{@code --summary-export} 输出）生成单场景行，或直接手工构造 {@link BenchmarkRow}。</p>
 *
 * @author CH
 * @since 2026/08/15
 */
@Data
public class BenchmarkDocumentData extends DocumentData {

    /**
     * 环境描述（OS / JDK / CPU / 内存）。
     */
    private String environment;

    /**
     * 压测工具描述，如 "k6 v2.2.0（真并发 flash 模式）"。
     */
    private String tool;

    /**
     * 场景说明（如 "GET /echo（128B），N 虚拟用户同时各发 1 次请求"）。
     */
    private String scenario;

    /**
     * 压测结果行。
     */
    private List<BenchmarkRow> rows = new ArrayList<>();

    /**
     * 压测场景行：实现 × 并发等级 × 指标。
     */
    @Data
    public static class BenchmarkRow {

        /**
         * 服务器实现（jdk / nio / netty ...）。
         */
        private String implementation;

        /**
         * 并发数（虚拟用户数 / 同时连接数）。
         */
        private int concurrency;

        /**
         * 总请求数。
         */
        private long total;

        /**
         * 失败请求数。
         */
        private long fails;

        /**
         * 吞吐量（req/s）。
         */
        private double rps;

        /**
         * p95 延迟（毫秒）。
         */
        private double p95;

        /**
         * p99 延迟（毫秒）。
         */
        private double p99;

        /**
         * 成功率（百分比，0~100）。
         *
         * @return 成功率
         */
        public double successRate() {
            return total > 0 ? (total - fails) * 100.0 / total : 0.0;
        }
    }

    /**
     * 解析单次 k6 summary JSON（{@code k6 run --summary-export=out.json}）为一行压测数据。
     *
     * <p>支持 k6 summary 中以下指标：</p>
     * <ul>
     *   <li>{@code http_req_duration.values.p(95)} / {@code p(99)} — 延迟毫秒</li>
     *   <li>{@code http_req_failed.values.fails} / {@code passes} — 失败/成功数</li>
     *   <li>{@code http_reqs.values.rate} — RPS</li>
     * </ul>
     *
     * @param k6SummaryJson k6 summary JSON 字符串
     * @param implementation 服务器实现名称
     * @param concurrency    并发数（VUS）
     * @return 压测结果行
     */
    @SuppressWarnings("unchecked")
    public static BenchmarkRow fromK6SummaryJson(String k6SummaryJson, String implementation, int concurrency) {
        Map<String, Object> root = Json.fromJson(k6SummaryJson, Map.class);
        Map<String, Object> metrics = root != null ? (Map<String, Object>) root.get("metrics") : null;
        BenchmarkRow row = new BenchmarkRow();
        row.setImplementation(implementation);
        row.setConcurrency(concurrency);

        if (metrics == null) {
            return row;
        }
        Map<String, Object> duration = (Map<String, Object>) metrics.get("http_req_duration");
        if (duration != null) {
            Map<String, Object> values = (Map<String, Object>) duration.get("values");
            if (values != null) {
                Number p95 = (Number) values.get("p(95)");
                Number p99 = (Number) values.get("p(99)");
                if (p95 != null) {
                    row.setP95(p95.doubleValue());
                }
                if (p99 != null) {
                    row.setP99(p99.doubleValue());
                }
            }
        }
        Map<String, Object> failed = (Map<String, Object>) metrics.get("http_req_failed");
        if (failed != null) {
            Map<String, Object> values = (Map<String, Object>) failed.get("values");
            if (values != null) {
                Number fails = (Number) values.get("fails");
                Number passes = (Number) values.get("passes");
                long f = fails != null ? fails.longValue() : 0L;
                long p = passes != null ? passes.longValue() : 0L;
                row.setFails(f);
                row.setTotal(f + p);
            }
        }
        Map<String, Object> reqs = (Map<String, Object>) metrics.get("http_reqs");
        if (reqs != null) {
            Map<String, Object> values = (Map<String, Object>) reqs.get("values");
            if (values != null) {
                Number rate = (Number) values.get("rate");
                if (rate != null) row.setRps(rate.doubleValue());
                Number count = (Number) values.get("count");
                if (count != null && row.getTotal() == 0) {
                    row.setTotal(count.longValue());
                }
            }
        }
        return row;
    }
}
