package com.chua.hprof.support.parser;

import com.chua.hprof.support.model.HprofClassDetail;
import org.netbeans.lib.profiler.heap.FieldValue;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.netbeans.lib.profiler.heap.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 保留量 Top 类的三层引用链遍历器。
 *
 * <p>GridKit 的 {@code getGCRoot(Instance)} 依赖包私有的支配者分析，
 * 在多数转储中返回 null，因此无法直接遍历入边（谁指向我）。
 * 作为替代，本遍历器针对每个 Top 类的保留量最大实例，
 * 只使用公开 API 构造出可读的三层"谁持有什么"引用链：</p>
 *
 * <ol>
 *   <li><b>第 1 层 - 持有者实例</b>：该类自身的保留量最大实例
 *   （已由 {@link HprofClassDetail} 采集）。</li>
 *   <li><b>第 2 层 - 它持有什么</b>：该实例的出边
 *   {@code getReferences()}，按子对象的保留量排序——即
 *   "这个对象通过其最大的几个子对象让 N MB 内存无法回收"。</li>
 *   <li><b>第 3 层 - 子对象指向哪里</b>：对排名靠前的子引用，
 *   给出目标类名 + 保留量，例如
 *   {@code ConcurrentHashMap(58MB) -> ConcurrentHashMap$Node[](58MB)}。</li>
 * </ol>
 *
 * <p>三层合起来自底向上可读作：
 * <code>GC 根类型（取自 HprofParseContext.gcRoots）&larr; 持有者
 * 类 &larr; 被持有的子对象</code>，足以让非专业读者看出
 * "哪个对象的哪个字段钉住了多少兆内存"。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofRefChainWalker {

    /**
    * 每个持有者实例最多排序保留的子引用数量上限。
    */
    private static final int CHILD_LIMIT = 5;

    /**
    * 最多遍历的类数量上限。
    */
    private static final int CLASS_LIMIT = 10;

    /**
    * 定位保留量最大实例时，每个类最多扫描的实例数。
    */
    private static final int SCAN_LIMIT = 5000;

    /**
     * 构造方法，创建 HprofRefChainWalker 实例。
     */
    private HprofRefChainWalker() {
    }

    /**
    * 引用链中的一个子引用。
    *
    * @param className  目标类
    * @param retained   目标实例的保留字节数
    * @param instanceId 目标实例 id
    * @author CH
    * @since 4.0.0.42
    * @return 结果值
    */
    public record ChildRef(String className, long retained, long instanceId) {
    }

    /**
    * 一条遍历出的引用链：持有者实例 + 排序后的子引用。
    *
    * @param holderClass    正在遍历的类名
    * @param holderId       保留量最大实例的 id
    * @param holderRetained 持有者实例的保留字节数
    * @param fields         持有者自身的字段值（名称/类型/可读值）
    * @param children       排序后的出边引用
    * @author CH
    * @since 4.0.0.42
    */
    public record RefChain(String holderClass,
                           long holderId,
                           long holderRetained,
                           List<HprofClassDetail.FieldValueDetail> fields,
                           List<ChildRef> children) {
    }

    /**
    * 遍历保留量 Top 类的引用链。
    *
    * @param heap    已打开的 GridKit 堆
    * @param objects 按类聚合的记录（用于排序）
    * @return 每个存在保留量最大实例的类对应一条 {@link RefChain}
    */
    public static List<RefChain> walk(Heap heap, List<HprofParseContext.HprofRecord> objects) {
        List<RefChain> chains = new ArrayList<>();
        List<HprofParseContext.HprofRecord> top = new ArrayList<>(objects);
        top.sort((a, b) -> Long.compare(b.retainedSize(), a.retainedSize()));
        int done = 0;
        for (HprofParseContext.HprofRecord record : top) {
            if (done >= CLASS_LIMIT) {
                break;
            }
            if (record.retainedSize() <= 0) {
                continue;
            }
            RefChain chain = walkOne(heap, record);
            if (chain != null) {
                chains.add(chain);
                done++;
            }
        }
        return chains;
    }

    /**
    * 遍历单个类：定位其保留量最大的实例，并对子引用排序。
    *
    * @param heap   已打开的 GridKit 堆
    * @param record 该类的按类记录
    * @return 引用链；没有可用实例时为 null
    */
    private static RefChain walkOne(Heap heap, HprofParseContext.HprofRecord record) {
        JavaClass cls = heap.getJavaClassByID(record.objectId());
        if (cls == null) {
            return null;
        }
        // 在有限次扫描内定位保留量最大的实例
        Instance best = null;
        int scanned = 0;
        for (Instance inst : cls.getInstances()) {
            scanned++;
            if (scanned >= SCAN_LIMIT) {
                break;
            }
            if (best == null || inst.getRetainedSize() > best.getRetainedSize()) {
                best = inst;
            }
        }
        if (best == null) {
            return null;
        }

        List<HprofClassDetail.FieldValueDetail> fields = new ArrayList<>();
        for (FieldValue fv : best.getFieldValues()) {
            fields.add(HprofParseContext.toFieldValueDetail(fv, false));
        }

        List<ChildRef> children = rankChildren(best);
        return new RefChain(record.className(), best.getInstanceId(),
                best.getRetainedSize(), fields, children);
    }

    /**
    * 按子实例保留量对某个实例的出边引用排序。
    *
    * @param inst 持有者实例
    * @return 排序后的子引用（无对象引用时为空）
    */
    private static List<ChildRef> rankChildren(Instance inst) {
        List<ChildRef> children = new ArrayList<>();
        for (Value value : inst.getReferences()) {
            if (value instanceof ObjectFieldValue objectFieldValue) {
                Instance child = objectFieldValue.getInstance();
                if (child != null) {
                    children.add(new ChildRef(
                            child.getJavaClass().getName(),
                            child.getRetainedSize(),
                            child.getInstanceId()));
                }
            }
            if (children.size() >= CHILD_LIMIT * 4) {
                break;
            }
        }
        children.sort((a, b) -> Long.compare(b.retained(), a.retained()));
        return children.size() > CHILD_LIMIT
                ? new ArrayList<>(children.subList(0, CHILD_LIMIT))
                : children;
    }
}
