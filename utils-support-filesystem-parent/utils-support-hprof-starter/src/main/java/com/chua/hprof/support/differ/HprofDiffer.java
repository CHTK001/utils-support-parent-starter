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
     * <p>由 {@link HprofDiffer#compare} 一次产出，包含两类视角：两个按类增长排行
     * （{@link #byRetainedDelta} 按保留字节增量、{@link #byInstanceDelta} 按实例数增量
     * 降序），以及两份转储各自的总体量（{@link #beforeRetainedTotal} /
     * {@link #afterRetainedTotal} 与 {@link #beforeObjectCount} /
     * {@link #afterObjectCount}），便于先看整体涨跌、再下钻到具体类。</p>
     *
     * <p>可空性：两个排行列表在紧凑构造器里已 {@code requireNonNull} 并用
     * {@link List#copyOf(List)} 快照，访问器<b>保证非 null</b>，且元素不允许为
     * {@code null}。四个 {@code long} 分量均为原始类型，不存在空值语义。
     * 两个排行覆盖的是两份直方图<b>类名并集</b>的每一个类，因此两侧列表元素个数相同、
     * 仅排序不同；负增量（类被正常回收）同样保留在列表中，不做过滤。</p>
     *
     * @param byRetainedDelta 按 保留字节增量 降序排列的类增长明细，取值范围为两份转储
     *                        直方图的类名并集，每个类各一条 {@link ClassGrowth}。
     *                        排序键为 {@code afterRetained - beforeRetained}（单位字节），
     *                        增量为负的类排在后面。取自 {@code compare} 中按
     *                        {@code ClassGrowth#retainedDelta()} 降序排序的副本，
     *                        已做不可变快照，不允许为 {@code null}；
     *                        {@link #topGrowths(int)} 即取本列表的前若干项
     * @param byInstanceDelta 按 实例数增量 降序排列的类增长明细，类名并集与
     *                        {@link #byRetainedDelta} 完全相同，元素也是同一批
     *                        {@link ClassGrowth} 对象，区别仅在排序键为
     *                        {@code afterCount - beforeCount}（单位「个实例」）。
     *                        排在首位者即实例数膨胀最严重的类。
     *                        已做不可变快照，不允许为 {@code null}
     * @param beforeRetainedTotal 前一份（较早）转储的 全堆保留字节总量，单位字节，
     *                             取自 {@code before.totalRetainedBytes()}，即解析时
     *                             对各类的 {@code retainedSize} 求和得到的近似值
     *                             （按类累加，非按对象去重的精确 retained size）。
     *                             原始 {@code long}，非负，无数据时为 0；
     *                             与 {@link #afterRetainedTotal} 相减即得全堆增长量，
     *                             可用来判断单类排行是否足以解释整体增长
     * @param afterRetainedTotal 后一份（较晚）转储的 全堆保留字节总量，单位字节，
     *                            取自 {@code after.totalRetainedBytes()}，口径与
     *                            {@link #beforeRetainedTotal} 相同，两者必须来自同一套
     *                            解析实现才可比较。原始 {@code long}，非负
     * @param beforeObjectCount 前一份（较早）转储的 堆内对象实例总数，单位「个」，
     *                          取自 {@code before.totalObjectCount()}，即解析时对各类的
     *                          {@code instanceCount} 求和。原始 {@code long}，非负，
     *                          无数据时为 0
     * @param afterObjectCount 后一份（较晚）转储的 堆内对象实例总数，单位「个」，
     *                         取自 {@code after.totalObjectCount()}，口径与
     *                         {@link #beforeObjectCount} 相同。原始 {@code long}，非负。
     *                         与 {@link #beforeObjectCount} 相减可交叉验证实例增长
     *                         究竟由大对象数组还是小对象海量堆积造成
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
         * 规范构造器：对两个增长排行列表做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变；排行元素均为非空记录，
         * 因此使用拒绝 null 元素的 {@link List#copyOf(List)}。
         * {@link #topGrowths(int)} 基于不可变列表取子视图，行为不变。</p>
         */
        public Diff {
            byRetainedDelta = List.copyOf(Objects.requireNonNull(byRetainedDelta, "byRetainedDelta 不能为 null"));
            byInstanceDelta = List.copyOf(Objects.requireNonNull(byInstanceDelta, "byInstanceDelta 不能为 null"));
        }

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
