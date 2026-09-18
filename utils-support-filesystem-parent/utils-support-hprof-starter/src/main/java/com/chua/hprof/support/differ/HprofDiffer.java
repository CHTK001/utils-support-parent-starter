package com.chua.hprof.support.differ;

import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.parser.HprofParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 两份 hprof 转储对比器。
 *
 * <p>单份转储只能看到"此刻堆里有什么"，回答不了"什么在涨、为什么到崩溃点"。
 * 本工具对比两份转储（崩溃前 / 崩溃后，或任意两个时间点的
 * {@code heapdump001.hprof} / {@code heapdump002.hprof}），输出
 * <b>增长类排行</b>：哪些类的实例数 / 保留量增长最多。增长最快的类
 * 就是泄漏源，是"为什么最终 OOM 崩溃"最直接的证据。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * HprofDiffer.Diff diff = HprofDiffer.compare(
 *         HprofParser.parse(new File("before.hprof")),
 *         HprofParser.parse(new File("after.hprof")));
 * for (HprofDiffer.ClassGrowth g : diff.topGrowths(20)) {
 *     System.out.println(g.className() + " 增加 " + g.retainedDelta() + " bytes");
 * }
 * }</pre>
 *
 * <p>对比基于类名 join，对两边都存在的类计算
 * {@code after - before} 差值，负增长同样保留（可用于定位被正常回收的类）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofDiffer {

    /**
     * 构造方法，创建 HprofDiffer 实例。
     */
    private HprofDiffer() {
    }

    /**
    * 单个类的增长明细。
    *
    * @param className      类全名
    * @param beforeCount    前一份的实例数（无该类为 0）
    * @param afterCount     后一份的实例数（无该类为 0）
    * @param beforeRetained 前一份的保留字节
    * @param afterRetained  后一份的保留字节
    * @author CH
    * @since 4.0.0.42
    */
    public record ClassGrowth(String className,
                              long beforeCount,
                              long afterCount,
                              long beforeRetained,
                              long afterRetained) {

        /**
        * 实例数增量。
        *
        * @return afterCount - beforeCount
        */
        public long instanceDelta() {
            return afterCount - beforeCount;
        }

        /**
        * 保留字节增量。
        *
        * @return afterRetained - beforeRetained
        */
        public long retainedDelta() {
            return afterRetained - beforeRetained;
        }
    }

    /**
    * 两份转储的对比结果。
    *
    * @author CH
    * @since 4.0.0.42
    */
    public record Diff(List<ClassGrowth> byRetainedDelta,
                       List<ClassGrowth> byInstanceDelta,
                       long beforeRetainedTotal,
                       long afterRetainedTotal,
                       long beforeObjectCount,
                       long afterObjectCount) {

        /**
        * 按保留增量取 Top N。
        *
        * @param n 数量
        * @return 增长最多的前 N 个类
        */
        public List<ClassGrowth> topGrowths(int n) {
            return byRetainedDelta.subList(0, Math.min(n, byRetainedDelta.size()));
        }
    }

    /**
    * 对比两份解析结果。
    *
    * @param before 早一份（实例较少 / 时间较早）
    * @param after  晚一份
    * @return 对比结果
    */
    public static Diff compare(HprofParser.Result before, HprofParser.Result after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<String, HprofHistogramRow> b = index(before.histogram());
        Map<String, HprofHistogramRow> a = index(after.histogram());

        // union of class names
        Map<String, Long> keys = new java.util.TreeMap<>();
        b.keySet().forEach(k -> keys.put(k, 0L));
        a.keySet().forEach(k -> keys.put(k, 0L));

        List<ClassGrowth> all = new ArrayList<>(keys.size());
        for (String className : keys.keySet()) {
            HprofHistogramRow br = b.get(className);
            HprofHistogramRow ar = a.get(className);
            long bCount = br != null ? br.getInstanceCount() : 0L;
            long aCount = ar != null ? ar.getInstanceCount() : 0L;
            long bRetained = br != null ? br.getRetainedSize() : 0L;
            long aRetained = ar != null ? ar.getRetainedSize() : 0L;
            all.add(new ClassGrowth(className, bCount, aCount, bRetained, aRetained));
        }
        List<ClassGrowth> byRetained = new ArrayList<>(all);
        byRetained.sort((x, y) -> Long.compare(y.retainedDelta(), x.retainedDelta()));
        List<ClassGrowth> byInstance = new ArrayList<>(all);
        byInstance.sort((x, y) -> Long.compare(y.instanceDelta(), x.instanceDelta()));

        long beforeTotal = before.totalRetainedBytes();
        long afterTotal = after.totalRetainedBytes();
        return new Diff(byRetained, byInstance,
                beforeTotal, afterTotal,
                before.totalObjectCount(), after.totalObjectCount());
    }

    /**
    * 对比两个 hprof 文件（内部各自解析一次）。
    *
    * @param before 早一份文件
    * @param after  晚一份文件
    * @return 对比结果
    * @throws IOException 任一文件无法解析
    */
    public static Diff compareFiles(File before, File after) throws IOException {
        return compare(HprofParser.parse(before), HprofParser.parse(after));
    }

    /**
    * 把直方图索引为 类名 -> 行。
    *
    * @param rows 直方图行
    * @return 索引
    */
    private static Map<String, HprofHistogramRow> index(List<HprofHistogramRow> rows) {
        Map<String, HprofHistogramRow> map = new java.util.HashMap<>();
        for (HprofHistogramRow row : rows) {
            if (row.getClassName() != null) {
                map.put(row.getClassName(), row);
            }
        }
        return map;
    }
}
