package com.chua.hprof.support.serializer;

import com.chua.hprof.support.analyzer.HprofAnalyzer;
import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofAnalysis;
import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;

import java.util.List;
import java.util.Locale;

/**
 * 将解析得到的 hprof 结果序列化为中文 Markdown 报告。
 *
 * <p>产出类直方表与泄漏嫌疑段落：</p>
 * <pre>
 * | 类名 | 实例数 | 占用内存 | 引用链 |
 * |------|--------|----------|--------|
 * | HashMap | 1 | 1.2 GB | static OrderCache.cache |
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofToMarkdownSerializer {

    /**
     * 构造方法，创建 Hprof转为MarkdownSerializer 实例。
     */
    private HprofToMarkdownSerializer() {
    }

    /**
    * 将解析结果序列化为 Markdown 字符串。
    *
    * @param result   解析结果
    * @param fileName 报告头使用的源文件名
    * @return Markdown 文档
    */
    public static String serialize(HprofParser.Result result, String fileName) {
        HprofAnalysis analysis = HprofAnalyzer.analyze(result,
                fileName == null ? null : new java.io.File(fileName));
        com.chua.hprof.support.action.HprofActionPlanner.ActionPlan plan =
                com.chua.hprof.support.action.HprofActionPlanner.plan(result, analysis);
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, result, fileName);
        appendProblemAndPlan(sb, plan);
        appendCrashContext(sb, analysis);
        appendRootCause(sb, analysis);
        appendClassHistogram(sb, result.histogram());
        appendNonJdk(sb, analysis);
        appendRefChains(sb, result);
        appendLeakSuspects(sb, result.topRetained());
        appendConclusions(sb, analysis);
        appendChecklist(sb, plan);
        return sb.toString();
    }

    /**
    * 追加"问题 + 处理步骤"段落。
    *
    * @param sb   输出缓冲
    * @param plan 处置计划
    */
    private static void appendProblemAndPlan(StringBuilder sb,
                                             com.chua.hprof.support.action.HprofActionPlanner.ActionPlan plan) {
        sb.append("## 问题是什么 · 怎么处理\n\n");
        sb.append(plan.problemSummary()).append("\n\n");
        sb.append("| 优先级 | 做什么 | 怎么做 | 预期效果 |\n");
        sb.append("|--------|--------|--------|----------|\n");
        for (com.chua.hprof.support.action.HprofActionPlanner.ActionItem item : plan.items()) {
            sb.append("| P").append(item.priority())
                    .append(" | ").append(item.title())
                    .append(" | ").append(item.how())
                    .append(" | ").append(item.expectedEffect())
                    .append(" |\n");
        }
        sb.append("\n");
    }

    /**
    * 追加引用链段落。
    *
    * @param sb     输出缓冲
    * @param result 解析结果
    */
    private static void appendRefChains(StringBuilder sb, HprofParser.Result result) {
        if (result.refChains() == null || result.refChains().isEmpty()) {
            return;
        }
        sb.append("## 引用链（谁持有谁）\n\n");
        for (com.chua.hprof.support.parser.HprofRefChainWalker.RefChain chain : result.refChains()) {
            sb.append("- **").append(chain.holderClass())
                    .append("**（#").append(chain.holderId())
                    .append("，保留 ").append(HprofObject.formatSize(chain.holderRetained())).append("）\n");
            if (!chain.fields().isEmpty()) {
                sb.append("  - 自身字段：");
                boolean first = true;
                for (com.chua.hprof.support.model.HprofClassDetail.FieldValueDetail fv : chain.fields()) {
                    if (!first) {
                        sb.append("，");
                    }
                    first = false;
                    sb.append(fv.getName()).append("=").append(fv.getValueText());
                }
                sb.append("\n");
            }
            if (!chain.children().isEmpty()) {
                sb.append("  - 持有子引用（按保留大小）：");
                boolean first = true;
                for (com.chua.hprof.support.parser.HprofRefChainWalker.ChildRef child : chain.children()) {
                    if (!first) {
                        sb.append("；");
                    }
                    first = false;
                    sb.append(child.className()).append(" → ")
                            .append(HprofObject.formatSize(child.retained()))
                            .append(" (#").append(child.instanceId()).append(")");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    /**
    * 追加可勾选处置清单（Markdown 用 - [ ] 复选框）。
    *
    * @param sb   输出缓冲
    * @param plan 处置计划
    */
    private static void appendChecklist(StringBuilder sb,
                                        com.chua.hprof.support.action.HprofActionPlanner.ActionPlan plan) {
        sb.append("## 处置清单（处理一项勾一项）\n\n");
        for (com.chua.hprof.support.action.HprofActionPlanner.ActionItem item : plan.items()) {
            sb.append("- [ ] **P").append(item.priority()).append("** ").append(item.title()).append('\n');
            sb.append("  - 验证：").append(item.verify()).append('\n');
        }
        sb.append("\n");
    }

    /**
    * 追加报告头。
    *
    * @param sb       输出缓冲
    * @param result   解析结果
    * @param fileName 源文件名
    */
    private static void appendHeader(StringBuilder sb, HprofParser.Result result, String fileName) {
        sb.append("# HPROF 堆内存分析\n\n");
        sb.append("- **源文件**：`").append(fileName == null ? "stream" : fileName).append("`\n");
        sb.append("- **存活对象数**：").append(result.totalObjectCount()).append("\n");
        sb.append("- **总保留内存**：").append(HprofObject.formatSize(result.totalRetainedBytes())).append("\n");
        sb.append("\n");
    }

    /**
    * 追加崩溃语境段落（OOM 判定 / 堆水位 / hs_err 证据）。
    *
    * @param sb       输出缓冲
    * @param analysis 分析结果
    */
    private static void appendCrashContext(StringBuilder sb, HprofAnalysis analysis) {
        if (analysis.crashSignals == null || analysis.crashSignals.isEmpty()) {
            return;
        }
        sb.append(analysis.oomLikely
                        ? "## ⚠ 崩溃语境判定（强烈疑似 OOM）\n\n"
                        : "## 崩溃语境判定（证据不足）\n\n");
        for (com.chua.hprof.support.crash.CrashContext.CrashSignal signal : analysis.crashSignals) {
            sb.append("- **").append(signal.kind()).append("**：").append(signal.detail())
                    .append("。*证据：").append(signal.evidence()).append("*\n");
        }
        sb.append("\n");
    }

    /**
    * 追加根因判定段落。
    *
    * @param sb       输出缓冲
    * @param analysis 分析
    */
    private static void appendRootCause(StringBuilder sb, HprofAnalysis analysis) {
        if (analysis.rootCause == null || analysis.rootCause.isBlank()) {
            return;
        }
        sb.append("## 根因判定（为什么产生了这么多对象）\n\n")
                .append(analysis.rootCause).append("\n\n");
    }

    /**
    * 追加类直方表。
    *
    * @param sb   输出缓冲
    * @param rows 直方行
    */
    private static void appendClassHistogram(StringBuilder sb, List<HprofHistogramRow> rows) {
        sb.append("## 类直方图\n\n");
        sb.append("| 类名 | 实例数 | 浅层大小 | 保留大小 |\n");
        sb.append("|------|--------|----------|----------|\n");
        for (HprofHistogramRow row : rows) {
            sb.append("| ").append(row.getSimpleName())
                    .append(" | ").append(row.getInstanceCount())
                    .append(" | ").append(HprofObject.formatSize(row.getShallowSize()))
                    .append(" | ").append(HprofObject.formatSize(row.getRetainedSize()))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    /**
    * 追加非 JDK 包排行段落。
    *
    * @param sb       输出缓冲
    * @param analysis 分析
    */
    private static void appendNonJdk(StringBuilder sb, HprofAnalysis analysis) {
        if (analysis.nonJdkPackageGroups == null || analysis.nonJdkPackageGroups.isEmpty()) {
            return;
        }
        long total = Math.max(analysis.totalRetainedBytes, 1L);
        sb.append("## 非 JDK 类内存排行（代码泄漏定位）\n\n");
        sb.append("非 JDK 类共保留 ")
                .append(HprofObject.formatSize(analysis.nonJdkRetainedBytes))
                .append("（占堆 ")
                .append(String.format(Locale.ROOT, "%.1f%%",
                        analysis.nonJdkRetainedBytes * 100.0 / total)).append("）。\n\n");
        sb.append("| 包前缀（非 JDK） | 实例数 | 保留内存 | 占非 JDK 比 |\n");
        sb.append("|-----------------|--------|----------|-------------|\n");
        for (HprofAnalyzer.PackageGroup group : analysis.nonJdkPackageGroups) {
            double pct = analysis.nonJdkRetainedBytes > 0
                    ? group.retained() * 100.0 / analysis.nonJdkRetainedBytes : 0.0;
            sb.append("| ").append(group.name())
                    .append(" | ").append(group.instances())
                    .append(" | ").append(HprofObject.formatSize(group.retained()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f%%", pct))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    /**
    * 追加泄漏嫌疑表。
    *
    * @param sb       输出缓冲
    * @param suspects  Top 保留对象
    */
    private static void appendLeakSuspects(StringBuilder sb, List<HprofObject> suspects) {
        sb.append("## 泄漏嫌疑（Top 保留）\n\n");
        sb.append("| 类名 | 保留大小 | GC 根 / 引用链 |\n");
        sb.append("|------|----------|----------------|\n");
        for (HprofObject s : suspects) {
            String chain = s.getRefChain() != null ? s.getRefChain()
                    : (s.getGcRoot() != null ? s.getGcRoot() : "unknown");
            sb.append("| ").append(simpleName(s.getClassName()))
                    .append(" | ").append(s.getRetainedSizeText())
                    .append(" | ").append(chain)
                    .append(" |\n");
        }
        sb.append("\n");
    }

    /**
    * 追加结论段落。
    *
    * @param sb       输出缓冲
    * @param analysis 分析
    */
    private static void appendConclusions(StringBuilder sb, HprofAnalysis analysis) {
        sb.append("## 结论\n\n");
        for (String c : analysis.conclusions) {
            sb.append("- ").append(c).append("\n");
        }
        sb.append("\n");
    }

    /**
    * 提取类简单名。
    *
    * @param className 类全名
    * @return 简单名部分
    */
    private static String simpleName(String className) {
        if (className == null || className.isEmpty()) {
            return "unknown";
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }
}
