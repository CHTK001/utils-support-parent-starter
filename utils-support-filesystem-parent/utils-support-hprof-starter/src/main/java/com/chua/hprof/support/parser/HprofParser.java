package com.chua.hprof.support.parser;

import com.chua.hprof.support.model.HprofClassDetail;
import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unified entry point for hprof binary parsing.
 *
 * <p>Wraps the GridKit hprof-heap
 * ({@code org.gridkit.jvmtool:hprof-heap}, the NetBeans PerfLib HPROF
 * backend that backs Eclipse MAT) to expose Java-structured data: object
 * instances, per-class histograms and top retained objects. The output of
 * {@link #parse(File)} / {@link #parse(InputStream)} is the intermediate
 * "Java object / structured data" stage in the hprof to JSON / Markdown to
 * AI analysis chain.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HprofParser {

    /**
    * Parsed result holder, immutable.
    */
    public record Result(List<HprofObject> objects,
                          List<HprofHistogramRow> histogram,
                          List<HprofObject> topRetained,
                          List<String> gcRoots,
                          Map<String, Long> gcRootsByKind,
                          Map<String, HprofClassDetail> classDetails,
                          long totalRetainedBytes,
                          long totalObjectCount) {
    }

    private HprofParser() {
    }

    /**
    * Parse an hprof file from disk.
    *
    * @param file hprof binary file
    * @return parsed result
    * @throws IOException when the file cannot be read
    */
    public static Result parse(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.exists()) {
            throw new IllegalArgumentException("hprof file not found: " + file);
        }
        HprofParseContext.ParsedContext ctx = HprofParseContext.parse(file);
        return fromContext(ctx);
    }

    /**
    * Parse an hprof stream.
    *
    * @param is hprof binary stream
    * @return parsed result
    * @throws IOException when the stream cannot be read
    */
    public static Result parse(InputStream is) throws IOException {
        Objects.requireNonNull(is, "input stream");
        byte[] data = is.readAllBytes();
        HprofParseContext.ParsedContext ctx = HprofParseContext.parse(data);
        return fromContext(ctx);
    }

    /**
    * Parse an hprof stream, optionally with a file name for reports.
    *
    * @param is       hprof binary stream
    * @param fileName file name (nullable), used in markdown headers
    * @return parsed result
    * @throws IOException when the stream cannot be read
    */
    public static Result parse(InputStream is, String fileName) throws IOException {
        Objects.requireNonNull(is, "input stream");
        byte[] data = is.readAllBytes();
        HprofParseContext.ParsedContext ctx = HprofParseContext.parse(data);
        return fromContext(ctx);
    }

    /**
    * Parse raw hprof bytes.
    *
    * @param data raw hprof bytes
    * @return parsed result
    * @throws IOException when the bytes cannot be parsed
    */
    public static Result parseBytes(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        HprofParseContext.ParsedContext ctx = HprofParseContext.parse(data);
        return fromContext(ctx);
    }

    /**
    * Build a Result from a parsed context.
    *
    * @param ctx parsed context
    * @return the result
    */
    private static Result fromContext(HprofParseContext.ParsedContext ctx) {
        List<HprofObject> objects = new ArrayList<>(ctx.objects().size());
        for (HprofParseContext.HprofRecord record : ctx.objects()) {
            objects.add(toObject(record));
        }
        List<HprofHistogramRow> histogram = buildHistogram(ctx);
        List<HprofObject> topRetained = objects.stream()
                .sorted(Comparator.comparingLong(HprofObject::getRetainedSize).reversed())
                .limit(50)
                .toList();
        long totalRetained = ctx.retainedByClass().values().stream().mapToLong(Long::longValue).sum();
        long totalCount = ctx.countByClass().values().stream().mapToLong(Long::longValue).sum();
        Map<String, Long> gcRootsByKind = ctx.gcRootsByKind();
        Map<String, HprofClassDetail> classDetails = ctx.classDetails();
        return new Result(objects, histogram, topRetained, ctx.gcRoots(),
                gcRootsByKind, classDetails, totalRetained, totalCount);
    }

    /**
    * Convert a parsed record to the model object.
    *
    * @param record parsed record
    * @return model object
    */
    private static HprofObject toObject(HprofParseContext.HprofRecord record) {
        HprofObject obj = new HprofObject(
                record.className(),
                record.instanceCount(),
                record.shallowSize(),
                record.retainedSize());
        obj.setObjectId(record.objectId());
        obj.setGcRoot(record.gcRoot());
        obj.setRefChain(record.refChain());
        return obj;
    }

    /**
    * Build per-class histogram rows from the parsed context.
    *
    * <p>The context already carries per-class shallow sizes (computed as
    * {@code instanceSize * instances}) so the histogram is assembled in
    * one pass without scanning the object list.</p>
    *
    * @param ctx parsed context
    * @return histogram rows sorted by retained size descending
    */
    private static List<HprofHistogramRow> buildHistogram(HprofParseContext.ParsedContext ctx) {
        Map<String, long[]> aggregate = new HashMap<>();
        for (Map.Entry<String, Long> e : ctx.retainedByClass().entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            long[] acc = aggregate.computeIfAbsent(e.getKey(), k -> new long[2]);
            acc[1] = e.getValue();
        }
        for (Map.Entry<String, Long> e : ctx.countByClass().entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            long[] acc = aggregate.computeIfAbsent(e.getKey(), k -> new long[2]);
            acc[0] = e.getValue();
        }
        // shallow size per class: recompute from the object list (one pass)
        Map<String, Long> shallowByClass = new HashMap<>();
        for (HprofParseContext.HprofRecord record : ctx.objects()) {
            if (record.className() == null) {
                continue;
            }
            shallowByClass.merge(record.className(), record.shallowSize(), Long::sum);
        }
        List<HprofHistogramRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : aggregate.entrySet()) {
            String className = e.getKey();
            long count = e.getValue()[0];
            long retained = e.getValue()[1];
            long shallow = shallowByClass.getOrDefault(className, 0L);
            rows.add(new HprofHistogramRow(className, count, shallow, retained));
        }
        rows.sort(Comparator.comparingLong(HprofHistogramRow::getRetainedSize).reversed());
        return rows;
    }
}
