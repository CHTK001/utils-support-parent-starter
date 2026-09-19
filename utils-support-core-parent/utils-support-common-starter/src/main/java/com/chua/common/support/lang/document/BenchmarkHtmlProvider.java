package com.chua.common.support.lang.document;

import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 压测报告 HTML 导出器（基于 ECharts CDN 渲染图表）。
 *
 * <p>将 {@link BenchmarkDocumentData} 渲染为单页 HTML 压测报告，
 * 包含：</p>
 * <ul>
 *   <li>逐场景明细表（实现 × 并发 × 成功率 / RPS / p95 / p99）</li>
 *   <li>ECharts 图表：成功率、p95、p99、RPS 对比</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * BenchmarkDocumentData data = BenchmarkDocumentData.fromK6SummaryJson(json, "jdk", 500);
 * DocumentProvider.create("benchmark-html").export(data, new File("report.html"));
 * }</pre>
 *
 * @author CH
 * @since 2026/08/15
 */
@Spi("benchmark-html")
public class BenchmarkHtmlProvider implements DocumentProvider {

    /**
     * ECharts CDN 地址
    */
    private static final String ECHARTS_CDN =
            "https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js";

    @Override
    /**
     * 获取Type
    */
    public String getType() {
        return "benchmark-html";
    }

    @Override
    /**
     * 获取Extensions
    */
    public String[] getExtensions() {
        return new String[]{".html", ".htm"};
    }

    @Override
    /**
     * Export
    */
    public void export(DocumentData data, File outputFile, DocumentExportConfig config) {
        if (!(data instanceof BenchmarkDocumentData benchmark)) {
            throw new IllegalArgumentException(
                    "BenchmarkHtmlProvider 仅支持 BenchmarkDocumentData，实际收到: "
                            + (data == null ? "null" : data.getClass().getName()));
        }
        String html = render(benchmark);
        write(outputFile, html);
    }

    /**
     * 渲染 HTML 报告。
     * @param data 数据，不允许为 null
     * @return 结果字符串
     */
    private String render(BenchmarkDocumentData data) {
        List<BenchmarkDocumentData.BenchmarkRow> rows = new ArrayList<>(data.getRows());
        rows.sort(Comparator.comparing(BenchmarkDocumentData.BenchmarkRow::getImplementation)
                .thenComparingInt(BenchmarkDocumentData.BenchmarkRow::getConcurrency));

        // 实现名列表（保持出现顺序）
        List<String> impls = new ArrayList<>();
        for (BenchmarkDocumentData.BenchmarkRow r : rows) {
            if (!impls.contains(r.getImplementation())) {
                impls.add(r.getImplementation());
            }
        }
        // 并发等级列表
        List<Integer> vus = new ArrayList<>();
        for (BenchmarkDocumentData.BenchmarkRow r : rows) {
            if (!vus.contains(r.getConcurrency())) {
                vus.add(r.getConcurrency());
            }
        }
        vus.sort(Integer::compareTo);

        StringBuilder table = new StringBuilder();
        for (String impl : impls) {
            table.append("<tr class=\"impl-header\"><td colspan=\"7\"><b>")
                    .append(escape(impl.toUpperCase())).append(" HttpServer</b></td></tr>\n");
            for (Integer v : vus) {
                BenchmarkDocumentData.BenchmarkRow r = find(rows, impl, v);
                if (r == null) {
                    continue;
                }
                double rate = r.successRate();
                String cls = rate >= 99.99 ? "ok" : (rate >= 90 ? "warn" : "bad");
                table.append("<tr><td>").append(v).append("</td>")
                        .append("<td>").append(r.getTotal()).append("</td>")
                        .append("<td>").append(r.getFails()).append("</td>")
                        .append("<td class=\"").append(cls).append("\">")
                        .append(String.format(java.util.Locale.ROOT, "%.2f%%", rate)).append("</td>")
                        .append("<td>").append(String.format(java.util.Locale.ROOT, "%.0f", r.getRps())).append("</td>")
                        .append("<td>").append(String.format(java.util.Locale.ROOT, "%.1f", r.getP95())).append("</td>")
                        .append("<td>").append(String.format(java.util.Locale.ROOT, "%.1f", r.getP99())).append("</td>")
                        .append("</tr>\n");
            }
        }

        // 构造 ECharts 数据
        StringBuilder jsImpls = new StringBuilder();
        StringBuilder jsVus = new StringBuilder();
        Map<String, String> series = new LinkedHashMap<>();
        series.put("rate", "successRate");
        series.put("p95", "p95");
        series.put("p99", "p99");
        series.put("rps", "rps");
        for (String impl : impls) {
            if (jsImpls.length() > 0) {
                jsImpls.append(',');
            }
            jsImpls.append('"').append(escapeJs(impl.toUpperCase())).append('"');
        }
        for (int i = 0; i < vus.size(); i++) {
            if (i > 0) {
                jsVus.append(',');
            }
            jsVus.append(vus.get(i)).append(" 并发");
        }

        StringBuilder dataMap = new StringBuilder();
        for (Map.Entry<String, String> e : series.entrySet()) {
            String key = e.getKey();
            dataMap.append("const ").append(key).append("Data = {};\n");
            for (String impl : impls) {
                dataMap.append(key).append("Data[\"").append(escapeJs(impl)).append("\"] = [");
                for (int i = 0; i < vus.size(); i++) {
                    if (i > 0) {
                        dataMap.append(',');
                    }
                    BenchmarkDocumentData.BenchmarkRow r = find(rows, impl, vus.get(i));
                    if (r == null) {
                        dataMap.append("0");
                    } else if ("rate".equals(key)) {
                        dataMap.append(String.format(java.util.Locale.ROOT, "%.2f", r.successRate()));
                    } else if ("rps".equals(key)) {
                        dataMap.append(String.format(java.util.Locale.ROOT, "%.0f", r.getRps()));
                    } else {
                        dataMap.append(String.format(java.util.Locale.ROOT, "%.1f",
                                "p95".equals(key) ? r.getP95() : r.getP99()));
                    }
                }
                dataMap.append("];\n");
            }
        }

        String title = data.getTitle() != null ? data.getTitle() : "HTTP 服务器压测报告";
        String env = data.getEnvironment() != null ? data.getEnvironment() : "";
        String tool = data.getTool() != null ? data.getTool() : "";
        String scenario = data.getScenario() != null ? data.getScenario() : "";

        // KPI 汇总
        int totalScenarios = rows.size();
        double avgRate = rows.stream().mapToDouble(BenchmarkDocumentData.BenchmarkRow::successRate).average().orElse(0);
        double peakRps = rows.stream().mapToDouble(BenchmarkDocumentData.BenchmarkRow::getRps).max().orElse(0);
        double avgP99 = rows.stream().mapToDouble(BenchmarkDocumentData.BenchmarkRow::getP99).average().orElse(0);
        String kpiScenarios = totalScenarios + " 场景";
        String kpiRate = String.format(java.util.Locale.ROOT, "%.2f%%", avgRate);
        String kpiRps = String.format(java.util.Locale.ROOT, "%.0f req/s", peakRps);
        String kpiP99 = String.format(java.util.Locale.ROOT, "%.1f ms", avgP99);

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <script src="%s"></script>
                <style>
                  :root {
                    --primary:#4f46e5; --primary-soft:#eef2ff; --bg:#f8fafc;
                    --card:#ffffff; --text:#0f172a; --muted:#64748b; --border:#e2e8f0;
                    --ok:#10b981; --warn:#f59e0b; --bad:#ef4444;
                  }
                  * { box-sizing: border-box; }
                  body { font-family:'Inter','Segoe UI','Microsoft YaHei',system-ui,sans-serif; margin:0; background:var(--bg); color:var(--text); -webkit-font-smoothing:antialiased; }
                  .hero { background:linear-gradient(135deg,#4f46e5 0%%,#7c3aed 100%%); color:#fff; padding:42px 24px 58px; text-align:center; }
                  .hero h1 { margin:0 0 10px; font-size:28px; font-weight:700; letter-spacing:.5px; }
                  .hero .meta { font-size:13px; opacity:.88; line-height:1.9; max-width:900px; margin:0 auto; }
                  .container { max-width:1120px; margin:-34px auto 48px; padding:0 24px; }
                  .kpis { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:16px; margin-bottom:26px; }
                  .kpi { background:var(--card); border:1px solid var(--border); border-radius:14px; padding:18px 22px; box-shadow:0 1px 2px rgba(15,23,42,.04); }
                  .kpi .label { font-size:12px; color:var(--muted); text-transform:uppercase; letter-spacing:.8px; font-weight:600; }
                  .kpi .value { font-size:26px; font-weight:700; margin-top:6px; color:var(--primary); }
                  .card { background:var(--card); border:1px solid var(--border); border-radius:14px; padding:22px; margin-bottom:26px; box-shadow:0 1px 2px rgba(15,23,42,.04); }
                  .card h2 { margin:0 0 16px; font-size:16px; font-weight:600; display:flex; align-items:center; gap:10px; }
                  .card h2::before { content:''; width:4px; height:18px; background:var(--primary); border-radius:2px; }
                  table { width:100%%; border-collapse:collapse; font-size:13.5px; }
                  th { background:var(--primary-soft); color:var(--text); font-weight:600; padding:10px 12px; text-align:center; border-bottom:2px solid var(--border); }
                  td { padding:9px 12px; border-bottom:1px solid var(--border); text-align:center; color:#334155; }
                  tr:hover td { background:#f1f5f9; }
                  .impl-header td { background:#f8fafc; font-weight:700; text-align:left; color:var(--primary); letter-spacing:.5px; }
                  .ok { color:var(--ok); font-weight:700; }
                  .warn { color:var(--warn); font-weight:700; }
                  .bad { color:var(--bad); font-weight:700; }
                  .charts { display:grid; grid-template-columns:1fr 1fr; gap:20px; }
                  .chart-box { background:var(--card); border:1px solid var(--border); border-radius:14px; padding:16px; }
                  .chart-box h3 { font-size:14px; margin:0 0 10px; text-align:center; color:var(--muted); font-weight:600; }
                  .chart-box div { width:100%%; height:320px; }
                  .full { grid-column:1/-1; }
                  footer { text-align:center; color:var(--muted); font-size:12px; margin-top:36px; }
                  @media (max-width:900px) { .charts { grid-template-columns:1fr; } }
                </style>
                </head>
                <body>
                <div class="hero">
                  <h1>📊 %s</h1>
                  <div class="meta">%s<br>%s<br>%s</div>
                </div>
                <div class="container">
                  <div class="kpis">
                    <div class="kpi"><div class="label">并发场景</div><div class="value">%s</div></div>
                    <div class="kpi"><div class="label">平均成功率</div><div class="value">%s</div></div>
                    <div class="kpi"><div class="label">峰值吞吐</div><div class="value">%s</div></div>
                    <div class="kpi"><div class="label">平均 p99</div><div class="value">%s</div></div>
                  </div>
                  <div class="card">
                    <h2>并发压测结果明细</h2>
                    <table>
                    <tr><th>并发数</th><th>总请求</th><th>失败</th><th>成功率</th><th>RPS</th><th>p95 (ms)</th><th>p99 (ms)</th></tr>
                    %s
                    </table>
                  </div>
                  <div class="charts">
                    <div class="chart-box full"><h3>各实现成功率对比（%%）</h3><div id="chartRate"></div></div>
                    <div class="chart-box"><h3>p95 延迟对比（ms）</h3><div id="chartP95"></div></div>
                    <div class="chart-box"><h3>p99 延迟对比（ms）</h3><div id="chartP99"></div></div>
                    <div class="chart-box full"><h3>吞吐量对比（RPS）</h3><div id="chartRps"></div></div>
                  </div>
                  <footer>Generated by utils-support-common-starter · Benchmark Report · ECharts</footer>
                </div>
                <script>
                const IMPLS = [%s];
                const VUS = [%s];
                %s
                const COLORS = { jdk:'#4f46e5', nio:'#10b981', netty:'#ef4444' };
                const colorOf = (name) => COLORS[name.toLowerCase()] || '#64748b';
                function mkSeries(map, key, type) {
                  return IMPLS.map(impl => ({
                    name: impl, type: type,
                    data: map[impl.toLowerCase()],
                    itemStyle: { color: colorOf(impl), borderRadius: type === 'bar' ? 4 : 0 },
                    lineStyle: { color: colorOf(impl), width: 2 },
                    smooth: true,
                  }));
                }
                function initChart(id, option) {
                  const el = document.getElementById(id);
                  if (!el) {
                      return;
                  }
                  const chart = echarts.init(el);
                  chart.setOption(option);
                  window.addEventListener('resize', () => chart.resize());
                }
                initChart('chartRate', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS, axisLine: { lineStyle: { color: '#cbd5e1' } } },
                  yAxis: { type: 'value', min: 0, max: 100, name: '成功率 %%', splitLine: { lineStyle: { color: '#f1f5f9' } } },
                  series: mkSeries(rateData, 'rate', 'bar'),
                });
                initChart('chartP95', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS, axisLine: { lineStyle: { color: '#cbd5e1' } } },
                  yAxis: { type: 'value', name: 'ms', splitLine: { lineStyle: { color: '#f1f5f9' } } },
                  series: mkSeries(p95Data, 'p95', 'line'),
                });
                initChart('chartP99', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS, axisLine: { lineStyle: { color: '#cbd5e1' } } },
                  yAxis: { type: 'value', name: 'ms', splitLine: { lineStyle: { color: '#f1f5f9' } } },
                  series: mkSeries(p99Data, 'p99', 'line'),
                });
                initChart('chartRps', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS, axisLine: { lineStyle: { color: '#cbd5e1' } } },
                  yAxis: { type: 'value', name: 'req/s', splitLine: { lineStyle: { color: '#f1f5f9' } } },
                  series: mkSeries(rpsData, 'rps', 'bar'),
                });
                </script>
                </body>
                </html>
                """.formatted(escape(title), ECHARTS_CDN, escape(title),
                escape(env), escape(tool), escape(scenario),
                kpiScenarios, kpiRate, kpiRps, kpiP99,
                table, jsImpls, jsVus, dataMap);
    }

    /**
     * 查找指定实现 + 并发等级的行。
     */
    private BenchmarkDocumentData.BenchmarkRow find(List<BenchmarkDocumentData.BenchmarkRow> rows,
                                                    String impl, int concurrency) {
        for (BenchmarkDocumentData.BenchmarkRow r : rows) {
            if (impl.equals(r.getImplementation()) && r.getConcurrency() == concurrency) {
                return r;
            }
        }
        return null;
    }

    /**
     * HTML 转义。
     * @param s 方法入参 s
     * @return 结果字符串
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * JS 字符串转义。
     * @param s 方法入参 s
     * @return 结果字符串
     */
    private static String escapeJs(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 写入文件。
     * @param outputFile output文件，不允许为 null
     * @param content 内容，不允许为 null
     */
    private void write(File outputFile, String content) {
        try {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new RuntimeException("压测报告 HTML 导出失败", e);
        }
    }
}
