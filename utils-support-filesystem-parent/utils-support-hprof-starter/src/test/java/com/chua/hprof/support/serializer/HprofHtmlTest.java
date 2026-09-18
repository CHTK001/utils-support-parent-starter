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
 * Unit tests for the HTML serializer and the rule-based analyzer.
 *
 * @author CH
 * @since 4.0.0.42
 */
class HprofHtmlTest {

    /**
     * A hand-built result that exercises every analyzer rule.
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
                Map.of(), totalRetained, 3_600_000L);
    }

    /**
     * The HTML document carries every visual section.
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
     * The embedded JSON data block is well-formed and carries chart data.
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
     * The analyzer fires the high-severity rules on a concentrated heap.
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
     * The JSON document now carries the analysis block.
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
    }

    /**
     * The AI summary block appears only when provided.
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
     * The HprofAiSummarizer delegates to the ChatClient.chatSync.
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
}
