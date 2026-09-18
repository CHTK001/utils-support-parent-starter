package com.chua.hprof.support.parser;

import org.netbeans.lib.profiler.heap.GCRoot;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.HeapFactory;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;

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
                                Map<String, Long> gcRootsByKind) {
    }

    private HprofParseContext() {
    }

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
    * reader, because hprof-heap only accepts {@link File} input.</p>
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
        return new ParsedContext(objects, retainedByClass, countByClass, gcRoots, gcRootsByKind);
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
