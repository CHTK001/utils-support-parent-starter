package com.chua.hprof.support.analyzer;

import com.chua.hprof.support.crash.CrashContext;
import com.chua.hprof.support.crash.CrashContext.CrashSignal;
import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 基于规则的堆转储分析引擎。
 *
 * <p>将解析得到的 {@link HprofParser.Result} 转化为人类可读的结论和
 * 图表数据。算法遍历类直方图并应用一组固定启发式规则，让 LLM（或人工）
 * 无需重新读取原始转储即可读到清晰的"为什么内存高"的答案：</p>
 *
 * <ol>
 *   <li><b>Top 保留集中度</b> - Top-N 类占总保留内存的比例。集中度高说明
 *   特定泄漏持有者而非普遍膨胀。</li>
 *   <li><b>集合增长</b> - 集合与数组类（{@code java.util.*}、
 *   {@code java.lang.Object[]}、原始数组）的聚合浅层大小。大值说明无界
 *   缓存 / 列表。</li>
 *   <li><b>类加载器扇出</b> - 不同 {@code *ClassLoader} 类的数量及其
 *   保留总量。数百个类加载器通常意味着插件 / OSGi / Groovy / Kotlin
 *   脚本泄漏。</li>
 *   <li><b>String / char[] / byte[] 权重</b> - string 与 byte 数组类
 *   持有的保留量；大值说明文本 / 二进制缓存膨胀（源码缓存、图标缓存、
 *   反编译字节码等）。</li>
 *   <li><b>GC 根分布</b> - 哪类根持有存活对象集；线程对象主导的根指向
 *   线程局部，JNI global 根指向 native 持有引用。</li>
 *   <li><b>非 JDK 包占比</b> - 区分 JDK 自带类（{@code java.*} /
 *   {@code javax.*} / {@code jdk.*}）与用户 / 第三方类，按包前缀分组
 *   统计保留量，定位"是哪个包的代码泄漏"。</li>
 *   <li><b>根因判定</b> - 综合以上规则输出"最可能的根因"一句话结论，
 *   直接回答"为什么产生了这么多对象"。</li>
 * </ol>
 *
 * <p>引擎无状态、确定性：同输入同输出。面对异常直方图从不抛错；每条
 * 规则在其前置数据缺失时退化为中性 finding。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofAnalyzer {

    /**
    * 集中度规则统计的 Top N 类数量。
    */
    private static final int CONCENTRATION_TOP_N = 10;

    /**
    * JDK 自带类的包前缀（用于区分"非 JDK"）。
    */
    private static final String[] JDK_PREFIXES = {
            "java.", "javax.", "jdk.", "com.sun.", "sun.",
            "org.w3c.", "org.xml.", "org.omg."
    };

    /**
     * 构造方法，创建 HprofAnalyzer 实例。
     */
    private HprofAnalyzer() {
    }

    /**
    * 分析一个已解析的 hprof 结果。
    *
    * @param result 解析结果
    * @return 分析报告
    */
    public static HprofAnalysis analyze(HprofParser.Result result) {
        return analyze(result, null);
    }

    /**
    * 分析一个已解析的 hprof 结果，并叠加崩溃语境。
    *
    * @param result    解析结果
    * @param hprofFile hprof 源文件（用于崩溃信号探测，可为 null）
    * @return 分析报告
    */
    public static HprofAnalysis analyze(HprofParser.Result result, java.io.File hprofFile) {
        Objects.requireNonNull(result, "result");
        HprofAnalysis analysis = new HprofAnalysis();
        analysis.totalRetainedBytes = result.totalRetainedBytes();
        analysis.totalObjectCount = result.totalObjectCount();

        // 崩溃语境探测（OOM 信号 / 堆水位 / hs_err 伴随日志）
        analysis.crashSignals =
                CrashContext.detect(hprofFile, result.totalRetainedBytes(), result.totalObjectCount());
        analysis.oomLikely = analysis.crashSignals.stream().anyMatch(CrashSignal::oomLikely);

        List<HprofHistogramRow> histogram = result.histogram();
        analysis.topByRetained = topRows(histogram, 20);
        analysis.topByInstances = topInstances(histogram, 20);

        // 规则 1：Top-N 集中度
        long total = Math.max(result.totalRetainedBytes(), 1L);
        long topN = 0L;
        int counted = 0;
        for (HprofHistogramRow row : analysis.topByRetained) {
            if (row.getRetainedSize() <= 0) {
                continue;
            }
            topN += row.getRetainedSize();
            counted++;
            if (counted >= CONCENTRATION_TOP_N) {
                break;
            }
        }
        analysis.topNRetainedRatio = (double) topN / total;
        analysis.topNRetainedBytes = topN;

        // 规则 2：集合 + 数组权重
        long collectionBytes = 0L;
        long collectionCount = 0L;
        for (HprofHistogramRow row : histogram) {
            if (isCollectionOrArray(row.getClassName())) {
                collectionBytes += row.getRetainedSize();
                collectionCount += row.getInstanceCount();
            }
        }
        analysis.collectionRetainedBytes = collectionBytes;
        analysis.collectionInstances = collectionCount;

        // 规则 3：类加载器扇出
        long classLoaderClasses = 0L;
        long classLoaderRetained = 0L;
        List<HprofHistogramRow> classLoaderRows = new ArrayList<>();
        for (HprofHistogramRow row : histogram) {
            if (row.getClassName() != null
                    && row.getClassName().toLowerCase(Locale.ROOT).contains("classloader")) {
                classLoaderClasses++;
                classLoaderRetained += row.getRetainedSize();
                classLoaderRows.add(row);
            }
        }
        analysis.classLoaderClassCount = classLoaderClasses;
        analysis.classLoaderRetainedBytes = classLoaderRetained;
        analysis.topClassLoaders = topRows(classLoaderRows, 10);

        // 规则 4：string / byte 权重
        long stringBytes = 0L;
        for (HprofHistogramRow row : histogram) {
            String name = row.getClassName();
            if (name != null
                    && (name.equals("java.lang.String")
                    || name.equals("char[]")
                    || name.equals("byte[]")
                    || name.equals("java.lang.StringBuilder")
                    || name.equals("java.lang.StringBuffer"))) {
                stringBytes += row.getRetainedSize();
            }
        }
        analysis.stringAndBinaryRetainedBytes = stringBytes;

        // 规则 5：GC 根分布
        analysis.gcRootsByKind = result.gcRootsByKind();

        // 规则 6：非 JDK 包分组
        analysis.jdkRetainedBytes = 0L;
        analysis.nonJdkRetainedBytes = 0L;
        analysis.nonJdkPackageGroups = groupByPackage(histogram, false, 10);
        analysis.jdkPackageGroups = groupByPackage(histogram, true, 10);
        long jdk = 0L;
        long nonJdk = 0L;
        for (HprofHistogramRow row : histogram) {
            long retained = row.getRetainedSize();
            if (isJdkClass(row.getClassName())) {
                jdk += retained;
            } else {
                nonJdk += retained;
            }
        }
        analysis.jdkRetainedBytes = jdk;
        analysis.nonJdkRetainedBytes = nonJdk;

        // 综合 findings + 结论 + 根因（叠加崩溃语境）
        analysis.findingDetails = buildFindings(analysis, result);
        analysis.conclusions = buildConclusions(analysis, result);
        buildRootCause(analysis, result);
        return analysis;
    }

    /**
    * 按包前缀分组统计保留量。
    *
    * @param rows        直方图行
    * @param jdkOnly     true 只统计 JDK 类，false 只统计非 JDK 类
    * @param limit       分组数量上限
    * @return 包分组列表（按保留量降序）
    */
    private static List<PackageGroup> groupByPackage(List<HprofHistogramRow> rows,
                                                      boolean jdkOnly, int limit) {
        Map<String, long[]> groups = new java.util.LinkedHashMap<>();
        for (HprofHistogramRow row : rows) {
            if (row.getRetainedSize() <= 0) {
                continue;
            }
            boolean isJdk = isJdkClass(row.getClassName());
            if (isJdk != jdkOnly) {
                continue;
            }
            String pkg = packageOf(row.getClassName());
            long[] acc = groups.computeIfAbsent(pkg, k -> new long[2]);
            acc[0] += row.getInstanceCount();
            acc[1] += row.getRetainedSize();
        }
        List<PackageGroup> list = new ArrayList<>();
        for (Map.Entry<String, long[]> e : groups.entrySet()) {
            list.add(new PackageGroup(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        list.sort(Comparator.comparingLong((PackageGroup g) -> g.retained).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /**
    * 提取类的包名前缀（到第二个点为止）。
    *
    * @param className 类全名
    * @return 包前缀，无包时返回 "(default)"
    */
    private static String packageOf(String className) {
        if (className == null) {
            return "(未知)";
        }
        // 取到倒数第二个点之前
        int last = className.lastIndexOf('.');
        if (last <= 0) {
            return className.endsWith("[]") ? "(数组)" : "(默认包)";
        }
        int secondLast = className.lastIndexOf('.', last - 1);
        if (secondLast < 0) {
            // java.lang.String -> java.lang
            return className.substring(0, last);
        }
        // 取前两段包：com.intellij.util -> com.intellij
        return className.substring(0, secondLast + 1);
    }

    /**
    * 是否为 JDK 自带类。
    *
    * @param className 类全名
    * @return true 为 JDK 类
    */
    private static boolean isJdkClass(String className) {
        if (className == null) {
            return false;
        }
        if (className.endsWith("[]")) {
            // 数组类视为 JDK
            return true;
        }
        for (String prefix : JDK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
    * 按保留大小排行（降序），跳过零保留行。
    *
    * @param rows  源行
    * @param limit 上限
    * @return 排行行
    */
    private static List<HprofHistogramRow> topRows(List<HprofHistogramRow> rows, int limit) {
        List<HprofHistogramRow> out = new ArrayList<>(Math.min(limit, rows.size()));
        for (HprofHistogramRow row : rows) {
            if (row.getRetainedSize() <= 0) {
                continue;
            }
            out.add(row);
            if (out.size() >= limit) {
                break;
            }
        }
        out.sort(Comparator.comparingLong(HprofHistogramRow::getRetainedSize).reversed());
        return out;
    }

    /**
    * 按实例数排行（降序）。
    *
    * @param rows  源行
    * @param limit 上限
    * @return 排行行
    */
    private static List<HprofHistogramRow> topInstances(List<HprofHistogramRow> rows, int limit) {
        List<HprofHistogramRow> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparingLong(HprofHistogramRow::getInstanceCount).reversed());
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    /**
    * 类名是否属于 JDK 集合 / 数组族。
    *
    * @param className 类全名
    * @return java.util 集合、Object[] 与原始数组为 true
    */
    private static boolean isCollectionOrArray(String className) {
        if (className == null) {
            return false;
        }
        if (className.startsWith("java.util.")
                || className.startsWith("java.util.concurrent.")
                || className.startsWith("java.util.function.")
                || className.equals("java.util.Arrays")
                || className.endsWith("[]")
                || className.startsWith("it.unimi.dsi.fastutil.")) {
            return true;
        }
        return false;
    }

    /**
    * 构建结构化 findings 列表。
    *
    * @param analysis 进行中的分析
    * @param result   解析结果
    * @return finding 列表，严重程度降序
    */
    private static List<HprofFinding> buildFindings(HprofAnalysis analysis, HprofParser.Result result) {
        List<HprofFinding> findings = new ArrayList<>();
        long total = Math.max(analysis.totalRetainedBytes, 1L);

        if (analysis.topNRetainedRatio >= 0.5) {
            findings.add(new HprofFinding("high_concentration", "high",
                    "Top-" + CONCENTRATION_TOP_N + " 类占用 "
                            + percent(analysis.topNRetainedRatio) + " 存活内存",
                    "内存被少数持有者主导 - 请检查 Top 保留列表，定位直接的根因"
                            + "（缓存、注册表或泄漏容器）。"));
        }

        if (analysis.collectionRetainedBytes > total * 0.3) {
            findings.add(new HprofFinding("collection_bloat", "high",
                    "集合 + 数组持有 " + HprofObject.formatSize(analysis.collectionRetainedBytes)
                            + "（占存活内存 " + percent(analysis.collectionRetainedBytes / (double) total)
                            + "），共 " + analysis.collectionInstances + " 个实例",
                    "检查是否存在无界缓存、监听器列表或长生命周期的请求级集合。"
                            + "单个持有者保留数百万个小对象是典型的泄漏模式。"));
        }

        if (analysis.classLoaderClassCount >= 50) {
            findings.add(new HprofFinding("classloader_fanout", "high",
                    analysis.classLoaderClassCount + " 个不同的类加载器类，保留 "
                            + HprofObject.formatSize(analysis.classLoaderRetainedBytes),
                    "每个插件 / 脚本 / OSGi 模块有独立加载器属正常，但同类型加载器达数十个"
                            + "通常意味着不可卸载的模块被持续持有 - IDE 中 metaspace + 堆增长的常见根因。"));
        }

        if (analysis.stringAndBinaryRetainedBytes > total * 0.1) {
            findings.add(new HprofFinding("string_bloat", "medium",
                    "String / byte[] / char[] 类型保留 "
                            + HprofObject.formatSize(analysis.stringAndBinaryRetainedBytes),
                    "通常是大缓存的载荷（文本、字节码、图标、图片）。找出包裹这些数组的"
                            + "持有类 - 持有者是泄漏源，数组只是症状。"));
        }

        // 非 JDK 占比 finding
        long nonJdk = analysis.nonJdkRetainedBytes;
        if (nonJdk > total * 0.2 && !analysis.nonJdkPackageGroups.isEmpty()) {
            PackageGroup top = analysis.nonJdkPackageGroups.get(0);
            findings.add(new HprofFinding("non_jdk_holder", "high",
                    "非 JDK 类保留 " + HprofObject.formatSize(nonJdk)
                            + "（占 " + percent(nonJdk / (double) total) + "），"
                            + "主要来自 " + top.name + " 包（"
                            + HprofObject.formatSize(top.retained) + "）",
                    "内存主要由业务 / 第三方代码持有，属于代码泄漏或业务缓存膨胀，"
                            + "而非 JDK 自身。优先检查 " + top.name + " 下的缓存、静态字段"
                            + "与监听器注册。"));
        }

        // GC 根 finding
        if (result.gcRootsByKind() != null && !result.gcRootsByKind().isEmpty()) {
            long jni = result.gcRootsByKind().getOrDefault("JNI global", 0L);
            if (jni > 1000) {
                findings.add(new HprofFinding("jni_grefs", "medium",
                        jni + " 个 JNI 全局引用",
                        "native 代码通过 JNI GlobalRef 持有 Java 对象。请验证 native 库"
                                + "（JNI、NIO、Swing）是否释放引用；JNI 泄漏表现为大量"
                                + "看似不可达的对象仍被根持有。"));
            }
            long thread = result.gcRootsByKind().getOrDefault("thread object", 0L);
            if (thread > 500) {
                findings.add(new HprofFinding("thread_locals", "medium",
                        thread + " 个线程对象根",
                        "线程栈 / 局部变量在线程存活期间持续持有其对象。泄漏的线程池"
                                + "会让大对象图无法回收。"));
            }
        }

        if (findings.isEmpty()) {
            HprofObject top = result.topRetained().isEmpty()
                    ? null
                    : result.topRetained().get(0);
            if (top != null && top.getRetainedSize() > 0) {
                findings.add(new HprofFinding("top_holder", "info",
                        "最大持有者：" + top.getClassName()
                                + "（保留 " + HprofObject.formatSize(top.getRetainedSize()) + "）",
                        "未触发强泄漏启发式；请核对该持有者在当前工作负载下是否属预期。"));
            } else {
                findings.add(new HprofFinding("clean", "info",
                        "未触发强泄漏启发式",
                        "堆主要由常规 JDK 类型构成；转储健康，或泄漏低于启发式阈值。"));
            }
        }
        findings.sort(Comparator.comparing(HprofFinding::getSeverity).reversed());
        return findings;
    }

    /**
    * 综合用户可见的结论句。
    *
    * @param analysis 进行中的分析
    * @param result   解析结果
    * @return 结论字符串列表，每段一条
    */
    private static List<String> buildConclusions(HprofAnalysis analysis, HprofParser.Result result) {
        List<String> conclusions = new ArrayList<>();
        conclusions.add("存活堆共 " + result.totalObjectCount()
                + " 个对象，总保留 " + HprofObject.formatSize(analysis.totalRetainedBytes) + "。");
        conclusions.add("Top-" + CONCENTRATION_TOP_N + " 类占保留内存 "
                + percent(analysis.topNRetainedRatio)
                + (analysis.topNRetainedRatio >= 0.5 ? " - 高度集中，请检查持有者。" : "。"));
        conclusions.add("集合 + 数组：保留 " + HprofObject.formatSize(analysis.collectionRetainedBytes)
                + "，共 " + analysis.collectionInstances + " 个实例。");
        conclusions.add("字符串 / 二进制载荷：保留 "
                + HprofObject.formatSize(analysis.stringAndBinaryRetainedBytes) + "。");
        conclusions.add("类加载器：" + analysis.classLoaderClassCount
                + " 个不同加载器类，保留 "
                + HprofObject.formatSize(analysis.classLoaderRetainedBytes) + "。");
        long nonJdk = analysis.nonJdkRetainedBytes;
        long total = Math.max(analysis.totalRetainedBytes, 1L);
        conclusions.add("非 JDK 类保留 " + HprofObject.formatSize(nonJdk)
                + "（占 " + percent(nonJdk / (double) total) + "），"
                + "JDK 类保留 " + HprofObject.formatSize(analysis.jdkRetainedBytes) + "。");
        if (result.gcRootsByKind() != null && !result.gcRootsByKind().isEmpty()) {
            Map<String, Long> roots = result.gcRootsByKind();
            StringBuilder sb = new StringBuilder("GC 根分布：");
            boolean first = true;
            for (Map.Entry<String, Long> e : roots.entrySet()) {
                if (!first) {
                    sb.append("，");
                }
                first = false;
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            sb.append("。");
            conclusions.add(sb.toString());
        }
        return conclusions;
    }

    /**
    * 综合根因判定，并填充结构化字段（供 HTML 报告 / MCP 诊断卡直接消费）。
    *
    * <p>产出三部分：一句话结论 {@code rootCauseHeadline}、分节明细
    * {@code rootCauseSections}（崩溃判定 / 主要根因 / 具体机制 / 对象来源 /
    * 优先处置），并把分节拼成向后兼容的纯文本 {@code rootCause}。命中
    * 的具体机制单独存入 {@code rootCauseMechanisms}。</p>
    *
    * @param analysis 进行中的分析（crashSignals / oomLikely 已在前序步骤填好）
    * @param result   解析结果
    */
    private static void buildRootCause(HprofAnalysis analysis, HprofParser.Result result) {
        List<CrashSignal> crashSignals = analysis.crashSignals;
        List<RootCauseSection> sections = new ArrayList<>();
        long total = Math.max(analysis.totalRetainedBytes, 1L);
        long nonJdk = analysis.nonJdkRetainedBytes;

        // 0. 崩溃语境判定
        if (crashSignals != null && !crashSignals.isEmpty()) {
            CrashSignal dominant = crashSignals.stream()
                    .filter(CrashSignal::oomLikely)
                    .findFirst().orElse(crashSignals.get(0));
            boolean anyOom = crashSignals.stream().anyMatch(CrashSignal::oomLikely);
            sections.add(new RootCauseSection("崩溃判定",
                    dominant.detail() + "。" + (anyOom
                            ? "结合存活堆水位与伴随信号，本次崩溃高度疑似内存耗尽（OOM）。"
                            : "未捕获到明确的 OOM / native crash 伴随证据，崩溃更可能由其他原因"
                            + "（GC 风暴、堆外内存、native 段错误）引发，需结合 hs_err 日志或 GC 日志确认。")));
        }

        // 1. 主要根因：业务 / 第三方代码持有 vs JDK 自身为主
        PackageGroup top = null;
        String primarySource;
        if (nonJdk > total * 0.2 && !analysis.nonJdkPackageGroups.isEmpty()) {
            top = analysis.nonJdkPackageGroups.get(0);
            primarySource = top.name + " 包（业务 / 第三方）";
            sections.add(new RootCauseSection("主要根因",
                    "业务 / 第三方代码持有 - " + top.name + " 包保留 "
                            + HprofObject.formatSize(top.retained)
                            + "（占非 JDK 的 "
                            + percent(top.retained / (double) Math.max(nonJdk, 1L)) + "）"));
        } else {
            primarySource = "JDK 运行时自身类型";
            sections.add(new RootCauseSection("主要根因",
                    "JDK 自身类型为主，无单一业务包主导"));
        }

        // 2. 具体机制
        List<String> mechanisms = new ArrayList<>();
        if (analysis.classLoaderClassCount >= 50) {
            mechanisms.add("类加载器泄漏（" + analysis.classLoaderClassCount
                    + " 个加载器类，疑似插件 / 脚本未卸载）");
        }
        if (analysis.collectionRetainedBytes > total * 0.3) {
            mechanisms.add("无界集合 / 缓存（" + HprofObject.formatSize(analysis.collectionRetainedBytes)
                    + " 保留在集合 + 数组）");
        }
        if (analysis.stringAndBinaryRetainedBytes > total * 0.1) {
            mechanisms.add("字符串 / 二进制缓存膨胀（"
                    + HprofObject.formatSize(analysis.stringAndBinaryRetainedBytes) + "）");
        }
        Map<String, Long> roots = result.gcRootsByKind();
        if (roots != null) {
            long thread = roots.getOrDefault("thread object", 0L);
            if (thread > 500) {
                mechanisms.add("线程池泄漏（" + thread + " 个线程对象根）");
            }
            long jni = roots.getOrDefault("JNI global", 0L);
            if (jni > 1000) {
                mechanisms.add("JNI 全局引用未释放（" + jni + " 个）");
            }
        }
        analysis.rootCauseMechanisms = mechanisms;
        if (!mechanisms.isEmpty()) {
            sections.add(new RootCauseSection("具体机制", String.join("；", mechanisms)));
        } else if (analysis.topNRetainedRatio < 0.5) {
            sections.add(new RootCauseSection("具体机制",
                    "保留量分散（Top-10 仅占 " + percent(analysis.topNRetainedRatio) + "），无单一热点"));
        } else {
            sections.add(new RootCauseSection("具体机制",
                    "Top-10 类高度集中（占 " + percent(analysis.topNRetainedRatio) + "），热点明确"));
        }

        // 3. 对象来源 + 优先处置
        sections.add(new RootCauseSection("对象来源", "大量对象主要来自 " + objectOrigin(analysis)));
        sections.add(new RootCauseSection("优先处置", firstAction(analysis, result)));

        analysis.rootCauseSections = sections;

        // 一句话结论：疑似 OOM 判定 + 主要内存来源
        String sourceDesc = top != null
                ? top.name + " 包（业务 / 第三方，保留 " + HprofObject.formatSize(top.retained) + "）"
                : primarySource;
        analysis.rootCauseHeadline = (analysis.oomLikely
                ? "高度疑似 OOM 崩溃"
                : (crashSignals != null && !crashSignals.isEmpty()
                ? "崩溃原因不确定（无明确 OOM 证据）"
                : "未发现崩溃信号")) + "；内存主要来自 " + sourceDesc;

        // 向后兼容：把分节拼成纯文本根因
        StringBuilder sb = new StringBuilder();
        for (RootCauseSection s : sections) {
            sb.append("【").append(s.label()).append("】").append(s.text()).append("\n");
        }
        analysis.rootCause = sb.toString().trim();
    }

    /**
    * 说明"这么多对象"的来源。
    *
    * @param analysis 分析
    * @return 来源说明
    */
    private static String objectOrigin(HprofAnalysis analysis) {
        if (analysis.collectionInstances > 1_000_000L) {
            return "无界集合 / 缓存不断累积小对象（" + analysis.collectionInstances
                    + " 个集合 + 数组实例）";
        }
        if (analysis.classLoaderClassCount >= 50) {
            return "多个类加载器各自加载了重复的类与元数据";
        }
        return "常规 JDK 容器与运行时对象";
    }

    /**
    * 给出第一条最优先处置建议。
    *
    * @param analysis 分析
    * @param result   解析结果
    * @return 建议文本
    */
    private static String firstAction(HprofAnalysis analysis, HprofParser.Result result) {
        if (!analysis.nonJdkPackageGroups.isEmpty()
                && analysis.nonJdkRetainedBytes > analysis.totalRetainedBytes * 0.2) {
            PackageGroup top = analysis.nonJdkPackageGroups.get(0);
            return "审查 " + top.name + " 包下的静态缓存 / 监听器注册，确认是否有未清理的强引用";
        }
        if (analysis.classLoaderClassCount >= 50) {
            return "排查插件 / 脚本类加载器泄漏，确认卸载机制是否生效";
        }
        if (analysis.collectionRetainedBytes > analysis.totalRetainedBytes * 0.3) {
            return "为最大集合加容量上限或引入 LRU 淘汰";
        }
        if (!result.topRetained().isEmpty() && result.topRetained().get(0).getRetainedSize() > 0) {
            return "检查最大持有者 " + result.topRetained().get(0).getClassName()
                    + " 的创建与释放路径";
        }
        return "对比两次转储的直方图，定位增长最快的类";
    }

    /**
    * 将比例格式化为百分数字符串。
    *
    * @param ratio 0..1
    * @return 百分比字符串
    */
    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0);
    }

    /**
    * 包分组统计行。
    *
    * @param name    包前缀
    * @param instances 实例总数
    * @param retained 保留字节总数
    * @author CH
    * @since 4.0.0.42
    * @return 结果值
    */
    public record PackageGroup(String name, long instances, long retained) {
    }

    /**
    * 根因判定的一个分节（如"崩溃判定""主要根因"）。
    *
    * <p>结构化暴露，供 HTML 报告、MCP 诊断卡按节渲染，避免各自再解析
    * 一整段自由文本。</p>
    *
    * @param label 分节标题（人类可读）
    * @param text  分节正文
    * @author CH
    * @since 4.0.0.42
    * @return 结果值
    */
    public record RootCauseSection(String label, String text) {
    }

    /**
    * 单个分析 finding。
    *
    * @param key      稳定机器 key
    * @param severity "high" / "medium" / "low" / "info"
    * @param title    简短人类标题
    * @param detail   "为什么"说明
    * @author CH
    * @since 4.0.0.42
    * @return 结果值
    */
    public record HprofFinding(String key, String severity, String title, String detail) {

        /**
        * 用于排序的数值严重度（越大越严重）。
        *
        * @return 3 high，2 medium，1 low，0 info
        */
        public int getSeverity() {
            return switch (severity) {
                case "high" -> 3;
                case "medium" -> 2;
                case "low" -> 1;
                default -> 0;
            };
        }
    }

    /**
    * {@link #analyze(HprofParser.Result)} 返回的完整分析报告。
    *
    * <p>所有字段一次计算完成；HTML / Markdown / JSON 渲染器直接读取，
    * 无需重新遍历直方图。</p>
    *
    * @author CH
    * @since 4.0.0.42
    */
    public static final class HprofAnalysis {

        /**
        * 转储中的总保留字节。
        */
        public long totalRetainedBytes;

        /**
        * 总存活实例数。
        */
        public long totalObjectCount;

        /**
        * 图表用的 Top 保留行。
        */
        public List<HprofHistogramRow> topByRetained;

        /**
        * 图表用的 Top 实例数行。
        */
        public List<HprofHistogramRow> topByInstances;

        /**
        * Top-N 类持有的保留字节。
        */
        public long topNRetainedBytes;

        /**
        * Top-N 类占总保留的比例（0..1）。
        */
        public double topNRetainedRatio;

        /**
        * 集合 + 数组类持有的保留字节。
        */
        public long collectionRetainedBytes;

        /**
        * 集合 + 数组类的实例总数。
        */
        public long collectionInstances;

        /**
        * 不同的类加载器类数量。
        */
        public long classLoaderClassCount;

        /**
        * 类加载器类保留字节。
        */
        public long classLoaderRetainedBytes;

        /**
        * Top 类加载器行。
        */
        public List<HprofHistogramRow> topClassLoaders;

        /**
        * String / byte[] / char[] 族保留字节。
        */
        public long stringAndBinaryRetainedBytes;

        /**
        * GC 根按类型计数。
        */
        public Map<String, Long> gcRootsByKind;

        /**
        * JDK 类保留字节。
        */
        public long jdkRetainedBytes;

        /**
        * 非 JDK 类保留字节。
        */
        public long nonJdkRetainedBytes;

        /**
        * 非 JDK 包分组排行。
        */
        public List<PackageGroup> nonJdkPackageGroups;

        /**
        * JDK 包分组排行。
        */
        public List<PackageGroup> jdkPackageGroups;

        /**
        * 结构化 findings，严重程度降序。
        */
        public List<HprofFinding> findingDetails;

        /**
        * 白话结论段落。
        */
        public List<String> conclusions;

        /**
        * 根因判定（回答"为什么这么多对象"）。
        */
        public String rootCause;

        /**
        * 一句话根因结论（疑似 OOM 判定 + 主要内存来源）。
        */
        public String rootCauseHeadline;

        /**
        * 结构化根因分节（崩溃判定 / 主要根因 / 具体机制 / 对象来源 / 优先处置）。
        */
        public List<RootCauseSection> rootCauseSections;

        /**
        * 命中的具体泄漏机制列表（类加载器 / 无界集合 / 字符串缓存 / 线程池 / JNI）。
        */
        public List<String> rootCauseMechanisms;

        /**
        * 崩溃语境信号（OOM 推断 / hs_err / 堆水位）。
        */
        public List<CrashSignal> crashSignals;

        /**
        * 是否强烈疑似 OOM 崩溃。
        */
        public boolean oomLikely;
    }
}
