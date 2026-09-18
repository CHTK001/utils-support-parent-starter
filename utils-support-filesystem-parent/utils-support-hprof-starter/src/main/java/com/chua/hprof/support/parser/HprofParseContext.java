package com.chua.hprof.support.parser;

import com.chua.hprof.support.model.HprofClassDetail;
import org.netbeans.lib.profiler.heap.Field;
import org.netbeans.lib.profiler.heap.FieldValue;
import org.netbeans.lib.profiler.heap.GCRoot;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.HeapFactory;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.Type;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 GridKit hprof-heap 的 HPROF 二进制读取器，供 {@link HprofParser} 使用。
 *
 * <p>通过 {@code org.gridkit.jvmtool:hprof-heap}（Eclipse MAT 所使用的
 * NetBeans PerfLib HPROF 后端）解析 HPROF 堆转储，
 * 并落地为 {@link HprofRecord} 行。
 * 类直方图由 {@code JavaClass.getInstancesCount()} 与
 * {@code getRetainedSizeByClass()} 聚合；GC 根从
 * {@code HprofHeap.getGCRoots()} 收集。</p>
 *
 * <p>HAHA / Shark 只支持 32 位的 Android 转储，而 hprof-heap 能正确解析
 * 64 位 JDK 堆转储（8 字节记录长度与 8 字节标识符），
 * 因此 JDK 25 崩溃转储中 790MB 的
 * {@code java_error_in_idea.hprof} 也能被正确解析。</p>
 *
 * <p>转储较大时，调用方应把解析放到工作线程中执行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofParseContext {

    /**
    * 已解析的记录行，不可变。
    */
    public record HprofRecord(String className,
                              long instanceCount,
                              long shallowSize,
                              long retainedSize,
                              long objectId,
                              String gcRoot,
                              String refChain) {
    }

    /**
    * 解析上下文载体，不可变。
    */
    public record ParsedContext(List<HprofRecord> objects,
                                Map<String, Long> retainedByClass,
                                Map<String, Long> countByClass,
                                List<String> gcRoots,
                                Map<String, Long> gcRootsByKind,
                                Map<String, HprofClassDetail> classDetails,
                                List<HprofRefChainWalker.RefChain> refChains) {
    }

    /**
     * 构造方法，创建 Hprof解析上下文 实例。
     */
    private HprofParseContext() {
    }

    /**
    * 采集实例级明细的 Top 类数量上限。
    */
    private static final int DETAIL_CLASS_LIMIT = 20;

    /**
    * 每个类采集的 Top 实例数量上限。
    */
    private static final int DETAIL_INSTANCE_LIMIT = 5;

    /**
    * 每个实例采集的字段值数量上限。
    */
    private static final int DETAIL_FIELD_LIMIT = 20;

    /**
    * 为挑出保留量最大的实例，每个类最多扫描的实例数。
    * 即使某个类有百万级实例，也能把明细遍历控制在有限开销内。
    */
    private static final int DETAIL_SCAN_LIMIT = 5000;

    /**
    * 解析 hprof 文件为上下文。
    *
    * @param file hprof 二进制文件
    * @return 解析上下文
    * @throws IOException 文件不可读时抛出
    */
    public static ParsedContext parse(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.exists()) {
            throw new IOException("hprof file not found: " + file);
        }
        Heap heap = HeapFactory.createHeap(file);
        return parse(heap);
    }

    /**
    * 解析 hprof 原始字节为上下文。
    *
    * <p>字节会被写入临时文件再交给 GridKit 读取器，因为 hprof-heap 只接受
    * {@link File} 输入。当系统临时空间不足以放下读取器所需的 long-map
    * 暂存数据时，本方法预期会失败——需要在磁盘受限的机器上解析 byte[] 的调用方，
    * 应先把文件落到剩余空间充足的目录下。</p>
    *
    * @param data hprof 字节
    * @return 解析上下文
    * @throws IOException 字节无法解析时抛出
    */
    public static ParsedContext parse(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        File temp = File.createTempFile("hprof-", ".hprof");
        temp.deleteOnExit();
        Files.write(temp.toPath(), data);
        Heap heap = HeapFactory.createHeap(temp);
        return parse(heap);
    }

    /**
    * 从已打开的 GridKit 堆读取器聚合类直方图与 GC 根。
    *
    * @param heap 已打开的 GridKit 堆读取器
    * @return 解析上下文
    */
    private static ParsedContext parse(Heap heap) {
        List<HprofRecord> objects = new ArrayList<>();
        Map<String, Long> retainedByClass = new HashMap<>();
        Map<String, Long> countByClass = new HashMap<>();

        List<JavaClass> classes = heap.getAllClasses();
        for (JavaClass cls : classes) {
            String name = cls.getName();
            if (name == null) {
                name = "class#" + cls.getJavaClassId();
            }
            long instances = cls.getInstancesCount();
            long retained = cls.getRetainedSizeByClass();
            // allInstancesSize 是该类所有实例浅堆大小之和；
            // getInstanceSize() 对数组类不可靠（大小随元素而变），
            // 因此优先采用总量值。
            long shallow = Math.max(cls.getAllInstancesSize(), 0L);
            objects.add(new HprofRecord(name, instances, shallow, retained,
                    cls.getJavaClassId(), null, null));
            countByClass.put(name, instances);
            retainedByClass.put(name, retained);
        }

        List<String> gcRoots = collectGcRoots(heap);
        Map<String, Long> gcRootsByKind = countGcRootsByKind(heap);
        Map<String, HprofClassDetail> classDetails =
                collectClassDetails(heap, objects);
        List<HprofRefChainWalker.RefChain> refChains =
                HprofRefChainWalker.walk(heap, objects);
        return new ParsedContext(objects, retainedByClass, countByClass,
                gcRoots, gcRootsByKind, classDetails, refChains);
    }

    /**
    * 针对保留量最大的那些类，采集其保留量最大实例的字段值，
    * 以及静态字段持有者。报告的“点击展开明细”视图正是基于这部分数据——
    * 它回答的是"谁持有了什么"，而不只是"占了多少"。
    *
    * @param heap    已打开的 GridKit 堆读取器
    * @param objects 按类聚合的记录
    * @return 类名到明细的映射
    */
    private static Map<String, HprofClassDetail> collectClassDetails(Heap heap,
                                                                      List<HprofRecord> objects) {
        Map<String, HprofClassDetail> details = new HashMap<>();
        List<HprofRecord> top = new ArrayList<>(objects);
        top.sort((a, b) -> Long.compare(b.retainedSize(), a.retainedSize()));
        int captured = 0;
        for (HprofRecord record : top) {
            if (captured >= DETAIL_CLASS_LIMIT) {
                break;
            }
            if (record.retainedSize() <= 0) {
                continue;
            }
            try {
                HprofClassDetail detail = buildClassDetail(heap, record);
                if (!detail.getInstances().isEmpty() || !detail.getStaticFields().isEmpty()) {
                    details.put(record.className(), detail);
                    captured++;
                }
            } catch (Throwable t) {
                // 部分类（如数组类、JDK 内部类）遍历实例时会抛异常；
                // 跳过它们，而不是让整份报告中断
            }
        }
        return details;
    }

    /**
    * 构建单个类的明细：Top 实例 + 静态字段。
    *
    * <p>会扫描该类的所有实例，保留保留量最大的
    * {@link #DETAIL_INSTANCE_LIMIT} 个。对多数类而言全量扫描开销很小
    * （数万个实例只需几毫秒），而且这是找到真正最大持有者的唯一办法——
    * 顺序取"前 N 个"对于大集合只会拿到一批很小的早期实例。</p>
    *
    * @param heap   已打开的 GridKit 堆读取器
    * @param record 该类的按类记录
    * @return 类明细
    */
    private static HprofClassDetail buildClassDetail(Heap heap, HprofRecord record) {
        JavaClass cls = heap.getJavaClassByID(record.objectId());
        if (cls == null) {
            return new HprofClassDetail(record.className(), List.of(), List.of());
        }
        // 保留最大的前 5 个实例（用固定容量堆式选择，避免排序整个列表）。
        // 用 JavaClass.getInstances()（LazyInstanceList，逐实例惰性生成）而非
        // Heap.getAllInstances(classId)——后者在 GridKit 里会忽略 classId 参数
        // 退化成全堆迭代器，导致每个类抓到的是同一批全局最大实例。
        List<HprofClassDetail.InstanceDetail> instances = new ArrayList<>();
        int scanned = 0;
        for (Instance inst : cls.getInstances()) {
            scanned++;
            if (scanned >= DETAIL_SCAN_LIMIT) {
                break;
            }
            long retained = inst.getRetainedSize();
            if (retained <= 0) {
                continue;
            }
            HprofClassDetail.InstanceDetail detail = instanceDetail(inst);
            if (instances.size() < DETAIL_INSTANCE_LIMIT) {
                instances.add(detail);
            } else {
                int minIdx = 0;
                for (int i = 1; i < instances.size(); i++) {
                    if (instances.get(i).getRetainedSize()
                            < instances.get(minIdx).getRetainedSize()) {
                        minIdx = i;
                    }
                }
                if (retained > instances.get(minIdx).getRetainedSize()) {
                    instances.set(minIdx, detail);
                }
            }
        }
        if (instances.isEmpty()) {
            return new HprofClassDetail(record.className(), List.of(), List.of());
        }
        instances.sort((a, b) -> Long.compare(b.getRetainedSize(), a.getRetainedSize()));
        return new HprofClassDetail(record.className(), instances, List.of());
    }

    /**
    * 构造单个实例明细。
    *
    * @param inst 实例
    * @return 实例明细
    */
    private static HprofClassDetail.InstanceDetail instanceDetail(Instance inst) {
        List<HprofClassDetail.FieldValueDetail> fieldValues = new ArrayList<>();
        for (FieldValue fv : inst.getFieldValues()) {
            if (fieldValues.size() >= DETAIL_FIELD_LIMIT) {
                break;
            }
            fieldValues.add(toFieldValueDetail(fv, false));
        }
        return new HprofClassDetail.InstanceDetail(
                inst.getInstanceId(), inst.getRetainedSize(), inst.getSize(), fieldValues);
    }

    /**
    * 把 GridKit 的 FieldValue 转换为可读明细。
    *
    * @param fv       字段值
    * @param isStatic 该字段是否为静态字段
    * @return 字段明细
    */
    static HprofClassDetail.FieldValueDetail toFieldValueDetail(FieldValue fv,
                                                                 boolean isStatic) {
        Field field = fv.getField();
        String fieldName = field != null ? field.getName() : "?";
        Type type = field != null ? field.getType() : null;
        String typeName = type != null && type.getName() != null
                ? type.getName() : "unknown";
        String valueText = readableValue(fv.getValue(), typeName);
        return new HprofClassDetail.FieldValueDetail(
                fieldName, isStatic, typeName, valueText);
    }

    /**
    * 把原始字段值渲染为简短可读的字符串。
    *
    * @param rawValue GridKit 给出的原始值文本（可能是对象 id 或字符串字面量）
    * @param typeName 目标类型名
    * @return 可读文本
    */
    private static String readableValue(String rawValue, String typeName) {
        if (rawValue == null) {
            return "null";
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return "null";
        }
        // 数组类型：值文本即元素个数
        if (typeName.endsWith("[]")) {
            return typeName + "[" + value + "]";
        }
        // 数值 / 基本类型：原样展示
        if (typeName.startsWith("int") || typeName.startsWith("long")
                || typeName.startsWith("boolean") || typeName.startsWith("char")) {
            return value;
        }
        // 引用类型：GridKit 返回的是目标对象 id
        return typeName + "@" + value;
    }

    /**
    * 按种类统计 GC 根数量。
    *
    * @param heap 已打开的 GridKit 堆读取器
    * @return 种类到数量的映射
    */
    private static Map<String, Long> countGcRootsByKind(Heap heap) {
        Map<String, Long> byKind = new HashMap<>();
        for (GCRoot root : heap.getGCRoots()) {
            byKind.merge(root.getKind(), 1L, Long::sum);
        }
        return byKind;
    }

    /**
    * 收集去重后的 GC 根种类 + 类描述。
    *
    * @param heap 已打开的 GridKit 堆读取器
    * @return GC 根描述列表
    */
    private static List<String> collectGcRoots(Heap heap) {
        Set<String> seen = new LinkedHashSet<>();
        for (GCRoot root : heap.getGCRoots()) {
            Instance inst = root.getInstance();
            String kind = root.getKind();
            if (inst != null) {
                seen.add(kind + ":" + inst.getJavaClass().getName());
            } else {
                seen.add(kind);
            }
        }
        return new ArrayList<>(seen);
    }
}
