package com.chua.wechat.support.restore.memory;

import com.chua.wechat.support.restore.memory.WechatMemoryExtractor.ExtractResult;
import com.chua.wechat.support.restore.memory.WechatMemoryExtractor.ExtractedRecord;
import com.chua.wechat.support.restore.memory.WechatMemoryPageParser.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * rowid 别名列还原测试。
 *
 * <p>SQLite 不把 {@code INTEGER PRIMARY KEY} 列的值写进记录体，因此按页解析读到的
 * 那一格恒为 {@code NULL}；实测导出物里 {@code contact.id} 与 {@code msg_all.local_id}
 * 整列为空，导致无法与 {@code name2id} / {@code real_sender_id} 关联。本用例钉住
 * 「按 DDL 判定别名列」与「用单元格 rowid 回填」两件事。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatMemoryRowidAliasTest {

    @TempDir
    Path tempDir;

    /**
     * rowidAlias应当识别列级INTEGER主键的各种写法。
     */
    @Test
    void rowidAliasShouldRecognizeColumnLevelIntegerPrimaryKey() {
        assertEquals("id", alias("CREATE TABLE contact(id INTEGER PRIMARY KEY, username TEXT)"));
        assertEquals("id", alias("CREATE TABLE contact(id integer PRIMARY KEY, username TEXT)"));
        assertEquals("local_id", alias("CREATE TABLE Msg_x(local_id INTEGER PRIMARY KEY AUTOINCREMENT, server_id INTEGER)"));
        assertEquals("id", alias("CREATE TABLE t(id INTEGER PRIMARY KEY NOT NULL, a TEXT)"));
        assertEquals("id", alias("CREATE TABLE \"t\"(\"id\" INTEGER PRIMARY KEY, \"a\" TEXT)"));
    }

    /**
     * rowidAlias应当拒绝非rowid别名的主键写法。
     */
    @Test
    void rowidAliasShouldRejectNonAliases() {
        // TEXT 主键不是 rowid 别名（Name2Id 就是这种）
        assertNull(alias("CREATE TABLE Name2Id(user_name TEXT PRIMARY KEY, is_session INTEGER)"));
        // 仅声明 INTEGER 而无列级 PRIMARY KEY
        assertNull(alias("CREATE TABLE t(a INTEGER, b TEXT)"));
        // 表级复合主键
        assertNull(alias("CREATE TABLE t(a INTEGER, b INTEGER, PRIMARY KEY(a, b))"));
        // INT（非 INTEGER）主键按 SQLite 规则不是别名
        assertNull(alias("CREATE TABLE t(id INT PRIMARY KEY, a TEXT)"));
        // 两个候选 —— 无法判定，宁可不回填
        assertNull(alias("CREATE TABLE t(a INTEGER PRIMARY KEY, b INTEGER PRIMARY KEY)"));
        // WITHOUT ROWID 表里该声明只是普通列
        assertNull(alias("CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT) WITHOUT ROWID"));
        assertNull(alias(null));
    }

    /**
     * rebuild应当用rowid回填空别名列且不动已有值。
     *
     * @throws Exception 重建或读回失败
     */
    @Test
    void rebuildShouldBackfillAliasFromRowid() throws Exception {
        TableSchema schema = new TableSchema("contact", List.of("id", "username"),
                List.of("INTEGER", "TEXT"), "CREATE TABLE contact(id INTEGER PRIMARY KEY, username TEXT)");
        ExtractResult result = new ExtractResult(List.of(), List.of(
                // 记录体里 id 恒为 null：正常情况
                new ExtractedRecord(7, 0x1000L, "contact", 41L, new String[]{null, "wxid_a"}, new int[]{0, 23}),
                // 已有真实值时不得覆盖
                new ExtractedRecord(7, 0x2000L, "contact", 42L, new String[]{"99", "wxid_b"}, new int[]{23, 23}),
                // rowid 未知（<=0）时保持空，不写出假的 0
                new ExtractedRecord(7, 0x3000L, "contact", 0L, new String[]{null, "wxid_c"}, new int[]{0, 23}),
                // 非别名表不受影响
                new ExtractedRecord(7, 0x4000L, "Name2Id", 5L, new String[]{"wxid_a", "1"}, new int[]{23, 1})
        ), Map.of("contact", schema), List.of());

        File db = tempDir.resolve("memory.db").toFile();
        WechatMemoryRebuilder.rebuild(result, db);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement statement = connection.createStatement()) {
            ResultSet rs = statement.executeQuery(
                    "SELECT username, id FROM contact ORDER BY username");
            rs.next();
            assertEquals("wxid_a", rs.getString(1));
            assertEquals("41", rs.getString(2), "空的 INTEGER PRIMARY KEY 列应由 rowid 回填");
            rs.next();
            assertEquals("wxid_b", rs.getString(1));
            assertEquals("99", rs.getString(2), "已有真实值不能被 rowid 覆盖");
            rs.next();
            assertEquals("wxid_c", rs.getString(1));
            assertNull(rs.getString(2), "rowid 未知时应保持为空，不能写 0");
        }
    }

    /**
     * 取 DDL 的 rowid 别名列名。
     *
     * @param ddl 建表语句，可为 null
     * @return 别名列名，无则 null
     */
    private static String alias(String ddl) {
        return WechatMemoryPageParser.rowidAlias(ddl == null ? null
                : new TableSchema("t", List.of(), List.of(), ddl));
    }
}
