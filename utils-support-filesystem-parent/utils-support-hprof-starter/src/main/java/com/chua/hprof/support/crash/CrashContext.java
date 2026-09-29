package com.chua.hprof.support.crash;

import com.chua.hprof.support.model.HprofObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃元数据探测。
 *
 * <p>hprof 本身只是一份堆快照，单靠它无法确定"崩溃"的确切原因
 * （OOM、SIGSEGV、线程死锁……）。本工具按以下顺序补齐崩溃语境：</p>
 *
 * <ol>
 *   <li><b>伴随文件探测</b>：JVM 崩溃时会同时生成
 *   {@code hs_err_pid<pid>.log}；IDE 导出 dump 时常见
 *   {@code java_error_in_<app>.hprof} 的命名约定。本工具在 hprof 所在
 *   目录寻找同前缀的 {@code .log} / {@code .txt} 并提取崩溃签名
 *   （Exception 类型、信号、出问题的线程 / 方法）。</li>
 *   <li><b>文件名约定</b>：{@code java_error_in_*} / *{@code _oom}
 *   等模式本身即强 OOM 信号。</li>
 *   <li><b>堆水位推断</b>：把转储中"存活"对象总保留量与常见堆上限
 *   （8G/16G/32G）对比，若已占满典型 Xmx 则判定"堆打满，极可能 OOM"。</li>
 * </ol>
 *
 * <p>全部推断都会标注置信度与依据，报告中不冒充确定性结论。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CrashContext {

    /**
     * 推断出的崩溃信号。
     *
     * <p>一条信号代表一次<b>启发式推断</b>，而非确定性结论：
     * {@link #kind} 说明推断来源，{@link #detail} 是结论文字，
     * {@link #evidence} 是支撑该结论的原始数据（文件名、日志签名、字节数等），
     * {@link #oomLikely} 是「疑似 OOM」这一判断标志。报告展示时
     * 三者需一并呈现，{@link #label()} 会把 OOM 情形收敛为「疑似 OOM」
     * 四个字，避免把推断说成事实。</p>
     *
     * <p>可空性：规范构造器不做任何非空校验，三个 {@code String} 分量在实践
     * 中均被调用点赋非空值（{@link #kind} 固定取自三个字面量），
     * 但类型上允许为 {@code null}，消费方需自行判空。</p>
     *
     * @param kind 信号 类别，即推断来源，取自受控的三个中文常量：
     *                {@code "文件名约定"}（{@code detectFromName}，命中
     *                {@code java_error_in_*} 前缀或 {@code _oom.hprof} /
     *                {@code outofmemory} 命名）、{@code "hs_err 伴随日志"}
     *                （{@code detectCompanionLogs}，同目录找到
     *                {@code hs_err_pid*.log} 并提取到签名）、{@code "堆水位"}
     *                （{@code heapWaterLevel}，按存活堆字节数与 4G/8G/16G
     *                阈值比较）。该值同时是 {@link #detect} 合并降级时的
     *                分流依据（靠 {@code equals} 做字符串比较，不是枚举，
     *                故新增类别必须同步改 {@code detect} 中的比较）
     * @param detail 结论 文字，面向阅读者的自然语言说明，形如
     *                「存活堆 9.5G，≥ 8G，已打满 8G 的常见 Xmx，强烈疑似 OOM」。
     *                内容由 {@code HprofObject#formatSize} 格式化出的可读尺寸
     *                与阈值文案拼接而成；{@code hs_err} 场景下则为从日志中
     *                提取的异常 / 信号签名（原始行以 {@code " | "} 连接）。
     *                取值来源为各 {@code detectXxx} 内的字面量与实时计算结果，
     *                允许为 {@code null}，但 {@code hs_err} 签名会截断到
     *                400 字符以内以控制报告体积
     * @param evidence 推断 依据，即支撑上述结论的可核查原始数据串，与
     *                 {@link #detail} 分开保存以免结论与证据混淆。
     *                 各来源形态不同：文件名来源填命中的文件名；
     *                 {@code hs_err} 来源填「日志文件名（字节数 B）」；
     *                 堆水位来源填 {@code totalRetained=字节数}，
     *                 高水位分支还会附「（对象数）」。允许为 {@code null}。
     *                 注意本字段可被 {@code detect} <b>追加文字</b>：
     *                 当定性 OOM 信号与堆水位证据矛盾时，会在其后拼接
     *                 「（但堆水位未达 4G，OOM 判定存疑，需结合 -Xmx 实际值复核）」
     *                 一类的存疑说明
     * @param oomLikely 是否 <b>疑似</b> OOM（OutOfMemoryError）的判断标志，
     *                   原始 {@code boolean}，不是概率也不是置信度，只表示
     *                   「本次推断倾向 OOM」。取值依据分两类：
     *                   <b>定性来源</b>（文件名约定、{@code hs_err} 伴随日志）
     *                   命中时置 {@code true}——注意 {@code hs_err} 分支
     *                   <b>无条件</b>置 {@code true}，即使提取到的签名是
     *                   {@code SIGSEGV} 等 native 崩溃而非内存溢出，
     *                   因此该值须与 {@link #detail} 中的实际签名对照阅读；
     *                   <b>定量来源</b>（堆水位）按存活保留字节分档：
     *                   小于 2G 置 {@code false}（「OOM 可能性低」），
     *                   大于等于 16G / 8G / 4G 三档依次置 {@code true}。
     *                   {@code detect} 还会在「定性为 OOM 但堆水位判定为
     *                   {@code false}」时，把所有非堆水位信号的本字段
     *                   <b>降级为 {@code false}</b>，即最终值以更具体的
     *                   堆水位证据为准。原始类型，恒为 {@code true} 或
     *                   {@code false}，无空值语义
     * @author CH
     * @since 4.0.0.42
     */
    public record CrashSignal(String kind,
                              String detail,
                              String evidence,
                              boolean oomLikely) {

        /**
         * OOM 信号的简写。
         *
         * @return kind 文本
         */
        public String label() {
            return oomLikely ? "疑似 OOM" : kind;
        }
    }

    /**
     * 构造方法，创建 Crash上下文 实例。
     */
    private CrashContext() {
    }

    /**
     * 对一个 hprof 文件做崩溃语境分析。
     *
     * @param hprofFile       hprof 文件
     * @param totalRetained   堆中存活总保留字节
     * @param totalLiveCount  存活对象数
     * @return 全部推断出的信号列表（可能为空）
     */
    public static List<CrashSignal> detect(File hprofFile,
                                           long totalRetained,
                                           long totalLiveCount) {
        List<CrashSignal> signals = new ArrayList<>();
        if (hprofFile != null) {
            signals.addAll(detectFromName(hprofFile));
            signals.addAll(detectCompanionLogs(hprofFile));
        }
        signals.add(heapWaterLevel(totalRetained, totalLiveCount));
        if (signals.isEmpty()) {
            return signals;
        }
        // 信号合并：文件名 / 伴随日志给出定性结论，堆水位给出定量结论。
        // 若"定性 OOM 信号"与"水位不足"矛盾（比如小堆场景用 java_error_in_
        // 命名），优先采信更具体的堆水位证据——此时 OOM 判定降级为"不确定"，
        // 避免把低水位的常规 dump 误判为 OOM 崩溃。
        boolean waterLevelHigh = signals.stream()
                .filter(s -> s.kind().equals("堆水位"))
                .anyMatch(CrashSignal::oomLikely);
        boolean namedOom = signals.stream()
                .filter(s -> !s.kind().equals("堆水位"))
                .anyMatch(CrashSignal::oomLikely);
        if (namedOom && !waterLevelHigh) {
            // 文件名声称 OOM 但堆水位并未打满 -> 全部 OOM 信号降级
            List<CrashSignal> downgraded = new ArrayList<>();
            for (CrashSignal s : signals) {
                if (s.oomLikely() && !s.kind().equals("堆水位")) {
                    downgraded.add(new CrashSignal(s.kind(), s.detail(),
                            s.evidence() + "（但堆水位未达 4G，OOM 判定存疑，需结合 -Xmx 实际值复核）",
                            false));
                } else {
                    downgraded.add(s);
                }
            }
            return downgraded;
        }
        return signals;
    }

    /**
     * 文件名约定推断。
     *
     * @param file hprof 文件
     * @return 命中的信号（0..1 条）
     */
    private static List<CrashSignal> detectFromName(File file) {
        List<CrashSignal> out = new ArrayList<>();
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("java_error_in_")) {
            out.add(new CrashSignal("文件名约定",
                    "IDE / JVM 在异常退出（通常 OOM 或 native crash）时导出的 dump",
                    "文件名 " + file.getName() + " 符合 java_error_in_* 约定",
                    true));
        } else if (name.endsWith("_oom.hprof") || name.contains("outofmemory")) {
            out.add(new CrashSignal("文件名约定",
                    "文件名显式标注 OOM",
                    file.getName(),
                    true));
        }
        return out;
    }

    /**
     * 在 hprof 所在目录寻找 hs_err / 同前缀日志并提取崩溃签名。
     *
     * @param file hprof 文件
     * @return 命中的信号（0..2 条）
     */
    private static List<CrashSignal> detectCompanionLogs(File file) {
        List<CrashSignal> out = new ArrayList<>();
        File dir = file.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return out;
        }
        String[] hsErr = scanHsErr(dir);
        if (hsErr != null) {
            out.add(new CrashSignal("hs_err 伴随日志",
                    hsErr[0], hsErr[1], true));
        }
        return out;
    }

    /**
     * 扫描目录中的 hs_err_pid*.log，提取第一处异常 / 信号签名。
     *
     * @param dir 目录
     * @return {@code [签名, 证据]}，未命中返回 null
     */
    private static String[] scanHsErr(File dir) {
        File[] logs = dir.listFiles((d, n) ->
                n.toLowerCase(java.util.Locale.ROOT).contains("hs_err")
                        && (n.endsWith(".log") || n.endsWith(".txt")));
        if (logs == null) {
            return null;
        }
        java.util.Arrays.sort(logs, java.util.Comparator.comparingLong(File::lastModified).reversed());
        for (File log : logs) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(log.toPath());
                StringBuilder sig = new StringBuilder();
                for (String line : lines) {
                    String t = line.trim();
                    if (t.isEmpty() || sig.length() > 400) {
                        continue;
                    }
                    if (t.startsWith("#")
                            || t.startsWith("Exception")
                            || t.startsWith("SIGSEGV")
                            || t.startsWith("SIGBUS")
                            || t.startsWith("Stack:")
                            || t.contains("OutOfMemoryError")) {
                        sig.append(t).append(" | ");
                    }
                }
                if (sig.length() > 0) {
                    String trimmed = sig.substring(0, Math.max(0, sig.length() - 2));
                    if (trimmed.length() > 400) {
                        trimmed = trimmed.substring(0, 400);
                    }
                    return new String[]{trimmed,
                            log.getName() + "（" + log.length() + "B）"};
                }
            } catch (java.io.IOException ignored) {
                // 日志读不到就跳过
            }
        }
        return null;
    }

    /**
     * 堆水位推断：总保留量相对常见 Xmx 的占比。
     *
     * @param totalRetained 总保留字节
     * @param totalLiveCount 存活对象数
     * @return 水位信号
     */
    private static CrashSignal heapWaterLevel(long totalRetained, long totalLiveCount) {
        long bytes = totalRetained;
        String sizeText = HprofObject.formatSize(bytes);
        if (bytes < 2L * 1024 * 1024 * 1024) {
            return new CrashSignal("堆水位",
                    "存活堆 " + sizeText + "，未达常见 Xmx 上限，OOM 可能性低",
                    "totalRetained=" + bytes, false);
        }
        long g4 = 4L * 1024 * 1024 * 1024;
        long g8 = 8L * 1024 * 1024 * 1024;
        long g16 = 16L * 1024 * 1024 * 1024;
        if (bytes >= g16) {
            return new CrashSignal("堆水位",
                    "存活堆 " + sizeText + " ≥ 16G，Xmx≥16G 的典型上限已打满，"
                            + "崩溃极可能是 OutOfMemoryError（Java heap space）",
                    "totalRetained=" + bytes + "（" + totalLiveCount + " 对象）", true);
        }
        if (bytes >= g8) {
            return new CrashSignal("堆水位",
                    "存活堆 " + sizeText + " ≥ 8G，已打满 8G 的常见 Xmx，"
                            + "强烈疑似 OOM",
                    "totalRetained=" + bytes, true);
        }
        if (bytes >= g4) {
            return new CrashSignal("堆水位",
                    "存活堆 " + sizeText + " ≥ 4G，4G Xmx 场景下堆已打满，"
                            + "疑似 OOM（需结合 -Xmx 确认）",
                    "totalRetained=" + bytes, true);
        }
        return new CrashSignal("堆水位",
                "存活堆 " + sizeText + "，水位中等，单快照无法判断是否 OOM",
                "totalRetained=" + bytes, false);
    }
}
