package com.chua.ibd.support.restore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ibd2sql 输出 SQL 的解析回归测试。
 *
 * <p>这些方法可以<b>直接喂 SQL 字符串</b>校验，不需要安装外部 ibd2sql，
 * 因此能在任何环境下跑。它们挡住的是三类实测踩过的真 bug：</p>
 * <ol>
 *   <li><b>VALUES 子句末尾的 {@code ;} 没去掉</b> → 外层括号剥不掉、深度恒为 1，
 *       整行被当成<b>一个值</b>，CSV 里每行是一整条 {@code (1,'PENELOPE',...)} 字符串；</li>
 *   <li><b>DDL 里的列名没被使用</b> → 表头是 {@code 0,1,2,...} 下标，毫无可读性；</li>
 *   <li><b>库表名替换用 {@code replaceFirst} + {@code \S+}</b> → 只有第一条 INSERT 改名，
 *       且 {@code CREATE TABLE IF NOT EXISTS} 的 {@code IF} 被当成表名吃掉，
 *       产出 {@code CREATE TABLE `t` NOT EXISTS ...} 这种跑不通的 DDL。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IbdSqlParserTest {

    /**
     * ibd2sql v2.x 的真实输出形态（sakila.actor）
     */
    private static final String ACTOR_SQL = String.join("\n",
            "CREATE TABLE IF NOT EXISTS `sakila`.`actor` (",
            "  `actor_id` smallint unsigned NOT NULL AUTO_INCREMENT,",
            "  `first_name` varchar(45) NOT NULL,",
            "  `last_name` varchar(45) NOT NULL,",
            "  `last_update` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,",
            "  PRIMARY KEY (`actor_id`),",
            "  KEY `idx_actor_last_name` (`last_name`)",
            ") ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;",
            "INSERT INTO `sakila`.`actor` VALUES (1,'PENELOPE','GUINESS','2006-02-15 04:34:33');",
            "INSERT INTO `sakila`.`actor` VALUES (2,'NICK','WAHLBERG','2006-02-15 04:34:33');");

    // ==================== 列名提取 ====================

    @Test
    void shouldExtractColumnNamesFromDdl() {
        List<String> columns = new IbdDataRestore().parseCreateTableColumns(ACTOR_SQL);

        assertEquals(List.of("actor_id", "first_name", "last_name", "last_update"), columns,
                "应取出真实列名，并跳过 PRIMARY KEY / KEY 这些表级约束");
    }

    @Test
    void shouldExtractColumnsWithoutBacktick() {
        String sql = "CREATE TABLE t (\n  id int NOT NULL,\n  name varchar(10)\n) ENGINE=InnoDB;";

        assertEquals(List.of("id", "name"), new IbdDataRestore().parseCreateTableColumns(sql));
    }

    @Test
    void shouldReturnEmptyColumnsWhenNoDdl() {
        assertTrue(new IbdDataRestore().parseCreateTableColumns("SELECT 1;").isEmpty());
    }

    @Test
    void shouldNotTreatColumnTypeParensAsEndOfDdl() {
        String sql = "CREATE TABLE t (\n"
                + "  `id` decimal(10,2) NOT NULL,\n"
                + "  `name` varchar(45) DEFAULT NULL,\n"
                + "  PRIMARY KEY (`id`)\n"
                + ") ENGINE=InnoDB;";

        assertEquals(List.of("id", "name"), new IbdDataRestore().parseCreateTableColumns(sql));
    }

    // ==================== 行解析 ====================

    @Test
    void shouldParseRowsWithRealColumnNames() {
        List<Map<String, Object>> rows = new IbdDataRestore().parseSqlToRows(ACTOR_SQL);

        assertEquals(2, rows.size());
        Map<String, Object> first = rows.getFirst();
        assertEquals(List.of("actor_id", "first_name", "last_name", "last_update"),
                List.copyOf(first.keySet()));
        // 关键：值必须按列拆开，且字符串字面量的引号被去掉
        assertEquals("1", first.get("actor_id"));
        assertEquals("PENELOPE", first.get("first_name"));
        assertEquals("GUINESS", first.get("last_name"));
        assertEquals("2006-02-15 04:34:33", first.get("last_update"));
    }

    @Test
    void shouldKeepCommaInsideStringValue() {
        String sql = "CREATE TABLE t (\n  `a` int,\n  `b` varchar(50),\n  `c` int\n) ENGINE=InnoDB;"
                + "\nINSERT INTO `t` VALUES (1,'a,b',2);";

        Map<String, Object> row = new IbdDataRestore().parseSqlToRows(sql).getFirst();

        assertEquals("a,b", row.get("b"), "引号内的逗号不能被当成字段分隔符");
        assertEquals("2", row.get("c"));
    }

    @Test
    void shouldHandleEscapedQuotes() {
        String sql = "CREATE TABLE t (\n  `a` int,\n  `b` varchar(50)\n) ENGINE=InnoDB;"
                + "\nINSERT INTO `t` VALUES (1,'it''s ok');";

        assertEquals("it's ok", new IbdDataRestore().parseSqlToRows(sql).getFirst().get("b"));
    }

    @Test
    void shouldHandleNestedParensAndNull() {
        String sql = "CREATE TABLE t (\n  `a` int,\n  `b` point,\n  `c` int\n) ENGINE=InnoDB;"
                + "\nINSERT INTO `t` VALUES (1,point(1,2),null);";

        Map<String, Object> row = new IbdDataRestore().parseSqlToRows(sql).getFirst();

        assertEquals("point(1,2)", row.get("b"), "函数调用里的逗号不能拆列");
        assertEquals("null", row.get("c"));
    }

    @Test
    void shouldParseMultiRowValues() {
        String sql = "CREATE TABLE t (\n  `a` int,\n  `b` varchar(10)\n) ENGINE=InnoDB;"
                + "\nINSERT INTO `t` VALUES (1,'x'),(2,'y');";

        List<Map<String, Object>> rows = new IbdDataRestore().parseSqlToRows(sql);

        assertEquals(2, rows.size());
        assertEquals("y", rows.get(1).get("b"));
    }

    @Test
    void shouldFallBackToIndexKeysWhenDdlMissing() {
        List<Map<String, Object>> rows = new IbdDataRestore().parseSqlToRows(
                "INSERT INTO `t` VALUES (1,'x');");

        assertEquals(1, rows.size());
        assertEquals("1", rows.getFirst().get("0"));
        assertEquals("x", rows.getFirst().get("1"));
    }

    @Test
    void shouldReturnEmptyRowsWhenNoInsert() {
        assertTrue(new IbdDataRestore().parseSqlToRows("CREATE TABLE t (\n  `a` int\n);").isEmpty());
    }

    // ==================== 值拆分 ====================

    @Test
    void shouldSplitValues() {
        IbdDataRestore restore = new IbdDataRestore();

        assertEquals(List.of("1", "PENELOPE"), restore.splitValues("1,'PENELOPE'"));
        assertEquals(List.of("a,b", "2"), restore.splitValues("'a,b',2"));
        assertEquals(List.of("point(1,2)"), restore.splitValues("point(1,2)"));
    }

    // ==================== 库表名替换 ====================

    /**
     * 实测踩过的真 bug：{@code replaceFirst} 只会改中第一条 INSERT。
     * 一张 200 行的表，改完只有第 1 行落到新表，其余 199 行仍写原表。
     */
    @Test
    void shouldRenameEveryInsertNotJustTheFirst() {
        String sql = "CREATE TABLE IF NOT EXISTS `sakila`.`actor` (\n  `actor_id` int\n) ENGINE=InnoDB;"
                + "\nINSERT INTO `sakila`.`actor` VALUES (1,'A');"
                + "\nINSERT INTO `sakila`.`actor` VALUES (2,'B');"
                + "\nINSERT INTO `sakila`.`actor` VALUES (3,'C');";

        String result = new IbdDataRestore().replaceTableNames(sql, "sakila_new", "actor_new");

        assertEquals(3, countOccurrences(result, "INSERT INTO `sakila_new`.`actor_new`"),
                "所有 INSERT 都应改名，实际: \n" + result);
        assertFalse(result.contains("`sakila`.`actor`"), "原表名应全部被替换掉，实际: \n" + result);
    }

    /**
     * 实测踩过的真 bug：{@code \S+} 把 {@code IF} 当成表名吃掉，
     * 产出 {@code CREATE TABLE `t` NOT EXISTS ...} 这种语法错误的 DDL。
     */
    @Test
    void shouldKeepIfNotExistsWhenRenamingCreateTable() {
        String sql = "CREATE TABLE IF NOT EXISTS `sakila`.`actor` (\n  `actor_id` int\n) ENGINE=InnoDB;";

        String result = new IbdDataRestore().replaceTableNames(sql, "sakila_new", "actor_new");

        assertTrue(result.startsWith("CREATE TABLE IF NOT EXISTS `sakila_new`.`actor_new` ("),
                "IF NOT EXISTS 必须原样保留，实际: " + result);
        assertFalse(result.contains("NOT EXISTS `sakila`"), "不应出现残缺的 NOT EXISTS，实际: " + result);
    }

    @Test
    void shouldRenameTableOnlyAndKeepOriginalSchema() {
        String sql = "INSERT INTO `sakila`.`actor` VALUES (1,'A');";

        String result = new IbdDataRestore().replaceTableNames(sql, "actor_new");

        assertEquals("INSERT INTO `sakila`.`actor_new` VALUES (1,'A');", result);
    }

    @Test
    void shouldRenameSchemaOnlyAndKeepOriginalTable() {
        String sql = "INSERT INTO `sakila`.`actor` VALUES (1,'A');";

        String result = new IbdDataRestore().replaceTableNames(sql, "sakila_new", null);

        assertEquals("INSERT INTO `sakila_new`.`actor` VALUES (1,'A');", result);
    }

    @Test
    void shouldRenameBareUnquotedTableName() {
        String sql = "CREATE TABLE actor (\n  `actor_id` int\n);\nINSERT INTO actor VALUES (1);";

        String result = new IbdDataRestore().replaceTableNames(sql, "actor_new");

        assertTrue(result.startsWith("CREATE TABLE `actor_new` ("), "实际: " + result);
        assertEquals(1, countOccurrences(result, "INSERT INTO `actor_new` VALUES (1);"));
    }

    /**
     * 外键里的 {@code REFERENCES `country`} 指向的是另一张表，
     * 单表还原时不该被改名，否则会指向一张不存在的表。
     */
    @Test
    void shouldNotRenameForeignKeyReferences() {
        String sql = "CREATE TABLE `sakila`.`city` (\n"
                + "  `city_id` smallint unsigned NOT NULL AUTO_INCREMENT,\n"
                + "  CONSTRAINT `fk_city_country` FOREIGN KEY (`country_id`)"
                + " REFERENCES `country` (`country_id`) ON DELETE RESTRICT\n"
                + ") ENGINE=InnoDB;";

        String result = new IbdDataRestore().replaceTableNames(sql, "sakila_new", "city_new");

        assertTrue(result.contains("CREATE TABLE `sakila_new`.`city_new` ("), "实际: " + result);
        assertTrue(result.contains("REFERENCES `country` (`country_id`)"), "外键目标不应被改名，实际: " + result);
    }

    @Test
    void shouldReturnOriginalWhenNothingSpecified() {
        String sql = "INSERT INTO `sakila`.`actor` VALUES (1,'A');";
        IbdDataRestore restore = new IbdDataRestore();

        assertEquals(sql, restore.replaceTableNames(sql, null, null));
        assertEquals(sql, restore.replaceTableNames(sql, "  ", "  "));
        assertEquals(sql, restore.replaceTableNames(sql, (String) null));
    }

    @Test
    void shouldNotTouchQuotedLiteralsLookingLikeStatements() {
        String sql = "INSERT INTO `sakila`.`actor` VALUES (1,'INSERT INTO `fake`.`t` VALUES (9)');";

        String result = new IbdDataRestore().replaceTableNames(sql, "actor_new");

        assertEquals("INSERT INTO `sakila`.`actor_new`"
                + " VALUES (1,'INSERT INTO `fake`.`t` VALUES (9)');", result);
    }

    // ==================== 建库引导语句 ====================

    /**
     * {@code targetSchema} 的契约是「建库语句」——只发 {@code USE} 的话，
     * 目标库不存在时脚本一执行就报 {@code ERROR 1049 Unknown database}。
     */
    @Test
    void shouldEmitCreateDatabaseWhenTargetSchemaGiven() {
        String prologue = IbdDataRestore.schemaPrologue("sakila_new");

        assertEquals("CREATE DATABASE IF NOT EXISTS `sakila_new` DEFAULT CHARACTER SET utf8mb4;\n"
                + "USE `sakila_new`;\n", prologue);
    }

    @Test
    void shouldEmitEmptyPrologueWhenTargetSchemaBlank() {
        assertEquals("", IbdDataRestore.schemaPrologue(null));
        assertEquals("", IbdDataRestore.schemaPrologue(""));
        assertEquals("", IbdDataRestore.schemaPrologue("   "));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
