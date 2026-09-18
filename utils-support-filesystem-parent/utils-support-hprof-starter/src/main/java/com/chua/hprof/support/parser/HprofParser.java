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
 * hprof 二进制解析的统一入口。
 *
 * <p>包装 GridKit 的 hprof-heap
 * （{@code org.gridkit.jvmtool:hprof-heap}，即 Eclipse MAT 所依赖的 NetBeans PerfLib HPROF
 * 后端），对外提供 Java 结构化数据：对象实例、按类直方图与
 * retained 占用最高的对象。{@link #parse(File)} / {@link #parse(InputStream)} 的输出
 * 即为 hprof → JSON / Markdown → AI 分析链路中的
 * "Java 对象 / 结构化数据" 中间层。</p>
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
                          List<HprofRefChainWalker.RefChain> refChains,
                          long totalRetainedBytes,
                          long totalObjectCount) {
    }

    /**
     * 构造方法，创建 HprofParser 实例。
     */
    private HprofParser() {
    }

    /**
    * Parse an hprof file from disk.
    *
    * @param file hprof binary file
    * @return parsed result
    * @throws IOException 无法读取该文件时
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
    * @throws IOException 无法读取该流时
    */
    public static Result parse(InputStream is) throws IOException {
        Objects.requireNonNull(is, "input stream");
        byte[] data = is.readAllBytes();
        HprofParseContext.ParsedContext ctx = HprofParseContext.parse(data);
        return fromContext(ctx);
    }

    /**
    * 解析 hprof 流，可额外传入用于报告的文件名。
    *
    * @param is       hprof binary stream
    * @param fileName file name (nullable), used in markdown headers
    * @return parsed result
    * @throws IOException 无法读取该流时
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
    * @throws IOException 无法解析这些字节时
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
        List<HprofRefChainWalker.RefChain> refChains = ctx.refChains();
        return new Result(objects, histogram, topRetained, ctx.gcRoots(),
                gcRootsByKind, classDetails, refChains, totalRetained, totalCount);
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
    * 基于解析上下文构建按类直方图行。
    *
    * <p>上下文中已带有按类统计的浅堆大小（按
    * {@code instanceSize * instances} 计算），因此直方图
    * 可一次遍历组装完成，无需再扫描对象列表。</p>
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
        // 每个类的浅堆大小：从对象列表重新累加（一次遍历）
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
