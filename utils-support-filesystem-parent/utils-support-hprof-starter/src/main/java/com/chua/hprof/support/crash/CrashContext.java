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
