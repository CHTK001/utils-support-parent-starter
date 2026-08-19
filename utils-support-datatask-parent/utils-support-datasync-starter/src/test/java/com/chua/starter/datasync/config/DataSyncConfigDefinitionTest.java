package com.chua.starter.datasync.config;

import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class DataSyncConfigDefinitionTest {

    @Test
    void textConfigShouldReturnText() {
        var cfg = new TextConfigDefinition() {
            @Override public String text() { return "hello"; }
            @Override public String inputId() { return "in1"; }
            @Override public String sourceId() { return "src1"; }
            @Override public String outputId() { return "out1"; }
            @Override public String sinkId() { return "sink1"; }
            @Override public List<DataSyncFieldMapping> mappings() { return List.of(); }
            @Override public int batch() { return 100; }
            @Override public String cronType() { return ""; }
            @Override public String cron() { return ""; }
            @Override public Map<String, Object> params() { return Map.of(); }
        };
        assertEquals("hello", cfg.text());
    }

    @Test
    void fileConfigShouldReturnPath() {
        var cfg = new FileConfigDefinition() {
            @Override public String filePath() { return "/data/file.csv"; }
            @Override public String inputId() { return "in1"; }
            @Override public String sourceId() { return "src1"; }
            @Override public String outputId() { return "out1"; }
            @Override public String sinkId() { return "sink1"; }
            @Override public List<DataSyncFieldMapping> mappings() { return List.of(); }
            @Override public int batch() { return 100; }
            @Override public String cronType() { return ""; }
            @Override public String cron() { return ""; }
            @Override public Map<String, Object> params() { return Map.of(); }
        };
        assertEquals("/data/file.csv", cfg.filePath());
    }

    @Test
    void directoryConfigShouldReturnPath() {
        var cfg = new DirectoryConfigDefinition() {
            @Override public String directoryPath() { return "/data/dir"; }
            @Override public String inputId() { return "in1"; }
            @Override public String sourceId() { return "src1"; }
            @Override public String outputId() { return "out1"; }
            @Override public String sinkId() { return "sink1"; }
            @Override public List<DataSyncFieldMapping> mappings() { return List.of(); }
            @Override public int batch() { return 100; }
            @Override public String cronType() { return ""; }
            @Override public String cron() { return ""; }
            @Override public Map<String, Object> params() { return Map.of(); }
        };
        assertEquals("/data/dir", cfg.directoryPath());
    }
}

