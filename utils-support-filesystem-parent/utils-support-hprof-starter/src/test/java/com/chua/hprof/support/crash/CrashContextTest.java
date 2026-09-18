package com.chua.hprof.support.crash;

import com.chua.hprof.support.differ.HprofDiffer;
import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃语境探测与两份转储对比器测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class CrashContextTest {

    /**
     * 构造一份模拟的解析结果。
     */
    private static HprofParser.Result heap(long totalRetained, long objectCount,
                                          HprofHistogramRow... rows) {
        HprofObject top = rows.length > 0
                ? new HprofObject(rows[0].getClassName(), rows[0].getInstanceCount(),
                        rows[0].getShallowSize(), rows[0].getRetainedSize())
                : new HprofObject("X", 0L, 0L, 0L);
        return new HprofParser.Result(
                List.of(top), List.of(rows), List.of(top),
                List.of(), Map.of(), Map.of(), List.of(), totalRetained, objectCount);
    }

    /**
     * java_error_in_* 文件名 + 高水位（≥4G）=> 判定 OOM。
     */
    @Test
    void detectsOomFromFileNameAndWaterLevel() {
        File f = new File("/tmp/java_error_in_idea.hprof");
        List<CrashContext.CrashSignal> signals =
                CrashContext.detect(f, 5_000_000_000L, 9_000_000L);
        boolean oom = signals.stream().anyMatch(CrashContext.CrashSignal::oomLikely);
        assertTrue(oom, "expected an OOM-likely signal, got " + signals);
    }

    /**
     * java_error_in_* 文件名但堆水位未打满（<4G）=> OOM 判定降级为存疑。
     */
    @Test
    void downgradesOomWhenWaterLevelLow() {
        File f = new File("/tmp/java_error_in_idea.hprof");
        List<CrashContext.CrashSignal> signals =
                CrashContext.detect(f, 3_800_000_000L, 9_000_000L);
        boolean oom = signals.stream().anyMatch(CrashContext.CrashSignal::oomLikely);
        assertFalse(oom, "low water level must downgrade the OOM verdict: " + signals);
    }

    /**
     * 普通文件名 + 低水位 => 不判定 OOM。
     */
    @Test
    void noOomForLowWaterLevel() {
        File f = new File("/tmp/heapdump001.hprof");
        List<CrashContext.CrashSignal> signals =
                CrashContext.detect(f, 100_000_000L, 10_000L);
        boolean oom = signals.stream().anyMatch(CrashContext.CrashSignal::oomLikely);
        assertFalse(oom, "low water level must not be OOM-likely: " + signals);
    }

    /**
     * 两份转储对比：增长类按 retained 增量降序。
     */
    @Test
    void diffRanksGrowth() {
        HprofParser.Result before = heap(10_000_000L, 1_000L,
                new HprofHistogramRow("com.acme.Cache", 100L, 1_000_000L, 2_000_000L),
                new HprofHistogramRow("java.lang.String", 50L, 1_000_000L, 1_000_000L));
        HprofParser.Result after = heap(50_000_000L, 10_000L,
                new HprofHistogramRow("com.acme.Cache", 5_000L, 30_000_000L, 40_000_000L),
                new HprofHistogramRow("java.lang.String", 60L, 1_100_000L, 1_100_000L));

        HprofDiffer.Diff diff = HprofDiffer.compare(before, after);
        HprofDiffer.ClassGrowth top = diff.topGrowths(1).get(0);
        assertEquals("com.acme.Cache", top.className());
        assertEquals(40_000_000L - 2_000_000L, top.retainedDelta());
        assertEquals(5_000L - 100L, top.instanceDelta());
    }

    /**
     * 新出现的类（before 没有）按完整增量计。
     */
    @Test
    void diffCountsNewClassAsFullGrowth() {
        HprofParser.Result before = heap(1_000_000L, 10L,
                new HprofHistogramRow("java.lang.String", 10L, 100L, 100L));
        HprofParser.Result after = heap(1_000_000L + 5_000_000L, 10L + 100L,
                new HprofHistogramRow("java.lang.String", 10L, 100L, 100L),
                new HprofHistogramRow("com.acme.NewLeak", 100L, 0L, 5_000_000L));

        HprofDiffer.Diff diff = HprofDiffer.compare(before, after);
        HprofDiffer.ClassGrowth top = diff.topGrowths(1).get(0);
        assertEquals("com.acme.NewLeak", top.className());
        assertEquals(5_000_000L, top.retainedDelta());
        assertEquals(0L, top.beforeCount());
    }
}
