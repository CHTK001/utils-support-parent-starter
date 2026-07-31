package com.chua.starter.datasync.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataSyncFieldMappingTest {

    @Test
    void shouldConstructAndReturnFields() {
        var fm = new DataSyncFieldMapping() {
            @Override public String sourceField() { return "name"; }
            @Override public String targetField() { return "user_name"; }
            @Override public String converter() { return "toString"; }
        };
        assertEquals("name", fm.sourceField());
        assertEquals("user_name", fm.targetField());
        assertEquals("toString", fm.converter());
    }
}
