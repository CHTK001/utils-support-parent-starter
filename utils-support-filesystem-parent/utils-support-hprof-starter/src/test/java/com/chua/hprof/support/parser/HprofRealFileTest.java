package com.chua.hprof.support.parser;

import com.chua.hprof.support.mcp.HprofMcpProvider;
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
 * 针对真实 hprof 文件的端到端测试。
 *
 * <p>当 {@code C:\Users\Administrator\java_error_in_idea.hprof} 存在时读取它，
 * 经由 GridKit hprof-heap 后端解析，并校验 JSON / Markdown 的输出结构。
 * 每个测试都会自行假设该文件存在；若文件缺失（CI 环境或全新检出的代码），
 * 则静默跳过该测试。</p>
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
     * 磁盘上没有 hprof 文件时跳过本测试。
     */
    private void assumeHprofPresent() {
        Assumptions.assumeTrue(new File(HPROF_PATH).exists(),
                "hprof file not present at " + HPROF_PATH + ", skipping");
    }

    /**
     * 解析真实 hprof 文件并校验结果非空。
     *
     * <p>四个测试通过该静态持有者共享对 790MB 转储的单次解析
     * （每次解析需要数分钟）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicReference<HprofParser.Result> CACHED =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 解析Once。
     *
     * @return HprofParser结果 对象
     * @throws java.io.IOException 当执行过程不满足前置条件时
     */
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
     * 解析真实 hprof 文件并校验结果非空。
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
     * 将真实 hprof 文件转为 JSON，并校验 leak_suspects 结构。
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

        // 写入临时文件，便于人工查看结果
        Path out = Path.of("target", "real-hprof.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        System.out.println("[hprof] json written to " + out.toAbsolutePath());
    }

    /**
     * 将真实 hprof 文件转为 Markdown，并校验表格结构。
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

    /**
     * 针对真实 hprof 文件生成简洁的 MCP 诊断卡。
     */
    @Test
    void diagnoseRealHprof() throws Exception {
        assumeHprofPresent();
        HprofParser.Result result = parseOnce();
        File file = new File(HPROF_PATH);
        String card = HprofMcpProvider.diagnose(result, file.getPath());
        assertNotNull(card, "diagnose card");
        assertTrue(card.contains("【一句话结论】"), "conclusion block");
        assertTrue(card.contains("【问题原因（根因判定）】"), "cause block");
        assertTrue(card.contains("▸ 主要根因"), "primary cause section");
        assertTrue(card.contains("【关键证据】"), "evidence block");
        assertTrue(card.contains("【解决方案（按优先级）】"), "solution block");

        Path out = Path.of("target", "real-hprof-diagnose.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, card);
        System.out.println("\n[hprof] ===== diagnose card =====\n" + card);
        System.out.println("[hprof] diagnose written to " + out.toAbsolutePath());
    }
}
