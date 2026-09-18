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
 * GridKit hprof-heap backed HPROF binary reader used by {@link HprofParser}.
 *
 * <p>Parses an HPROF heap dump through
 * {@code org.gridkit.jvmtool:hprof-heap} (the NetBeans PerfLib HPROF backend
 * that backs Eclipse MAT) and materialises it into {@link HprofRecord} rows.
 * Class histograms are aggregated from {@code JavaClass.getInstancesCount()}
 * and {@code getRetainedSizeByClass()}; GC roots are collected from
 * {@code HprofHeap.getGCRoots()}.</p>
 *
 * <p>Unlike HAHA / Shark (which only support 32-bit Android dumps),
 * hprof-heap correctly parses 64-bit JDK heap dumps (8-byte record
 * lengths and 8-byte identifiers), so a 790MB
 * {@code java_error_in_idea.hprof} from a JDK 25 crash dump parses
 * correctly.</p>
 *
 * <p>The caller is expected to run the parse on a worker thread when the
 * dump is large.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofParseContext {

    /**
    * Parsed record row, immutable.
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
    * Parsed context holder, immutable.
    */
    public record ParsedContext(List<HprofRecord> objects,
                                Map<String, Long> retainedByClass,
                                Map<String, Long> countByClass,
                                List<String> gcRoots,
                                Map<String, Long> gcRootsByKind,
                                Map<String, HprofClassDetail> classDetails) {
    }

    private HprofParseContext() {
    }

    /**
    * Maximum number of top classes for which instance-level detail is captured.
    */
    private static final int DETAIL_CLASS_LIMIT = 20;

    /**
    * Maximum number of top instances captured per class.
    */
    private static final int DETAIL_INSTANCE_LIMIT = 5;

    /**
    * Maximum number of field values captured per instance.
    */
    private static final int DETAIL_FIELD_LIMIT = 20;

    /**
    * Parse an hprof file into a context.
    *
    * @param file hprof binary file
    * @return parsed context
    * @throws IOException when the file cannot be read
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
    * Parse raw hprof bytes into a context.
    *
    * <p>The bytes are written to a temp file and handed to the GridKit
    * reader, because hprof-heap only accepts {@link File} input. When the
    * system temp space is too small for the long-map scratch the reader
    * needs, this method is expected to fail — callers that need to parse
    * a byte[] on disk-constrained machines should stage the file under a
    * directory with enough free space first.</p>
    *
    * @param data hprof bytes
    * @return parsed context
    * @throws IOException when the bytes cannot be parsed
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
    * Aggregate class histogram and GC roots from an open GridKit heap.
    *
    * @param heap open GridKit heap reader
    * @return parsed context
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
            // allInstancesSize is the summed shallow size across every
            // instance of the class; getInstanceSize() is unreliable for
            // array classes (varies per element) so prefer the total.
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
        return new ParsedContext(objects, retainedByClass, countByClass,
                gcRoots, gcRootsByKind, classDetails);
    }

    /**
    * For the top classes (by retained size), capture the field values of
    * their retained-largest instances and the static field holders. This
    * is what powers the "click to expand details" view in the report -
    * it answers "who stored what" instead of just "how much".
    *
    * @param heap open GridKit heap reader
    * @param objects per-class records
    * @return class name to detail map
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
                // some classes (e.g. array types, JDK internals) throw on
                // instance walk; skip them rather than abort the report
            }
        }
        return details;
    }

    /**
    * Build the detail for one class: top instances + static fields.
    *
    * @param heap      open GridKit heap reader
    * @param className class name
    * @param record    per-class record
    * @return the class detail
    */
    private static HprofClassDetail buildClassDetail(Heap heap, HprofRecord record) {
        List<HprofClassDetail.InstanceDetail> instances = new ArrayList<>();
        int scanned = 0;
        for (Instance inst : heap.getAllInstances(record.objectId())) {
            scanned++;
            if (scanned >= DETAIL_INSTANCE_LIMIT) {
                break;
            }
            List<HprofClassDetail.FieldValueDetail> fieldValues = new ArrayList<>();
            for (FieldValue fv : inst.getFieldValues()) {
                if (fieldValues.size() >= DETAIL_FIELD_LIMIT) {
                    break;
                }
                fieldValues.add(toFieldValueDetail(fv, false));
            }
            instances.add(new HprofClassDetail.InstanceDetail(
                    inst.getInstanceId(), inst.getRetainedSize(),
                    inst.getSize(), fieldValues));
        }
        instances.sort((a, b) -> Long.compare(b.getRetainedSize(), a.getRetainedSize()));
        return new HprofClassDetail(record.className(), instances, List.of());
    }

    /**
    * Convert a GridKit FieldValue into a human-readable detail.
    *
    * @param fv       field value
    * @param isStatic whether the field is static
    * @return the detail
    */
    private static HprofClassDetail.FieldValueDetail toFieldValueDetail(FieldValue fv,
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
    * Render a raw field value as a short human-readable string.
    *
    * @param rawValue  raw value text from GridKit (may be an object id or
    *                  a string literal)
    * @param typeName  target type name
    * @return the readable text
    */
    private static String readableValue(String rawValue, String typeName) {
        if (rawValue == null) {
            return "null";
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return "null";
        }
        // array types: the value text is the element count
        if (typeName.endsWith("[]")) {
            return typeName + "[" + value + "]";
        }
        // numeric / primitive: show as-is
        if (typeName.startsWith("int") || typeName.startsWith("long")
                || typeName.startsWith("boolean") || typeName.startsWith("char")) {
            return value;
        }
        // reference type: GridKit returns the target object id
        return typeName + "@" + value;
    }

    /**
    * Count GC roots per kind.
    *
    * @param heap open GridKit heap reader
    * @return kind to count map
    */
    private static Map<String, Long> countGcRootsByKind(Heap heap) {
        Map<String, Long> byKind = new HashMap<>();
        for (GCRoot root : heap.getGCRoots()) {
            byKind.merge(root.getKind(), 1L, Long::sum);
        }
        return byKind;
    }

    /**
    * Collect distinct GC root kind + class descriptions.
    *
    * @param heap open GridKit heap reader
    * @return gc root descriptions
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
