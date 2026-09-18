package com.chua.hprof.support.serializer;

import com.chua.hprof.support.analyzer.HprofAnalyzer;
import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofAnalysis;
import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofFinding;
import com.chua.hprof.support.model.HprofClassDetail;
import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将解析出的 hprof 结果序列化为自包含的 HTML 报告。
 *
 * <p>HTML 为单个文件：CSS 内联、JS 内联，
 * {@code ECharts} 由 jsDelivr CDN 加载（与
 * {@code BenchmarkHtmlProvider} 使用同一个 CDN 常量）。报告渲染：</p>
 *
 * <ul>
 *   <li>KPI 卡片行（retained 总量、对象数量、Top-N 占比、
 *   集合类占比、类加载器数量）</li>
 *   <li>按 retained 大小排序的 Top-20 类柱状图</li>
 *   <li>按实例数量排序的 Top-20 类柱状图</li>
 *   <li>GC-root 分布饼图</li>
 *   <li>分析发现（"内存为何偏高"）并带严重级别徽标</li>
 *   <li>通俗语言的结论</li>
 * </ul>
 *
 * <p>数据以 JSON 对象形式内嵌，从而让图表代码保持
 * 通用；报告离线打开同样安全（仅拉取 ECharts 脚本，
 * 即使 CDN 不可达，页面仍会渲染表格与发现项）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofToHtmlSerializer {

    /**
    * ECharts CDN, shared with {@code BenchmarkHtmlProvider}.
    */
    private static final String ECHARTS_CDN =
            "https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 构造方法，创建 Hprof转为HtmlSerializer 实例。
     */
    private HprofToHtmlSerializer() {
    }

    /**
    * 将解析结果序列化为不含 AI 摘要的 HTML 文档。
    *
    * @param result   parsed hprof result
    * @param fileName 报告页头显示的源文件名
    * @return HTML document
    */
    public static String serialize(HprofParser.Result result, String fileName) {
        return serialize(result, fileName, null);
    }

    /**
    * 将解析结果序列化为带 AI 摘要区块的 HTML 文档。
    *
    * @param result   parsed hprof result
    * @param fileName 报告页头显示的源文件名
    * @param aiSummary 可选的 AI 摘要文本，为 null 或空白时省略该区块
    * @return HTML document
    */
    public static String serialize(HprofParser.Result result, String fileName, String aiSummary) {
        Objects.requireNonNull(result, "result");
        HprofAnalysis analysis = HprofAnalyzer.analyze(result,
                fileName == null ? null : new java.io.File(fileName));
        com.chua.hprof.support.action.HprofActionPlanner.ActionPlan plan =
                com.chua.hprof.support.action.HprofActionPlanner.plan(result, analysis);
        StringBuilder sb = new StringBuilder(4096);
        appendHtmlHead(sb, fileName, result);
        appendKpiCards(sb, analysis, result);
        appendProblemAndPlan(sb, plan);
        appendRootCause(sb, analysis);
        appendCrashContext(sb, analysis);
        appendCharts(sb);
        appendFindings(sb, analysis, result);
        appendRefChains(sb, result);
        appendNonJdk(sb, analysis, result);
        appendConclusions(sb, analysis);
        appendChecklist(sb, plan);
        appendAiSummary(sb, aiSummary);
        appendDetails(sb, result);
        appendTables(sb, analysis, result);
        appendScript(sb, analysis, fileName);
        appendHtmlTail(sb);
        return sb.toString();
    }

    /**
    * 追加顶层"问题 + 处理步骤"行动卡片（非开发人员可直接读）。
    *
    * @param sb   输出缓冲
    * @param plan 处置计划
    */
    private static void appendProblemAndPlan(StringBuilder sb,
                                             com.chua.hprof.support.action.HprofActionPlanner.ActionPlan plan) {
        sb.append("<div class=\"action-card card\">\n")
                .append("<div class=\"rc-label\">📌 问题是什么 · 怎么处理（按优先级）</div>\n")
                .append("<div class=\"problem-summary\">").append(escape(plan.problemSummary())).append("</div>\n")
                .append("<ol class=\"action-list\">\n");
        for (com.chua.hprof.support.action.HprofActionPlanner.ActionItem item : plan.items()) {
            sb.append("<li class=\"action-p").append(item.priority()).append("\">")
                    .append("<span class=\"action-pri\">P").append(item.priority()).append("</span> ")
                    .append("<span class=\"action-title\">").append(escape(item.title())).append("</span>")
                    .append("<div class=\"action-how\">").append(escape(item.how())).append("</div>")
                    .append("<div class=\"action-effect\">预期：").append(escape(item.expectedEffect())).append("</div>")
                    .append("</li>\n");
        }
        sb.append("</ol>\n</div>\n");
    }

    /**
    * 追加引用链可视化区块（top 持有类的 3 层：持有实例 → 字段 → 子引用）。
    *
    * @param sb     输出缓冲
    * @param result 解析结果
    */
    private static void appendRefChains(StringBuilder sb, HprofParser.Result result) {
        if (result.refChains() == null || result.refChains().isEmpty()) {
            return;
        }
        sb.append("<h2>引用链可视化（谁持有谁）</h2>\n<div class=\"card\">\n");
        for (com.chua.hprof.support.parser.HprofRefChainWalker.RefChain chain : result.refChains()) {
            sb.append("<div class=\"chain\">\n")
                    .append("<div class=\"chain-head\">持有者 ")
                    .append(escape(chain.holderClass()))
                    .append(" <span class=\"id\">#").append(chain.holderId()).append("</span>")
                    .append("（保留 ").append(escape(HprofObject.formatSize(chain.holderRetained())))
                    .append("）</div>\n")
                    .append("<div class=\"chain-mid\">└ 自身字段：");
            if (chain.fields().isEmpty()) {
                sb.append("（无引用字段）");
            } else {
                boolean first = true;
                for (com.chua.hprof.support.model.HprofClassDetail.FieldValueDetail fv : chain.fields()) {
                    if (!first) {
                        sb.append("，");
                    }
                    first = false;
                    sb.append(escape(fv.getName())).append("=").append(escape(fv.getValueText()));
                }
            }
            sb.append("</div>\n<div class=\"chain-tail\">└ 持有子引用（按保留大小）：");
            if (chain.children().isEmpty()) {
                sb.append("（叶子对象，无可展开子引用）");
            } else {
                boolean first = true;
                for (com.chua.hprof.support.parser.HprofRefChainWalker.ChildRef child : chain.children()) {
                    if (!first) {
                        sb.append("<br>　");
                    }
                    first = false;
                    sb.append(escape(child.className()))
                            .append(" → ").append(escape(HprofObject.formatSize(child.retained())))
                            .append(" <span class=\"id\">#").append(child.instanceId()).append("</span>");
                }
            }
            sb.append("</div>\n</div>\n");
        }
        sb.append("</div>\n");
    }

    /**
    * 追加可勾选处置清单（localStorage 记忆勾选状态）。
    *
    * @param sb   输出缓冲
    * @param plan 处置计划
    */
    private static void appendChecklist(StringBuilder sb,
                                        com.chua.hprof.support.action.HprofActionPlanner.ActionPlan plan) {
        sb.append("<h2>处置清单（处理一项勾一项）</h2>\n<div class=\"card checklist\">\n");
        for (com.chua.hprof.support.action.HprofActionPlanner.ActionItem item : plan.items()) {
            sb.append("<label class=\"check-row\">\n")
                    .append("<input type=\"checkbox\" data-item=\"")
                    .append(escape(item.id())).append("\"")
                    .append(" onchange=\"toggleCheck(this)\"> ")
                    .append("<span class=\"check-label\" data-for=\"")
                    .append(escape(item.id())).append("\">")
                    .append("<b>P").append(item.priority()).append("</b> ")
                    .append(escape(item.title())).append("</span>")
                    .append("<div class=\"check-verify\">验证：").append(escape(item.verify())).append("</div>")
                    .append("</label>\n");
        }
        sb.append("</div>\n");
    }

    /**
    * 追加崩溃语境区块（OOM 判定 / 堆水位 / hs_err 证据）。
    *
    * @param sb       输出缓冲
    * @param analysis 分析结果
    */
    private static void appendCrashContext(StringBuilder sb, HprofAnalysis analysis) {
        if (analysis.crashSignals == null || analysis.crashSignals.isEmpty()) {
            return;
        }
        sb.append("<div class=\"crash").append(analysis.oomLikely ? " oom" : "")
                .append(" card\">\n")
                .append("<div class=\"rc-label\">")
                .append(analysis.oomLikely
                        ? "⚠ 崩溃语境判定（强烈疑似 OOM）"
                        : "崩溃语境判定（证据不足）")
                .append("</div>\n");
        for (com.chua.hprof.support.crash.CrashContext.CrashSignal signal : analysis.crashSignals) {
            sb.append("<div class=\"crash-item\"><span class=\"crash-kind\">")
                    .append(escape(signal.kind())).append("</span> ")
                    .append(escape(signal.detail())).append("</div>\n")
                    .append("<div class=\"crash-ev\">证据：")
                    .append(escape(signal.evidence())).append("</div>\n");
        }
        sb.append("</div>\n");
    }

    /**
    * Append the root-cause block that answers "why so many objects".
    *
    * @param sb       output buffer
    * @param analysis analysis
    */
    private static void appendRootCause(StringBuilder sb, HprofAnalysis analysis) {
        if (analysis.rootCause == null || analysis.rootCause.isBlank()) {
            return;
        }
        sb.append("<div class=\"root-cause card\">\n")
                .append("<div class=\"rc-label\">根因判定（为什么产生了这么多对象）</div>\n")
                .append("<div class=\"rc-text\">").append(escape(analysis.rootCause)).append("</div>\n")
                .append("</div>\n");
    }

    /**
    * 追加带内联 CSS 的 HTML 头部。
    *
    * @param sb       output buffer
    * @param fileName source file name
    * @param result   parsed result
    */
    private static void appendHtmlHead(StringBuilder sb, String fileName, HprofParser.Result result) {
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("<title>HPROF 堆分析 - ").append(escape(fileName == null ? "stream" : fileName)).append("</title>\n")
                .append("<style>\n")
                .append("  * { box-sizing: border-box; margin: 0; padding: 0; }\n")
                .append("  body { font-family: -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;")
                .append("         background: #f5f7fa; color: #1f2937; line-height: 1.6; }\n")
                .append("  .wrap { max-width: 1200px; margin: 0 auto; padding: 24px; }\n")
                .append("  h1 { font-size: 24px; margin-bottom: 4px; }\n")
                .append("  .meta { color: #6b7280; font-size: 13px; margin-bottom: 20px; }\n")
                .append("  h2 { font-size: 18px; margin: 24px 0 12px; border-left: 4px solid #3b82f6;")
                .append("       padding-left: 10px; }\n")
                .append("  .kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));")
                .append("            gap: 12px; margin-bottom: 8px; }\n")
                .append("  .kpi { background: #fff; border-radius: 10px; padding: 14px;")
                .append("        box-shadow: 0 1px 3px rgba(0,0,0,.08); }\n")
                .append("  .kpi .v { font-size: 22px; font-weight: 700; color: #111827; }\n")
                .append("  .kpi .l { font-size: 12px; color: #6b7280; margin-top: 2px; }\n")
                .append("  .chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }\n")
                .append("  .chart { background: #fff; border-radius: 10px; padding: 12px;")
                .append("          box-shadow: 0 1px 3px rgba(0,0,0,.08); height: 360px; }\n")
                .append("  .chart-tall { height: 420px; }\n")
                .append("  .card { background: #fff; border-radius: 10px; padding: 16px;")
                .append("         box-shadow: 0 1px 3px rgba(0,0,0,.08); margin-bottom: 12px; }\n")
                .append("  .finding { border-left: 4px solid #e5e7eb; padding: 10px 14px; margin-bottom: 10px;")
                .append("            background: #fafafa; border-radius: 6px; }\n")
                .append("  .finding.high { border-left-color: #ef4444; }\n")
                .append("  .finding.medium { border-left-color: #f59e0b; }\n")
                .append("  .finding.info { border-left-color: #3b82f6; }\n")
                .append("  .badge { display: inline-block; font-size: 11px; font-weight: 700; padding: 2px 8px;")
                .append("           border-radius: 10px; margin-right: 8px; vertical-align: middle; }\n")
                .append("  .badge.high { background: #fee2e2; color: #b91c1c; }\n")
                .append("  .badge.medium { background: #fef3c7; color: #92400e; }\n")
                .append("  .badge.info { background: #dbeafe; color: #1e40af; }\n")
                .append("  .finding .title { font-weight: 600; margin-bottom: 4px; }\n")
                .append("  .finding .detail { font-size: 13px; color: #4b5563; }\n")
                .append("  .root-cause { background: linear-gradient(135deg, #fef2f2 0%, #fee2e2 100%);")
                .append("                border: 1px solid #fecaca; margin-bottom: 12px; }\n")
                .append("  .root-cause .rc-label { font-weight: 700; font-size: 13px; color: #991b1b;")
                .append("                           margin-bottom: 6px; }\n")
                .append("  .root-cause .rc-text { font-size: 14px; color: #7f1d1d; line-height: 1.8; }\n")
                .append("  .crash { background: #fff; border: 2px solid #fca5a5; margin-bottom: 12px; }\n")
                .append("  .crash.oom { border-color: #ef4444; background: linear-gradient(135deg,#fef2f2 0%,#fee2e2 100%); }\n")
                .append("  .crash .crash-item { font-size: 14px; color: #1f2937; margin: 6px 0 2px; }\n")
                .append("  .crash .crash-kind { display: inline-block; font-size: 11px; font-weight: 700;")
                .append("                          background: #fee2e2; color: #b91c1c; padding: 1px 8px;")
                .append("                          border-radius: 10px; margin-right: 6px; }\n")
                .append("  .crash .crash-ev { font-size: 12px; color: #6b7280; padding-left: 4px; }\n")
                .append("  .action-card { background: linear-gradient(135deg,#eff6ff 0%,#dbeafe 100%);")
                .append("                 border: 2px solid #93c5fd; margin-bottom: 12px; }\n")
                .append("  .action-card .problem-summary { font-size: 14px; color: #1e3a8a; margin: 6px 0 10px;")
                .append("                                   font-weight: 600; }\n")
                .append("  .action-list { margin: 0 0 0 4px; padding-left: 18px; }\n")
                .append("  .action-list li { margin: 8px 0; }\n")
                .append("  .action-pri { display: inline-block; font-size: 11px; font-weight: 700;")
                .append("               background: #1e40af; color: #fff; padding: 1px 7px; border-radius: 10px;")
                .append("               margin-right: 6px; }\n")
                .append("  li.action-p2 .action-pri { background: #f59e0b; }\n")
                .append("  li.action-p3 .action-pri { background: #6b7280; }\n")
                .append("  .action-title { font-weight: 600; color: #111827; }\n")
                .append("  .action-how { font-size: 13px; color: #374151; margin-top: 2px; }\n")
                .append("  .action-effect { font-size: 12px; color: #059669; margin-top: 2px; }\n")
                .append("  .chain { border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 14px;")
                .append("          margin-bottom: 10px; background: #fafafa; }\n")
                .append("  .chain-head { font-weight: 600; font-size: 13px; color: #1d4ed8; }\n")
                .append("  .chain-mid, .chain-tail { font-size: 12px; color: #374151; margin-top: 4px;")
                .append("         font-family: ui-monospace, monospace; }\n")
                .append("  .chain .id { color: #6b7280; }\n")
                .append("  .checklist label { display: block; padding: 8px 0; border-bottom: 1px solid #f3f4f6;")
                .append("                      font-size: 14px; }\n")
                .append("  .checklist input { margin-right: 8px; transform: scale(1.1); }\n")
                .append("  .checklist .check-label { color: #111827; }\n")
                .append("  .checklist .check-verify { font-size: 12px; color: #059669; margin: 2px 0 0 24px; }\n")
                .append("  .checklist input:checked + .check-label { text-decoration: line-through; color: #9ca3af; }\n")
                .append("  .concl { background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 8px;")
                .append("           padding: 14px; font-size: 14px; }\n")
                .append("  .concl li { margin-bottom: 6px; }\n")
                .append("  .ai-summary { background: linear-gradient(135deg, #fef9c3 0%, #fde68a 100%);")
                .append("               border: 1px solid #fcd34d; }\n")
                .append("  .ai-summary .ai-text { white-space: pre-wrap; font-size: 14px; color: #78350f; }\n")
                .append("  details { background: #fff; border-radius: 10px; margin-bottom: 10px;")
                .append("           box-shadow: 0 1px 3px rgba(0,0,0,.08); }")
                .append("  details summary { cursor: pointer; padding: 12px 16px; font-weight: 600;")
                .append("                    color: #1f2937; list-style: none; }")
                .append("  details summary::-webkit-details-marker { display: none; }")
                .append("  details summary::before { content: '▶'; display: inline-block; margin-right: 8px;")
                .append("                           font-size: 11px; color: #3b82f6; transition: transform .15s; }")
                .append("  details[open] summary::before { transform: rotate(90deg); }")
                .append("  details .body { padding: 8px 16px 14px; border-top: 1px solid #e5e7eb; }")
                .append("  .inst { margin: 10px 0; padding: 10px; background: #f9fafb; border-radius: 6px; }")
                .append("  .inst .head { font-weight: 600; font-size: 13px; margin-bottom: 6px; }")
                .append("  .inst .head .id { color: #6b7280; font-family: ui-monospace, monospace; font-weight: 400; }")
                .append("  .field { display: flex; gap: 8px; font-size: 12px; padding: 2px 0;")
                .append("          font-family: ui-monospace, monospace; }")
                .append("  .field .fn { color: #1d4ed8; min-width: 180px; }")
                .append("  .field .ft { color: #6b7280; min-width: 120px; }")
                .append("  .field .fv { color: #059669; word-break: break-all; }")
                .append("  .field.static .fn::before { content: 'static '; color: #b91c1c; font-weight: 700; }\n")
                .append("  .detail-row td { padding: 0 8px 10px; background: #fafafa; }\n")
                .append("  table { width: 100%; border-collapse: collapse; font-size: 13px; }\n")
                .append("  th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #e5e7eb; }\n")
                .append("  th { background: #f9fafb; font-weight: 600; color: #374151; }\n")
                .append("  td.num, th.num { text-align: right; }\n")
                .append("  @media (max-width: 768px) { .chart-grid { grid-template-columns: 1fr; } }\n")
                .append("</style>\n</head>\n<body>\n<div class=\"wrap\">\n")
                .append("<h1>HPROF 堆内存分析报告</h1>\n")
                .append("<div class=\"meta\">源文件: ")
                .append(escape(fileName == null ? "stream" : fileName))
                .append(" · 解析时间 ").append(java.time.LocalDateTime.now())
                .append("</div>\n");
    }

    /**
    * Append the KPI card row.
    *
    * @param sb       output buffer
    * @param analysis analysis
    * @param result   parsed result
    */
    private static void appendKpiCards(StringBuilder sb, HprofAnalysis analysis, HprofParser.Result result) {
        sb.append("<div class=\"kpi-row\">\n")
                .append(kpi(HprofObject.formatSize(result.totalRetainedBytes()), "总保留内存"))
                .append(kpi(String.format("%,d", result.totalObjectCount()), "存活对象数"))
                .append(kpi(String.format("%.1f%%", analysis.topNRetainedRatio * 100.0),
                        "Top-10 类占比"))
                .append(kpi(HprofObject.formatSize(analysis.collectionRetainedBytes), "集合/数组保留"))
                .append(kpi(String.format("%,d", analysis.classLoaderClassCount), "类加载器类数"))
                .append("</div>\n");
    }

    /**
    * One KPI card.
    *
    * @param value value text
    * @param label label text
    * @return card html
    */
    private static String kpi(String value, String label) {
        return "<div class=\"kpi\"><div class=\"v\">" + escape(value)
                + "</div><div class=\"l\">" + escape(label) + "</div></div>\n";
    }

    /**
    * Append the chart containers (filled by JS).
    *
    * @param sb output buffer
    */
    private static void appendCharts(StringBuilder sb) {
        sb.append("<h2>内存占用排行</h2>\n")
                .append("<div class=\"chart-grid\">\n")
                .append("<div class=\"chart chart-tall\" id=\"chart-retained\"></div>\n")
                .append("<div class=\"chart chart-tall\" id=\"chart-instances\"></div>\n")
                .append("</div>\n")
                .append("<h2>GC 根分布</h2>\n")
                .append("<div class=\"chart\" id=\"chart-roots\"></div>\n");
    }

    /**
    * Append the findings section.
    *
    * @param sb       output buffer
    * @param analysis analysis
    * @param result   parsed result (for details linkage)
    */
    private static void appendFindings(StringBuilder sb, HprofAnalysis analysis,
                                       HprofParser.Result result) {
        sb.append("<h2>算法分析结论（为什么内存高）</h2>\n");
        for (HprofFinding f : analysis.findingDetails) {
            sb.append("<div class=\"card\">")
                    .append("<div class=\"finding ").append(escape(f.severity())).append("\">")
                    .append("<span class=\"badge ").append(escape(f.severity())).append("\">")
                    .append(escape(f.severity().toUpperCase(java.util.Locale.ROOT))).append("</span>")
                    .append("<span class=\"title\">").append(escape(f.title())).append("</span>")
                    .append("<div class=\"detail\">").append(escape(f.detail())).append("</div>")
                    .append("</div>\n</div>\n");
        }
    }

    /**
    * Append the non-JDK package ranking section.
    *
    * @param sb       output buffer
    * @param analysis analysis
    * @param result   parsed result (details linkage for future per-package expand)
    */
    private static void appendNonJdk(StringBuilder sb, HprofAnalysis analysis,
                                     HprofParser.Result result) {
        if (analysis.nonJdkPackageGroups == null || analysis.nonJdkPackageGroups.isEmpty()) {
            return;
        }
        long total = Math.max(analysis.totalRetainedBytes, 1L);
        sb.append("<h2>非 JDK 类内存排行（代码泄漏定位）</h2>\n")
                .append("<div class=\"card\">\n")
                .append("<div class=\"meta\">非 JDK 类共保留 ")
                .append(HprofObject.formatSize(analysis.nonJdkRetainedBytes))
                .append("（占堆 ")
                .append(String.format(java.util.Locale.ROOT, "%.1f%%",
                        analysis.nonJdkRetainedBytes * 100.0 / total))
                .append("）；JDK 类保留 ")
                .append(HprofObject.formatSize(analysis.jdkRetainedBytes)).append("</div>\n")
                .append("<table><thead><tr><th>包前缀（非 JDK）</th><th class=\"num\">实例数</th>")
                .append("<th class=\"num\">保留内存</th><th class=\"num\">占非 JDK 比</th>")
                .append("</tr></thead><tbody>\n");
        for (HprofAnalyzer.PackageGroup group : analysis.nonJdkPackageGroups) {
            double pct = analysis.nonJdkRetainedBytes > 0
                    ? group.retained() * 100.0 / analysis.nonJdkRetainedBytes : 0.0;
            sb.append("<tr><td>").append(escape(group.name()))
                    .append("</td><td class=\"num\">").append(group.instances())
                    .append("</td><td class=\"num\">")
                    .append(HprofObject.formatSize(group.retained()))
                    .append("</td><td class=\"num\">")
                    .append(String.format(java.util.Locale.ROOT, "%.1f%%", pct))
                    .append("</td></tr>\n");
        }
        sb.append("</tbody></table>\n</div>\n");
    }

    /**
    * Append the plain-language conclusions.
    *
    * @param sb       output buffer
    * @param analysis analysis
    */
    private static void appendConclusions(StringBuilder sb, HprofAnalysis analysis) {
        sb.append("<h2>结论</h2>\n<div class=\"concl\"><ol>\n");
        for (String c : analysis.conclusions) {
            sb.append("<li>").append(escape(c)).append("</li>\n");
        }
        sb.append("</ol></div>\n");
    }

    /**
    * 追加 AI 摘要区块；摘要为空白时整块省略。
    *
    * @param sb        output buffer
    * @param aiSummary AI summary text, may be null/blank
    */
    private static void appendAiSummary(StringBuilder sb, String aiSummary) {
        if (aiSummary == null || aiSummary.isBlank()) {
            return;
        }
        sb.append("<h2>AI 总结</h2>\n")
                .append("<div class=\"card ai-summary\">\n")
                .append("<div class=\"ai-text\">").append(escape(aiSummary)).append("</div>\n")
                .append("</div>\n");
    }

    /**
    * 追加"点开看详情"区块：对每个已采集的热点类，用
    * 可折叠的 {@code <details>} 展示其 retained 最大的实例
    * 及字段取值（哪个字段持有大集合 / 数组 /
    * 字符串），并列出静态字段持有者。用于回答"谁存了什么"。
    *
    * @param sb     output buffer
    * @param result parsed result with classDetails
    */
    private static void appendDetails(StringBuilder sb, HprofParser.Result result) {
        Map<String, HprofClassDetail> details = result.classDetails();
        if (details == null || details.isEmpty()) {
            return;
        }
        sb.append("<h2>内存持有明细（点开看是谁存了什么）</h2>\n");
        for (HprofClassDetail detail : details.values()) {
            if (detail.getInstances().isEmpty() && detail.getStaticFields().isEmpty()) {
                continue;
            }
            sb.append("<details>\n")
                    .append("<summary>").append(escape(detail.getClassName()))
                    .append("</summary>\n<div class=\"body\">\n");
            for (HprofClassDetail.InstanceDetail inst : detail.getInstances()) {
                sb.append("<div class=\"inst\">\n")
                        .append("<div class=\"head\">实例 ")
                        .append(escape(HprofObject.formatSize(inst.getRetainedSize())))
                        .append(" <span class=\"id\">#").append(inst.getInstanceId()).append("</span></div>\n");
                for (HprofClassDetail.FieldValueDetail fv : inst.getFieldValues()) {
                    appendField(sb, fv, false);
                }
                sb.append("</div>\n");
            }
            if (!detail.getStaticFields().isEmpty()) {
                sb.append("<div class=\"inst\"><div class=\"head\">静态字段持有者</div>\n");
                for (HprofClassDetail.FieldValueDetail fv : detail.getStaticFields()) {
                    appendField(sb, fv, true);
                }
                sb.append("</div>\n");
            }
            sb.append("</div>\n</details>\n");
        }
    }

    /**
    * One field row inside a details block.
    *
    * @param sb      output buffer
    * @param fv      field value detail
    * @param isStatic whether in the static-field section
    */
    private static void appendField(StringBuilder sb,
                                    HprofClassDetail.FieldValueDetail fv,
                                    boolean isStatic) {
        sb.append("<div class=\"field").append(isStatic ? " static" : "")
                .append("\"><span class=\"fn\">").append(escape(fv.getName()))
                .append("</span><span class=\"ft\">").append(escape(fv.getType()))
                .append("</span><span class=\"fv\">").append(escape(fv.getValueText()))
                .append("</span></div>\n");
    }

    /**
    * Append the detailed ranking tables.
    *
    * @param sb       output buffer
    * @param analysis analysis
    * @param result   parsed result (class details)
    */
    private static void appendTables(StringBuilder sb, HprofAnalysis analysis,
                                     HprofParser.Result result) {
        sb.append("<h2>类排行明细</h2>\n<div class=\"card\">\n")
                .append("<table><thead><tr><th>类</th><th class=\"num\">实例数</th>")
                .append("<th class=\"num\">Shallow</th><th class=\"num\">Retained</th>")
                .append("</tr></thead><tbody>\n");
        for (HprofHistogramRow row : analysis.topByRetained) {
            appendRow(sb, row, result);
        }
        sb.append("</tbody></table>\n</div>\n");
    }

    /**
    * 表格中的一行。若该类已采集到实例明细，则该行
    * 通过 {@code <details>} 展开以展示字段取值。
    *
    * @param sb    output buffer
    * @param row   histogram row
    * @param result parsed result (for classDetails)
    */
    private static void appendRow(StringBuilder sb, HprofHistogramRow row,
                                  HprofParser.Result result) {
        sb.append("<tr><td>").append(escape(row.getClassName()))
                .append("</td><td class=\"num\">").append(row.getInstanceCount())
                .append("</td><td class=\"num\">").append(HprofObject.formatSize(row.getShallowSize()))
                .append("</td><td class=\"num\">").append(HprofObject.formatSize(row.getRetainedSize()))
                .append("</td></tr>\n");
        if (result.classDetails() != null && result.classDetails().containsKey(row.getClassName())) {
            HprofClassDetail detail = result.classDetails().get(row.getClassName());
            sb.append("<tr class=\"detail-row\"><td colspan=\"4\">\n");
            for (HprofClassDetail.InstanceDetail inst : detail.getInstances()) {
                sb.append("<div class=\"inst\"><div class=\"head\">实例 ")
                        .append(HprofObject.formatSize(inst.getRetainedSize()))
                        .append(" <span class=\"id\">#").append(inst.getInstanceId()).append("</span></div>\n");
                for (HprofClassDetail.FieldValueDetail fv : inst.getFieldValues()) {
                    appendField(sb, fv, false);
                }
                sb.append("</div>\n");
            }
            sb.append("</td></tr>\n");
        }
    }

    /**
    * 追加驱动 ECharts 的内联脚本。
    *
    * @param sb       output buffer
    * @param analysis analysis
    * @param fileName source file name
    */
    private static void appendScript(StringBuilder sb, HprofAnalysis analysis, String fileName) {
        sb.append("<script src=\"").append(ECHARTS_CDN).append("\"></script>\n")
                .append("<script>\n")
                .append("  const DATA = ").append(analysisJson(analysis, fileName))
                .append(";\n")
                .append("  function fmt(bytes){ if(bytes<=0) return '0B';")
                .append("    const kb=bytes/1024; if(kb<1024) return kb.toFixed(1).replace('.0','')+'KB';")
                .append("    const mb=kb/1024; if(mb<1024) return mb.toFixed(1).replace('.0','')+'MB';")
                .append("    return (mb/1024).toFixed(1).replace('.0','')+'GB'; }\n")
                .append("  function initAll(){ if(typeof echarts==='undefined') return;")
                .append("    const retained=echarts.init(document.getElementById('chart-retained'));")
                .append("    retained.setOption({")
                .append("      title:{text:'按 Retained 内存排行（Top 20）',left:'center',textStyle:{fontSize:14}},")
                .append("      tooltip:{trigger:'axis',axisPointer:{type:'shadow'},")
                .append("        valueFormatter:function(v){return fmt(v);}},")
                .append("      grid:{left:'30%',right:'8%',top:36,bottom:20},")
                .append("      xAxis:{type:'value',axisLabel:{formatter:function(v){return fmt(v);}}},")
                .append("      yAxis:{type:'category',inverse:true,")
                .append("        data:DATA.retained.map(function(r){return r.name;})},")
                .append("      series:[{type:'bar',data:DATA.retained.map(function(r){return r.retained;}),")
                .append("        label:{show:true,position:'right',formatter:function(p){return fmt(p.value);}}}]")
                .append("    });")
                .append("    const instances=echarts.init(document.getElementById('chart-instances'));")
                .append("    instances.setOption({")
                .append("      title:{text:'按实例数排行（Top 20）',left:'center',textStyle:{fontSize:14}},")
                .append("      tooltip:{trigger:'axis',axisPointer:{type:'shadow'}},")
                .append("      grid:{left:'30%',right:'8%',top:36,bottom:20},")
                .append("      xAxis:{type:'value'},")
                .append("      yAxis:{type:'category',inverse:true,")
                .append("        data:DATA.instances.map(function(r){return r.name;})},")
                .append("      series:[{type:'bar',data:DATA.instances.map(function(r){return r.count;}),")
                .append("        label:{show:true,position:'right'}}]")
                .append("    });")
                .append("    const roots=echarts.init(document.getElementById('chart-roots'));")
                .append("    roots.setOption({")
                .append("      title:{text:'GC 根类型分布',left:'center',textStyle:{fontSize:14}},")
                .append("      tooltip:{trigger:'item',formatter:'{b}: {c} ({d}%)'},")
                .append("      series:[{type:'pie',radius:['40%','65%'],center:['50%','55%'],")
                .append("        data:DATA.roots.map(function(r){return {name:r.name,value:r.value};}),")
                .append("        label:{formatter:'{b}\\n{c}'}}]")
                .append("    });")
                .append("    window.addEventListener('resize',function(){")
                .append("      [retained,instances,roots].forEach(function(c){c.resize();});});")
                .append("  }\n")
                .append("  if(document.readyState==='loading'){")
                .append("    document.addEventListener('DOMContentLoaded',initAll);")
                .append("  } else { initAll(); }\n")
                .append("  function toggleCheck(el){ var k='hprof-check-'+el.dataset.item;")
                .append("    try{ if(el.checked){localStorage.setItem(k,'1');}")
                .append("      else{localStorage.removeItem(k);} }catch(e){} }")
                .append("  function restoreChecks(){ try{ document.querySelectorAll('.checklist input')")
                .append(".forEach(function(el){ if(localStorage.getItem('hprof-check-'+el.dataset.item))")
                .append("{ el.checked=true; var lbl=el.parentElement.querySelector('.check-label');")
                .append("if(lbl){lbl.style.textDecoration='line-through';lbl.style.color='#9ca3af';} } });")
                .append("}catch(e){} }\n")
                .append("  if(document.readyState==='loading'){")
                .append("    document.addEventListener('DOMContentLoaded',restoreChecks);")
                .append("  } else { restoreChecks(); }\n")
                .append("</script>\n");
    }

    /**
    * 构造图表使用的内嵌 JSON 数据对象。
    *
    * @param analysis analysis
    * @param fileName source file name
    * @return JSON string
    */
    private static String analysisJson(HprofAnalysis analysis, String fileName) {
        try {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("file", fileName);
            data.put("totalRetained", analysis.totalRetainedBytes);
            data.put("totalObjects", analysis.totalObjectCount);

            List<Map<String, Object>> retained = new ArrayList<>();
            for (HprofHistogramRow row : analysis.topByRetained) {
                retained.add(rowJson(row, "retained"));
            }
            data.put("retained", retained);

            List<Map<String, Object>> instances = new ArrayList<>();
            for (HprofHistogramRow row : analysis.topByInstances) {
                instances.add(rowJson(row, "instances"));
            }
            data.put("instances", instances);

            List<Map<String, Object>> roots = new ArrayList<>();
            if (analysis.gcRootsByKind != null) {
                for (Map.Entry<String, Long> e : analysis.gcRootsByKind.entrySet()) {
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("name", e.getKey());
                    r.put("value", e.getValue());
                    roots.add(r);
                }
            }
            data.put("roots", roots);

            data.put("findings", analysis.findingDetails.stream()
                    .map(f -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("key", f.key());
                        m.put("severity", f.severity());
                        m.put("title", f.title());
                        m.put("detail", f.detail());
                        return m;
                    }).toList());
            data.put("conclusions", analysis.conclusions);
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to build hprof html data", e);
        }
    }

    /**
    * One chart data row.
    *
    * @param row histogram row
    * @param mode retained / instances
    * @return the row map
    */
    private static Map<String, Object> rowJson(HprofHistogramRow row, String mode) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", row.getSimpleName().isEmpty() ? row.getClassName() : row.getSimpleName());
        m.put("className", row.getClassName());
        m.put("count", row.getInstanceCount());
        m.put("retained", row.getRetainedSize());
        m.put("shallow", row.getShallowSize());
        m.put("mode", mode);
        return m;
    }

    /**
    * Append the HTML closing tags.
    *
    * @param sb output buffer
    */
    private static void appendHtmlTail(StringBuilder sb) {
        sb.append("</div>\n</body>\n</html>\n");
    }

    /**
    * Escape a string for safe HTML insertion.
    *
    * @param value value
    * @return escaped value
    */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
