package com.chua.hprof.support.serializer;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.hprof.support.analyzer.HprofAnalyzer;
import com.chua.hprof.support.analyzer.HprofAnalyzer.HprofAnalysis;
import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.parser.HprofParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 序列化器与规则分析器的单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class HprofHtmlTest {

    /**
     * 手工构造的结果，可触发分析器的每一条规则。
     * @return HprofParser结果 对象
     */
    private static HprofParser.Result bigHeap() {
        HprofObject top = new HprofObject("java.util.HashMap", 500_000L, 20_000_000L, 500_000_000L);
        top.setGcRoot("static OrderCache.cache");
        HprofHistogramRow hashRow = new HprofHistogramRow("java.util.HashMap", 500_000L, 20_000_000L, 500_000_000L);
        HprofHistogramRow stringRow = new HprofHistogramRow("java.lang.String", 1_000_000L, 40_000_000L, 300_000_000L);
        HprofHistogramRow loaderRow = new HprofHistogramRow("com.acme.plugin.PluginClassLoader", 120L, 1_000_000L, 80_000_000L);
        HprofHistogramRow arrayRow = new HprofHistogramRow("byte[]", 2_000_000L, 80_000_000L, 200_000_000L);
        List<HprofHistogramRow> histogram = List.of(hashRow, stringRow, loaderRow, arrayRow);
        List<HprofObject> topRetained = List.of(top);
        long totalRetained = 500_000_000L + 300_000_000L + 80_000_000L + 200_000_000L;
        return new HprofParser.Result(
                List.of(top), histogram, topRetained,
                List.of("sticky class:java.lang.Class"),
                Map.of("sticky class", 40L, "thread object", 600L, "JNI global", 1500L),
                Map.of(), List.of(), totalRetained, 3_600_000L);
    }

    /**
     * HTML 文档包含全部可视化区块。
     */
    @Test
    void htmlContainsAllSections() {
        String html = HprofToHtmlSerializer.serialize(bigHeap(), "sample.hprof");
        assertNotNull(html);
        assertTrue(html.contains("<!DOCTYPE html>"), "html doctype");
        assertTrue(html.contains("echarts@5.5.0"), "echarts cdn");
        assertTrue(html.contains("chart-retained"), "retained chart");
        assertTrue(html.contains("chart-instances"), "instances chart");
        assertTrue(html.contains("chart-roots"), "gc-roots chart");
        assertTrue(html.contains("kpi-row"), "kpi cards");
        assertTrue(html.contains("算法分析结论"), "findings heading");
        assertTrue(html.contains("结论"), "conclusions heading");
        assertTrue(html.contains("sample.hprof"), "source file name");
    }

    /**
     * 内嵌的 JSON 数据块格式合法且带有图表数据。
     */
    @Test
    void embeddedDataIsJson() throws Exception {
        String html = HprofToHtmlSerializer.serialize(bigHeap(), "sample.hprof");
        int start = html.indexOf("const DATA = ") + "const DATA = ".length();
        int end = html.indexOf(";\n", start);
        String json = html.substring(start, end);
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals("sample.hprof", root.get("file").asText());
        assertTrue(root.get("retained").size() > 0, "retained rows");
        assertTrue(root.get("instances").size() > 0, "instances rows");
        assertTrue(root.get("roots").size() > 0, "gc root rows");
        assertTrue(root.get("findings").size() > 0, "findings rows");
    }

    /**
     * 内存高度集中时，分析器会触发高严重度规则。
     */
    @Test
    void analyzerFiresHighSeverityRules() {
        HprofAnalysis analysis = HprofAnalyzer.analyze(bigHeap());
        assertTrue(analysis.topNRetainedRatio > 0.5, "top-10 concentration");
        assertTrue(analysis.classLoaderClassCount >= 1, "class loader counted");
        List<String> keys = analysis.findingDetails.stream().map(f -> f.key()).toList();
        assertTrue(keys.contains("high_concentration"), "concentration finding: " + keys);
        assertTrue(keys.contains("collection_bloat"), "collection finding: " + keys);
        assertTrue(keys.contains("jni_grefs"), "jni grefs finding: " + keys);
        assertTrue(keys.contains("thread_locals"), "thread locals finding: " + keys);
    }

    /**
     * JSON 文档此时包含 analysis 区块。
     */
    @Test
    void jsonContainsAnalysisBlock() throws Exception {
        String json = HprofToJsonSerializer.serialize(bigHeap(), "sample.hprof");
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertNotNull(root.get("analysis"), "analysis node");
        assertTrue(root.get("analysis").get("findings").size() > 0, "findings");
        assertTrue(root.get("analysis").get("conclusions").size() > 0, "conclusions");
        assertNotNull(root.get("analysis").get("metrics").get("top10_retained_ratio"), "metrics");
        var gcRoots = root.get("analysis").get("gc_roots");
        assertNotNull(gcRoots, "gc_roots node");
        assertEquals(1500, gcRoots.get("by_kind").get("JNI global").asInt(), "jni global count");
        assertTrue(gcRoots.get("roots").size() > 0, "gc roots list");
    }

    /**
     * 仅在传入 AI 总结时才渲染 AI 总结区块。
     */
    @Test
    void aiSummaryBlockPresentWhenProvided() {
        String withAi = HprofToHtmlSerializer.serialize(bigHeap(), "sample.hprof",
                "主要内存占用来自 HashMap 大缓存，建议加 LRU 淘汰。");
        assertTrue(withAi.contains("AI 总结"), "ai block heading");
        assertTrue(withAi.contains("主要内存占用来自 HashMap 大缓存"), "ai summary text");
        assertTrue(withAi.contains("ai-summary"), "ai summary card");

        String withoutAi = HprofToHtmlSerializer.serialize(bigHeap(), "sample.hprof", null);
        assertTrue(!withoutAi.contains("AI 总结"), "no ai block when null");

        String blank = HprofToHtmlSerializer.serialize(bigHeap(), "sample.hprof", "   ");
        assertTrue(!blank.contains("AI 总结"), "no ai block when blank");
    }

    /**
     * HprofAiSummarizer 会委托给 ChatClient.chatSync。
     */
    @Test
    void aiSummarizerDelegatesToChatClient() {
        ChatClient fake = prompt -> "固定 AI 总结文本";
        com.chua.hprof.support.ai.HprofAiSummarizer summarizer =
                com.chua.hprof.support.ai.HprofAiSummarizer.of(fake);
        assertTrue(summarizer.isEnabled());
        String summary = summarizer.summarize(bigHeap());
        assertEquals("固定 AI 总结文本", summary);

        com.chua.hprof.support.ai.HprofAiSummarizer disabled =
                com.chua.hprof.support.ai.HprofAiSummarizer.of((ChatClient) null);
        assertTrue(!disabled.isEnabled());
        assertEquals(null, disabled.summarize(bigHeap()));
    }

    /**
     * 分析器把根因以结构化分区呈现，而不只是一大段文本。
     */
    @Test
    void rootCauseIsStructured() {
        HprofAnalysis analysis = HprofAnalyzer.analyze(bigHeap());
        assertNotNull(analysis.rootCauseHeadline, "headline");
        assertTrue(!analysis.rootCauseHeadline.isBlank(), "headline non-blank");
        assertNotNull(analysis.rootCauseSections, "sections");
        List<String> labels = analysis.rootCauseSections.stream()
                .map(s -> s.label()).toList();
        assertTrue(labels.contains("主要根因"), "primary cause: " + labels);
        assertTrue(labels.contains("具体机制"), "mechanisms: " + labels);
        assertTrue(labels.contains("优先处置"), "action: " + labels);
        assertTrue(analysis.rootCauseMechanisms != null && !analysis.rootCauseMechanisms.isEmpty(),
                "at least one mechanism fires on a concentrated heap");
        // 向后兼容的纯文本仍然存在，并且覆盖了各分区内容
        assertTrue(analysis.rootCause.contains("【主要根因】"), "rootCause blob keeps labels");
    }

    /**
     * MCP 诊断工具返回一张简洁的"根因 + 方案"卡片。
     */
    @Test
    void mcpDiagnoseCard() {
        String card = com.chua.hprof.support.mcp.HprofMcpProvider.diagnose(bigHeap(), "sample.hprof");
        assertNotNull(card);
        assertTrue(card.contains("【一句话结论】"), "headline block");
        assertTrue(card.contains("【问题原因（根因判定）】"), "cause block");
        assertTrue(card.contains("▸ 主要根因"), "cause section rendered");
        assertTrue(card.contains("【关键证据】"), "evidence block");
        assertTrue(card.contains("【解决方案（按优先级）】"), "solution block");
        assertTrue(card.contains("怎么做"), "solution how-to line");
        assertTrue(card.contains("[P2]"), "priority marker");
    }
}
