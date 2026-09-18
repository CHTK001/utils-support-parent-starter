package com.chua.hprof.support.parser;

import com.chua.hprof.support.model.HprofHistogramRow;
import com.chua.hprof.support.model.HprofObject;
import com.chua.hprof.support.serializer.HprofToJsonSerializer;
import com.chua.hprof.support.serializer.HprofToMarkdownSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the hprof to JSON / Markdown conversion chain.
 *
 * <p>These tests exercise the serializers directly with a hand-built
 * {@link HprofParser.Result} so they do not require a real hprof binary.
 * A real hprof parse test would need a sample heap dump on the test class
 * path; when one is available it can be added here.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class HprofConverterTest {

    /**
     * JSON 序列化器产出 leak_suspects 形状。
     */
    @Test
    void toJsonEmitsLeakSuspects() {
        HprofObject suspect = new HprofObject("java.util.HashMap", 1L, 0L, 1_200_000_000L);
        suspect.setGcRoot("static OrderCache.cache");
        suspect.setRefChain("static OrderCache.cache");
        HprofHistogramRow row = new HprofHistogramRow("java.util.HashMap", 1L, 0L, 1_200_000_000L);
        HprofParser.Result result = new HprofParser.Result(
                List.of(suspect), List.of(row), List.of(suspect),
                List.of("static OrderCache.cache"), Map.of("sticky class", 1L),
                Map.of(), 1_200_000_000L, 1L);

        String json = HprofToJsonSerializer.serialize(result, "sample.hprof");
        assertNotNull(json);
        assertTrue(json.contains("leak_suspects"), "json should contain leak_suspects: " + json);
        assertTrue(json.contains("java.util.HashMap"), "json should contain class name");
        assertTrue(json.contains("static OrderCache.cache"), "json should contain gc root");

        try {
            JsonNode root = new ObjectMapper().readTree(json);
            assertNotNull(root.get("leak_suspects"));
            assertEquals(1, root.get("leak_suspects").size());
            JsonNode first = root.get("leak_suspects").get(0);
            assertEquals("java.util.HashMap", first.get("class").asText());
            assertEquals("1.1GB", first.get("retained_size").asText());
            assertEquals("static OrderCache.cache", first.get("gc_root").asText());
        } catch (Exception e) {
            throw new AssertionError("failed to parse generated json", e);
        }
    }

    /**
     * Markdown serializer produces the class histogram table.
     */
    @Test
    void toMarkdownEmitsHistogramTable() {
        HprofObject suspect = new HprofObject("java.util.HashMap", 1L, 0L, 1_200_000_000L);
        suspect.setGcRoot("static OrderCache.cache");
        HprofHistogramRow row = new HprofHistogramRow("java.util.HashMap", 1L, 0L, 1_200_000_000L);
        HprofParser.Result result = new HprofParser.Result(
                List.of(suspect), List.of(row), List.of(suspect),
                List.of("static OrderCache.cache"), Map.of("sticky class", 1L),
                Map.of(), 1_200_000_000L, 1L);

        String md = HprofToMarkdownSerializer.serialize(result, "sample.hprof");
        assertNotNull(md);
        assertTrue(md.contains("类直方图"), "markdown should contain 类直方图 header");
        assertTrue(md.contains("泄漏嫌疑"), "markdown should contain 泄漏嫌疑 header");
        assertTrue(md.contains("根因判定"), "markdown should contain 根因判定 header");
        assertTrue(md.contains("HashMap"), "markdown should contain class name");
        assertTrue(md.contains("static OrderCache.cache"), "markdown should contain gc root");
        assertTrue(md.contains("1.1GB"), "markdown should contain retained size");
    }

    /**
     * formatSize handles the unit boundaries.
     */
    @Test
    void formatSizeUnitBoundaries() {
        assertEquals("0B", HprofObject.formatSize(0L));
        assertEquals("512B", HprofObject.formatSize(512L));
        assertEquals("1KB", HprofObject.formatSize(1024L));
        assertEquals("1MB", HprofObject.formatSize(1024L * 1024L));
        assertEquals("1GB", HprofObject.formatSize(1024L * 1024L * 1024L));
    }
}
