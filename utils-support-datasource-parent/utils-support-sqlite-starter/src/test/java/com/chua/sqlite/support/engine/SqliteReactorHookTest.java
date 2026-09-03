package com.chua.sqlite.support.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteReactorHook 单元测试（不需要 native 库）。
 */
class SqliteReactorHookTest {

    @Test
    void testParseEventInsert() {
        String json = "{\"type\":\"INSERT\",\"database\":\"main\",\"table\":\"users\",\"rowId\":1}";
        SqliteChangeEvent event = SqliteReactorHook.parseEvent(json);

        assertNotNull(event);
        assertEquals(SqliteChangeEvent.Type.INSERT, event.getType());
        assertEquals("users", event.getTable());
        assertEquals(1L, event.getRowId());
    }

    @Test
    void testParseEventUpdate() {
        String json = "{\"type\":\"UPDATE\",\"database\":\"main\",\"table\":\"orders\",\"rowId\":42}";
        SqliteChangeEvent event = SqliteReactorHook.parseEvent(json);

        assertNotNull(event);
        assertEquals(SqliteChangeEvent.Type.UPDATE, event.getType());
        assertEquals("orders", event.getTable());
        assertEquals(42L, event.getRowId());
    }

    @Test
    void testParseEventDelete() {
        String json = "{\"type\":\"DELETE\",\"database\":\"main\",\"table\":\"logs\",\"rowId\":999}";
        SqliteChangeEvent event = SqliteReactorHook.parseEvent(json);

        assertNotNull(event);
        assertEquals(SqliteChangeEvent.Type.DELETE, event.getType());
        assertEquals("logs", event.getTable());
        assertEquals(999L, event.getRowId());
    }

    @Test
    void testParseEventNull() {
        assertNull(SqliteReactorHook.parseEvent(null));
        assertNull(SqliteReactorHook.parseEvent(""));
        assertNull(SqliteReactorHook.parseEvent("invalid json"));
    }

    @Test
    void testParseEventMissingFields() {
        String json = "{\"type\":\"INSERT\"}";
        SqliteChangeEvent event = SqliteReactorHook.parseEvent(json);

        assertNotNull(event);
        assertEquals("(unknown)", event.getTable());
    }

    @Test
    void testParseEventLargeRowId() {
        String json = "{\"type\":\"INSERT\",\"database\":\"main\",\"table\":\"t\",\"rowId\":9999999999}";
        SqliteChangeEvent event = SqliteReactorHook.parseEvent(json);

        assertNotNull(event);
        assertEquals(9999999999L, event.getRowId());
    }
}
