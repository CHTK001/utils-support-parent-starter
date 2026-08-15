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

    /** ECharts CDN 地址 */
    private static final String ECHARTS_CDN =
            "https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js";

    @Override
    public String getType() {
        return "benchmark-html";
    }

    @Override
    public String[] getExtensions() {
        return new String[]{".html", ".htm"};
    }

    @Override
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
            if (jsImpls.length() > 0) jsImpls.append(',');
            jsImpls.append('"').append(escapeJs(impl.toUpperCase())).append('"');
        }
        for (int i = 0; i < vus.size(); i++) {
            if (i > 0) jsVus.append(',');
            jsVus.append(vus.get(i)).append(" 并发");
        }

        StringBuilder dataMap = new StringBuilder();
        for (Map.Entry<String, String> e : series.entrySet()) {
            String key = e.getKey();
            dataMap.append("const ").append(key).append("Data = {};\n");
            for (String impl : impls) {
                dataMap.append(key).append("Data[\"").append(escapeJs(impl)).append("\"] = [");
                for (int i = 0; i < vus.size(); i++) {
                    if (i > 0) dataMap.append(',');
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

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <script src="%s"></script>
                <style>
                  body { font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif; margin: 0; background: #f5f7fa; color: #1f2937; }
                  .container { max-width: 1100px; margin: 0 auto; padding: 24px; }
                  h1 { text-align: center; color: #111827; }
                  .meta { text-align: center; color: #6b7280; font-size: 14px; margin-bottom: 24px; line-height: 1.8; }
                  .card { background: #fff; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,.1); padding: 20px; margin-bottom: 24px; }
                  .card h2 { margin-top: 0; font-size: 18px; border-left: 4px solid #2563eb; padding-left: 10px; }
                  table { width: 100%%; border-collapse: collapse; font-size: 14px; }
                  th, td { padding: 8px 10px; border-bottom: 1px solid #e5e7eb; text-align: center; }
                  th { background: #f9fafb; font-weight: 600; }
                  .impl-header td { background: #eef2ff; font-weight: 700; text-align: left; }
                  .ok { color: #16a34a; font-weight: 700; }
                  .warn { color: #d97706; font-weight: 700; }
                  .bad { color: #dc2626; font-weight: 700; }
                  .charts { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
                  .chart-box { background: #fff; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,.1); padding: 16px; }
                  .chart-box h3 { font-size: 15px; margin: 0 0 8px; text-align: center; }
                  .chart-box div { width: 100%%; height: 320px; }
                  .full { grid-column: 1 / -1; }
                  @media (max-width: 900px) { .charts { grid-template-columns: 1fr; } }
                </style>
                </head>
                <body>
                <div class="container">
                <h1>📊 %s</h1>
                <div class="meta">
                  %s<br>%s<br>%s
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
                </div>
                <script>
                const IMPLS = [%s];
                const VUS = [%s];
                %s
                const COLORS = { jdk: '#2563eb', nio: '#16a34a', netty: '#dc2626' };
                const colorOf = (name) => COLORS[name.toLowerCase()] || '#64748b';
                function mkSeries(map, key, type) {
                  return IMPLS.map(impl => ({
                    name: impl, type: type,
                    data: map[impl.toLowerCase()],
                    itemStyle: { color: colorOf(impl) },
                    lineStyle: { color: colorOf(impl) },
                    smooth: true,
                  }));
                }
                function initChart(id, option) {
                  const el = document.getElementById(id);
                  if (!el) return;
                  const chart = echarts.init(el);
                  chart.setOption(option);
                  window.addEventListener('resize', () => chart.resize());
                }
                initChart('chartRate', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS },
                  yAxis: { type: 'value', min: 0, max: 100, name: '成功率 %%' },
                  series: mkSeries(rateData, 'rate', 'bar'),
                });
                initChart('chartP95', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS },
                  yAxis: { type: 'value', name: 'ms' },
                  series: mkSeries(p95Data, 'p95', 'line'),
                });
                initChart('chartP99', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS },
                  yAxis: { type: 'value', name: 'ms' },
                  series: mkSeries(p99Data, 'p99', 'line'),
                });
                initChart('chartRps', {
                  tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
                  xAxis: { type: 'category', data: VUS },
                  yAxis: { type: 'value', name: 'req/s' },
                  series: mkSeries(rpsData, 'rps', 'bar'),
                });
                </script>
                </body>
                </html>
                """.formatted(escape(title), ECHARTS_CDN, escape(title),
                escape(env), escape(tool), escape(scenario),
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
