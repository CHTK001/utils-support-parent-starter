package com.chua.starter.datasync.mapping;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** @author CH */
class FieldMappingConverterTest {

    final FieldMappingConverter converter = new DefaultFieldMappingConverter();

    @Test
    void toString_shouldConvert() {
        assertEquals("123", converter.convert(123, "f", "t", "toString"));
        assertEquals("hello", converter.convert("hello", "f", "t", "toString"));
    }

    @Test
    void toInteger_shouldConvert() {
        assertEquals(123, converter.convert("123", "f", "t", "toInteger"));
    }

    @Test
    void toInteger_shouldDefaultToZero() {
        assertEquals(0, converter.convert("abc", "f", "t", "toInteger"));
    }

    @Test
    void toLong_shouldConvert() {
        assertEquals(123456L, converter.convert("123456", "f", "t", "toLong"));
    }

    @Test
    void toDouble_shouldConvert() {
        assertEquals(3.14d, converter.convert("3.14", "f", "t", "toDouble"));
    }

    @Test
    void toBoolean_shouldReturnTrue() {
        assertTrue((Boolean) converter.convert("true", "f", "t", "toBoolean"));
    }

    @Test
    void toBoolean_shouldReturnFalse() {
        assertFalse((Boolean) converter.convert("false", "f", "t", "toBoolean"));
    }

    @Test
    void toDate_shouldParse() {
        Object result = converter.convert("2024-01-15", "f", "t", "toDate");
        assertInstanceOf(Date.class, result);
    }

    @Test
    void toDate_shouldParseMultipleFormats() {
        assertInstanceOf(Date.class, converter.convert("2024/01/15", "f", "t", "toDate"));
        assertInstanceOf(Date.class, converter.convert("20240115", "f", "t", "toDate"));
    }

    @Test
    void unknownConverter_shouldReturnOriginal() {
        assertEquals("hello", converter.convert("hello", "f", "t", "unknown"));
    }

    @Test
    void nullValue_shouldReturnNull() {
        assertNull(converter.convert(null, "f", "t", "toString"));
    }

    @Test
    void applyMappings_shouldTransformRecord() {
        Map<String, Object> record = Map.of("name", "Alice", "age", "30");
        List<DataSyncFieldMapping> mappings = List.of(
                new SimpleMapping("name", "user_name", "toString"),
                new SimpleMapping("age", "user_age", "toInteger")
        );
        Map<String, Object> result = converter.applyMappings(record, mappings);
        assertEquals("Alice", result.get("user_name"));
        assertEquals(30, result.get("user_age"));
    }

    @Test
    void applyMappings_withEmptyList_shouldReturnOriginal() {
        Map<String, Object> record = Map.of("a", 1);
        assertSame(record, converter.applyMappings(record, List.of()));
    }

    @Test
    void applyMappings_withNullList_shouldReturnOriginal() {
        Map<String, Object> record = Map.of("a", 1);
        assertSame(record, converter.applyMappings(record, null));
    }

    private record SimpleMapping(String sourceField, String targetField, String converter) implements DataSyncFieldMapping {}
}

