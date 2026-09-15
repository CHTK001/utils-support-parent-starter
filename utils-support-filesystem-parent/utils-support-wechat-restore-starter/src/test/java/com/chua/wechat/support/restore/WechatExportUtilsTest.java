package com.chua.wechat.support.restore;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WechatExportUtilsTest {

    @Test
    void testSanitizeFileName() {
        assertEquals("unknown", WechatExportUtils.sanitizeFileName(null));
        assertEquals("unknown", WechatExportUtils.sanitizeFileName(""));
        assertEquals("hello", WechatExportUtils.sanitizeFileName("hello"));
        assertEquals("hello_world", WechatExportUtils.sanitizeFileName("hello/world"));
        String longName = "a_very_long_name_that_is_exactly_fifty_characters_";
        String result = WechatExportUtils.sanitizeFileName(longName);
        assertEquals(50, result.length());
    }

    @Test
    void testIsGroupChat() {
        assertTrue(WechatExportUtils.isGroupChat("wxid_xxx@chatroom"));
        assertFalse(WechatExportUtils.isGroupChat("wxid_xxx"));
        assertFalse(WechatExportUtils.isGroupChat(null));
        assertFalse(WechatExportUtils.isGroupChat(""));
    }

    @Test
    void testFindSessionDb() {
        File tempDir = null;
        try {
            tempDir = File.createTempFile("test", "dir");
            tempDir.delete();
            tempDir.mkdirs();

            File subDir = new File(tempDir, "wxid_test");
            subDir.mkdirs();
            File sessionDb = new File(subDir, "session.db");
            sessionDb.createNewFile();

            File found = WechatExportUtils.findSessionDb(tempDir);
            assertEquals(sessionDb, found);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tempDir != null && tempDir.exists()) {
                deleteRecursively(tempDir);
            }
        }
    }

    @Test
    void testDeriveAccountDir() {
        File accountDir = new File("C:/xwechat_files/wxid_test");
        File sessionDb = new File(accountDir, "session.db");

        File result = WechatExportUtils.deriveAccountDir(sessionDb);
        assertEquals(accountDir, result);
    }

    @Test
    void testDeriveAccountDirNull() {
        assertNull(WechatExportUtils.deriveAccountDir(null));
    }

    @Test
    void testEscapeCsvField() {
        assertEquals("hello", WechatExportUtils.escapeCsvField("hello"));
        assertEquals("\"hello,world\"", WechatExportUtils.escapeCsvField("hello,world"));
        assertEquals("\"hello\"\"world\"", WechatExportUtils.escapeCsvField("hello\"world"));
        assertEquals("\"\n\"", WechatExportUtils.escapeCsvField("\n"));
        assertEquals("", WechatExportUtils.escapeCsvField(null));
    }

    @Test
    void testBuildSqlScript() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("username", "wxid_test");
        row.put("message", "hello");
        rows.add(row);

        String sql = WechatExportUtils.buildSqlScript(rows, "wechat_msg", null, true);
        assertTrue(sql.contains("CREATE TABLE"));
        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("`username`"));
        assertTrue(sql.contains("`message`"));
    }

    @Test
    void testBuildSqlScriptWithoutStructure() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("username", "wxid_test");
        rows.add(row);

        String sql = WechatExportUtils.buildSqlScript(rows, "wechat_msg", null, false);
        assertFalse(sql.contains("CREATE TABLE"));
        assertTrue(sql.contains("INSERT INTO"));
    }

    @Test
    void testBuildSqlScriptWithSchema() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("msg", "test");
        rows.add(row);

        String sql = WechatExportUtils.buildSqlScript(rows, "msgs", "wechat_db", false);
        assertTrue(sql.contains("USE `wechat_db`"));
        // targetSchema 的契约是「建库语句」：只发 USE 的话，库不存在时脚本一执行就报
        // ERROR 1049 Unknown database，用户还得手动建库
        assertTrue(sql.startsWith("CREATE DATABASE IF NOT EXISTS `wechat_db`"), "实际开头: " + sql);
    }

    @Test
    void testBuildSqlScriptWithoutSchemaHasNoPrologue() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("msg", "test");
        rows.add(row);

        String sql = WechatExportUtils.buildSqlScript(rows, "msgs", null, false);
        assertFalse(sql.contains("CREATE DATABASE"), "实际: " + sql);
        assertFalse(sql.contains("USE "), "实际: " + sql);
        assertTrue(sql.startsWith("INSERT INTO `msgs`"), "实际开头: " + sql);
    }

    @Test
    void testParseJsonToRows() throws Exception {
        String json = "[{\"username\":\"wxid_test\",\"message\":\"hello\"}]";
        File jsonFile = File.createTempFile("test", ".json");
        java.nio.file.Files.writeString(jsonFile.toPath(), json);

        List<Map<String, Object>> rows = WechatExportUtils.parseJsonToRows(List.of(jsonFile));
        assertEquals(1, rows.size());
        assertEquals("wxid_test", rows.get(0).get("username"));
        assertEquals("hello", rows.get(0).get("message"));

        jsonFile.delete();
    }

    @Test
    void testParseJsonToRowsSingleObject() throws Exception {
        String json = "{\"username\":\"wxid_test\"}";
        File jsonFile = File.createTempFile("test", ".json");
        java.nio.file.Files.writeString(jsonFile.toPath(), json);

        List<Map<String, Object>> rows = WechatExportUtils.parseJsonToRows(List.of(jsonFile));
        assertEquals(1, rows.size());
        assertEquals("wxid_test", rows.get(0).get("username"));

        jsonFile.delete();
    }

    @Test
    void testToSqlValue() {
        assertEquals("NULL", WechatExportUtils.toSqlValue(null));
        assertEquals("123", WechatExportUtils.toSqlValue(123));
        assertEquals("'hello'", WechatExportUtils.toSqlValue("hello"));
        assertEquals("true", WechatExportUtils.toSqlValue(true));
    }

    private void deleteRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteRecursively(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}