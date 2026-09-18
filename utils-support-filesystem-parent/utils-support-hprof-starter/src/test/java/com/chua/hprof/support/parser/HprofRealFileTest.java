package com.chua.hprof.support.parser;

import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.serializer.HprofToJsonSerializer;
import com.chua.hprof.support.serializer.HprofToHtmlSerializer;
import com.chua.hprof.support.serializer.HprofToMarkdownSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test against a real hprof file.
 *
 * <p>Reads {@code C:\Users\Administrator\java_error_in_idea.hprof} when
 * present, parses it through the GridKit hprof-heap backend and verifies
 * the JSON / Markdown output shape. Each test auto-assumes the file
 * exists; when the file is absent (CI or a fresh checkout) the test is
 * silently skipped.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class HprofRealFileTest {

    /**
     * Path to the real hprof file under test.
     */
    private static final String HPROF_PATH = "C:\\Users\\Administrator\\java_error_in_idea.hprof";

    /**
     * Skip the test when the hprof file is not on disk.
     */
    private void assumeHprofPresent() {
        Assumptions.assumeTrue(new File(HPROF_PATH).exists(),
                "hprof file not present at " + HPROF_PATH + ", skipping");
    }

    /**
     * Parse a real hprof file and verify the result is non-empty.
     *
     * <p>All four tests share a single parse of the 790MB dump via this
     * static holder (each parse takes several minutes).</p>
     */
    private static final java.util.concurrent.atomic.AtomicReference<HprofParser.Result> CACHED =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static HprofParser.Result parseOnce() throws java.io.IOException {
        HprofParser.Result existing = CACHED.get();
        if (existing != null) {
            return existing;
        }
        File file = new File(HPROF_PATH);
        long start = System.currentTimeMillis();
        HprofParser.Result result = HprofParser.parse(file);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[hprof] parsed " + file.length() + " bytes in " + elapsed + "ms, "
                + result.objects().size() + " objects, " + result.gcRoots().size() + " gc roots");
        CACHED.set(result);
        return result;
    }

    /**
     * Parse a real hprof file and verify the result is non-empty.
     */
    @Test
    void parseRealHprof() throws Exception {
        assumeHprofPresent();
        HprofParser.Result result = parseOnce();
        assertNotNull(result);
        assertFalse(result.objects().isEmpty(), "expected parsed objects");
        assertFalse(result.histogram().isEmpty(), "expected histogram rows");
        assertFalse(result.topRetained().isEmpty(), "expected top retained rows");
        assertTrue(result.totalRetainedBytes() > 0, "expected positive total retained bytes");
    }

    /**
     * Convert a real hprof file to JSON and verify the leak_suspects shape.
     */
    @Test
    void toJsonOfRealHprof() throws Exception {
        assumeHprofPresent();
        HprofParser.Result result = parseOnce();
        File file = new File(HPROF_PATH);
        String json = HprofToJsonSerializer.serialize(result, file.getName());
        assertNotNull(json);
        assertTrue(json.contains("leak_suspects"), "json should contain leak_suspects");
        assertTrue(json.contains("class_histogram"), "json should contain class_histogram");
        assertTrue(json.contains("meta"), "json should contain meta block");

        JsonNode root = new ObjectMapper().readTree(json);
        assertNotNull(root.get("leak_suspects"));
        int suspects = root.get("leak_suspects").size();
        assertTrue(suspects > 0, "expected at least one leak suspect");
        JsonNode first = root.get("leak_suspects").get(0);
        assertTrue(first.has("class"), "leak suspect must have class");
        assertTrue(first.has("retained_size"), "leak suspect must have retained_size");
        assertTrue(first.has("gc_root"), "leak suspect must have gc_root");

        JsonNode histogram = root.get("class_histogram");
        assertTrue(histogram.size() > 0, "expected at least one histogram row");
        JsonNode row0 = histogram.get(0);
        assertTrue(row0.has("class"));
        assertTrue(row0.has("instance_count"));
        assertTrue(row0.has("retained_size"));

        // write to a temp file so the user can inspect
        Path out = Path.of("target", "real-hprof.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        System.out.println("[hprof] json written to " + out.toAbsolutePath());
    }

    /**
     * Convert a real hprof file to Markdown and verify the table shape.
     */
    @Test
    void toMarkdownOfRealHprof() throws Exception {
        assumeHprofPresent();
        HprofParser.Result result = parseOnce();
        File file = new File(HPROF_PATH);
        String md = HprofToMarkdownSerializer.serialize(result, file.getName());
        assertNotNull(md);
        assertTrue(md.contains("# HPROF 堆内存分析"), "markdown should contain report title");
        assertTrue(md.contains("## 类直方图"), "markdown should contain histogram section");
        assertTrue(md.contains("## 泄漏嫌疑"), "markdown should contain suspect section");
        assertTrue(md.contains("## 根因判定"), "markdown should contain root cause section");
        assertTrue(md.contains("| 类名 |"), "markdown should contain histogram table header");
        assertTrue(md.contains("| 类名 | 保留大小 | GC 根 / 引用链 |"), "markdown should contain suspect table header");

        Path out = Path.of("target", "real-hprof.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println("[hprof] markdown written to " + out.toAbsolutePath());
    }

    /**
     * Print a quick top-5 retained summary to stdout.
     */
    @Test
    void topRetainedSummary() throws Exception {
        assumeHprofPresent();
        HprofParser.Result result = parseOnce();
        File file = new File(HPROF_PATH);
        System.out.println("[hprof] top 5 retained classes in " + file.getName() + ":");
        int shown = 0;
        for (HprofObject o : result.topRetained()) {
            System.out.println("  " + o.getClassName() + " retained=" + o.getRetainedSizeText()
                    + " root=" + (o.getGcRoot() == null ? "unknown" : o.getGcRoot()));
            if (++shown >= 5) {
                break;
            }
        }
        assertFalse(result.topRetained().isEmpty(), "expected top retained rows");
    }

    /**
     * Render the real hprof to a self-contained HTML report.
     */
    @Test
    void toHtmlOfRealHprof() throws Exception {
        assumeHprofPresent();
        HprofParser.Result result = parseOnce();
        File file = new File(HPROF_PATH);
        String html = HprofToHtmlSerializer.serialize(result, file.getName());
        assertNotNull(html);
        assertTrue(html.contains("<!DOCTYPE html>"), "html doctype");
        assertTrue(html.contains("echarts"), "echarts script");
        assertTrue(html.contains("chart-retained"), "retained chart");
        assertTrue(html.contains("算法分析结论"), "findings heading");

        Path out = Path.of("target", "real-hprof.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);
        System.out.println("[hprof] html written to " + out.toAbsolutePath()
                + " (" + html.length() + " chars)");
    }
}
