package com.chua.starter.datasync.model;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import com.chua.starter.datasync.mapping.DefaultDataSyncMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class DataSyncMappingTest {

    @Test
    void shouldConstructDefaultMapping() {
        var mapping = new DefaultDataSyncMapping(
                "m1", "in1", "src1", "out1", "sink1",
                null, List.of(), 100, "cron", "*/5 * * * * ?",
                Map.of("key", "val"), null
        );
        assertEquals("m1", mapping.mappingId());
        assertEquals("in1", mapping.inputId());
        assertEquals("src1", mapping.sourceId());
        assertEquals("out1", mapping.outputId());
        assertEquals("sink1", mapping.sinkId());
        assertEquals(100, mapping.batch());
        assertEquals("cron", mapping.cronType());
        assertEquals("*/5 * * * * ?", mapping.cron());
        assertEquals("val", mapping.params().get("key"));
        assertNull(mapping.trigger());
    }

    @Test
    void shouldConstructFromConfig() {
        var config = new FileConfigDefinition() {
            @Override public String filePath() { return "/tmp/f"; }
            @Override public String inputId() { return "in1"; }
            @Override public String sourceId() { return "src1"; }
            @Override public String outputId() { return "out1"; }
            @Override public String sinkId() { return "sink1"; }
            @Override public List<DataSyncFieldMapping> mappings() { return List.of(); }
            @Override public int batch() { return 50; }
            @Override public String cronType() { return "cron"; }
            @Override public String cron() { return "0 */1 * * * ?"; }
            @Override public Map<String, Object> params() { return Map.of(); }
        };
        var mapping = new DefaultDataSyncMapping("m1", config);
        assertEquals("m1", mapping.mappingId());
        assertEquals("in1", mapping.inputId());
        assertEquals("src1", mapping.sourceId());
        assertEquals("out1", mapping.outputId());
        assertEquals("sink1", mapping.sinkId());
        assertEquals(50, mapping.batch());
    }
}

