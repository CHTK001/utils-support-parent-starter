package com.chua.starter.datasync.mapping;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import com.chua.starter.datasync.model.DataSyncMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDataSyncMappingManagerTest {

    @Test
    void shouldAddAndGetMappings() {
        var mgr = new DefaultDataSyncMappingManager();
        var m1 = createMapping("m1", "in1", "out1");
        mgr.addMapping(m1);
        assertEquals(1, mgr.getMappings().size());
        assertEquals("m1", mgr.getMappings().get(0).mappingId());
    }

    @Test
    void shouldGetMappingsByInputId() {
        var mgr = new DefaultDataSyncMappingManager();
        mgr.addMapping(createMapping("m1", "inA", "out1"));
        mgr.addMapping(createMapping("m2", "inA", "out2"));
        mgr.addMapping(createMapping("m3", "inB", "out3"));
        List<DataSyncMapping> byInput = mgr.getMappingsByInputId("inA");
        assertEquals(2, byInput.size());
        assertTrue(byInput.stream().allMatch(m -> "inA".equals(m.inputId())));
    }

    @Test
    void shouldGetMappingsByOutputId() {
        var mgr = new DefaultDataSyncMappingManager();
        mgr.addMapping(createMapping("m1", "in1", "outX"));
        mgr.addMapping(createMapping("m2", "in2", "outX"));
        List<DataSyncMapping> byOutput = mgr.getMappingsByOutputId("outX");
        assertEquals(2, byOutput.size());
    }

    @Test
    void shouldCreateFromConfig() {
        var mgr = new DefaultDataSyncMappingManager();
        var config = new DataSyncConfigDefinition() {
            @Override public String inputId() { return "in1"; }
            @Override public String sourceId() { return "src1"; }
            @Override public String outputId() { return "out1"; }
            @Override public String sinkId() { return "sink1"; }
            @Override public List<DataSyncFieldMapping> mappings() { return List.of(); }
            @Override public int batch() { return 50; }
            @Override public String cronType() { return "cron"; }
            @Override public String cron() { return "0 */5 * * * ?"; }
            @Override public Map<String, Object> params() { return Map.of(); }
        };
        DataSyncMapping mapping = mgr.createFromConfig("m1", config);
        assertEquals("m1", mapping.mappingId());
        assertEquals("in1", mapping.inputId());
        assertEquals("src1", mapping.sourceId());
    }

    private static DataSyncMapping createMapping(String id, String inputId, String outputId) {
        return new DefaultDataSyncMapping(id, inputId, "src", outputId, "sink",
                null, List.of(), 100, "", "", Map.of(), null);
    }
}
