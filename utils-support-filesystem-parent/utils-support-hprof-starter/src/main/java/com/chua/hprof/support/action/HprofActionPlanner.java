package com.chua.hprof.support.action;

import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofAnalysis;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 处置计划生成器：从分析结果映射到"给用户的具体处理步骤"。
 *
 * <p>报告不止告诉用户"哪里有问题"，更告诉用户"怎么处理"。
 * 每个 {@link ActionItem} 包含：做什么、怎么做（可执行路径）、
 * 预期效果、验证方法（如何确认处理有效）。渲染为可勾选清单后，
 * 用户处理完一项打勾一项，最后按验证方法复核。</p>
 *
 * <p>规则是确定性的：命中某条分析规则 => 生成对应的处置项。
 * 针对 IntelliJ 特征（{@code com.intellij.*} 包占比高 + 类加载器多）
 * 额外给出 IDEA 专属步骤。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofActionPlanner {

    /**
     * 构造方法，创建 HprofActionPlanner 实例。
     */
    private HprofActionPlanner() {
    }

    /**
     * 单条处置项。
     *
     * @param id            稳定机器 id（用于勾选状态）
     * @param title         做什么（一句话）
     * @param how           怎么做（可执行路径 / 命令 / 设置项）
     * @param expectedEffect 预期效果（释放多少内存 / 消除什么）
     * @param verify        验证方法（处理后如何确认有效）
     * @param priority      优先级 1（最高）.. 3
     * @author CH
     * @since 4.0.0.42
     */
    public record ActionItem(String id, String title, String how,
                              String expectedEffect, String verify, int priority) {
    }

    /**
     * 完整处置计划。
     *
     * @param problemSummary  一段话概括问题（非开发人员可读）
     * @param items           处置步骤（按优先级排序）
     * @param ideaSpecific    是否含 IDEA 专属步骤
     * @author CH
     * @since 4.0.0.42
     * @return 结果值
     */
    public record ActionPlan(String problemSummary, List<ActionItem> items, boolean ideaSpecific) {
    }

    /**
     * 从分析结果生成处置计划。
     *
     * @param result   解析结果
     * @param analysis 分析结果
     * @return 处置计划
     */
    public static ActionPlan plan(HprofParser.Result result, HprofAnalysis analysis) {
        List<ActionItem> items = new ArrayList<>();
        boolean idea = isIntelliJ(analysis);
        String topPkg = analysis.nonJdkPackageGroups.isEmpty()
                ? "" : analysis.nonJdkPackageGroups.get(0).name();
        String topPkgText = topPkg.endsWith(".") ? topPkg : topPkg + ".";

        // 问题概括（大白话）
        StringBuilder problem = new StringBuilder();
        problem.append("堆里 ").append(HprofObject.formatSize(analysis.totalRetainedBytes))
                .append(" 存活内存中，").append(HprofObject.formatSize(analysis.nonJdkRetainedBytes))
                .append(" 被业务代码持有（占 ")
                .append(percent(analysis.nonJdkRetainedBytes / (double) Math.max(analysis.totalRetainedBytes, 1L)))
                .append("）");
        if (idea) {
            problem.append("，是 IntelliJ 的 ").append(topPkgText)
                    .append(" 符号注册表 + ").append(analysis.classLoaderClassCount)
                    .append(" 个未卸载的插件类加载器把堆撑满，极可能是 OOM 崩溃");
        } else {
            problem.append("，主要来自 ").append(topPkgText).append(" 包的缓存 / 静态引用");
        }

        int seq = 0;
        // 1. IDEA 专属（命中时最高优先级）
        if (idea) {
            items.add(item(++seq, "idea-disable-webtypes", 1,
                    "禁用 / 降级 IntelliJ Web Types 符号注册（polySymbols）",
                    "IntelliJ IDEA: Settings → Languages & Frameworks → Web Types，"
                            + "取消勾选 JS/TS polySymbols，或升级到最新 IDEA 版本"
                            + "（webTypes 注册表已优化）。临时方案：File → Invalidate Caches 清缓存后重启",
                    "预期释放 " + HprofObject.formatSize(analysis.nonJdkRetainedBytes)
                            + " 中的 " + HprofObject.formatSize(topPkgBytes(analysis)),
                    "处理后重新导出 hprof，确认 " + topPkgText
                            + " 包保留量大幅下降、Contributions/Html 类不再霸榜"));
            items.add(item(++seq, "idea-reload-plugin", 1,
                    "排查 " + analysis.classLoaderClassCount + " 个类加载器：禁用 / 重装泄漏插件",
                    "IntelliJ IDEA: Settings → Plugins，逐个禁用最近安装 / 启用的插件；"
                            + "观察 类加载器数量（com.intellij.ide.plugins.cl.PluginClassLoader）是否随之下降",
                    "预期消除类加载器泄漏（当前 " + HprofObject.formatSize(analysis.classLoaderRetainedBytes)
                            + " + metaspace 增长）",
                    "重启后导出 hprof，com.intellij.ide.plugins.cl 类加载器实例数应显著减少"));
        }

        // 2. 无界集合
        if (analysis.collectionRetainedBytes > analysis.totalRetainedBytes * 0.3) {
            items.add(item(++seq, "bound-collections", 2,
                    "为最大集合加容量上限 / LRU 淘汰",
                    "对 " + topPkgText + " 及 java.util.* 的无界 Map/List 加 Caffeine/Guava LRU "
                            + "或显式 cap（如最大 10 万条）；监听器注册要对称反注册",
                    "预期消除 " + HprofObject.formatSize(analysis.collectionRetainedBytes)
                            + " 集合 + 数组保留",
                    "跑压测后导出 hprof，集合实例数应从 "
                            + analysis.collectionInstances + " 明显回落"));
        }

        // 3. 字符串 / 二进制缓存
        if (analysis.stringAndBinaryRetainedBytes > analysis.totalRetainedBytes * 0.1) {
            items.add(item(++seq, "trim-string-cache", 2,
                    "收紧字符串 / 二进制缓存 TTL",
                    "定位持有 String/byte[] 的缓存（图标、源码、反编译字节码），"
                            + "加过期时间 + 上限，避免长期驻留",
                    "预期释放 " + HprofObject.formatSize(analysis.stringAndBinaryRetainedBytes),
                    "GC 后 hprof 中 java.lang.String / byte[] 保留量下降"));
        }

        // 4. 线程池
        Map<String, Long> roots = result.gcRootsByKind();
        long thread = roots.getOrDefault("thread object", 0L);
        if (thread > 500) {
            items.add(item(++seq, "kill-threadpool", 2,
                    "收缩泄漏的线程池（" + thread + " 个线程对象根）",
                    "给线程池设上限 + 无任务超时回收；避免 per-request 新建线程；"
                            + "关闭未 shutdown 的 ScheduledExecutor",
                    "预期释放线程栈 + 线程局部变量持有的对象图",
                    "hprof 中 thread object GC 根数量应降到正常水位（几十）"));
        }

        // 5. JNI
        long jni = roots.getOrDefault("JNI global", 0L);
        if (jni > 1000) {
            items.add(item(++seq, "release-jni", 3,
                    "释放 JNI 全局引用（" + jni + " 个）",
                    "native 代码用完 Java 对象要 DeleteGlobalRef；排查 NIO / Swing / 自定义 JNI 库",
                    "预期消除 native 侧持有的 Java 对象",
                    "JNI global GC 根数量显著下降"));
        }

        // 6. 兜底：JVM 参数
        items.add(item(++seq, "tune-heap", 3,
                "（兜底）调大 -Xmx 只是缓解，不解决泄漏",
                "先用上面步骤定位泄漏源；确无泄漏后再评估是否需调大堆 + 加 -XX:MaxMetaspaceSize",
                "治标：争取更多存活空间",
                "GC 日志中 Full GC 频率 / 停顿下降，堆使用率不再 100%"));

        return new ActionPlan(problem.toString(), items, idea);
    }

    /**
     * 是否 IntelliJ 场景。
     *
     * @param analysis 分析
     * @return true 时非 JDK 包排行头部含 com.intellij
     */
    private static boolean isIntelliJ(HprofAnalysis analysis) {
        return !analysis.nonJdkPackageGroups.isEmpty()
                && analysis.nonJdkPackageGroups.get(0).name()
                .toLowerCase(Locale.ROOT).contains("com.intellij");
    }

    /**
     * 头部非 JDK 包的保留字节。
     *
     * @param analysis 分析
     * @return 保留字节
     */
    private static long topPkgBytes(HprofAnalysis analysis) {
        return analysis.nonJdkPackageGroups.isEmpty()
                ? 0L : analysis.nonJdkPackageGroups.get(0).retained();
    }

    /**
     * 构造一条处置项。
     *
     * @param seq    序号
     * @param id     稳定 id
     * @param priority 优先级
     * @param title  标题
     * @param how    怎么做
     * @param effect 预期
     * @param verify 验证
     * @return 处置项
     */
    private static ActionItem item(int seq, String id, int priority, String title,
                                   String how, String effect, String verify) {
        return new ActionItem(id + "_" + seq, title, how, effect, verify, priority);
    }

    /**
     * 百分比格式化。
     *
     * @param ratio 0..1
     * @return 百分比
     */
    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0);
    }
}
