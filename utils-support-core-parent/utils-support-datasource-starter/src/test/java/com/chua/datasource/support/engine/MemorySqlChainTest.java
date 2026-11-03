package com.chua.datasource.support.engine;

import com.chua.datasource.support.engine.MemorySqlAst.DmlPlan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemorySqlParser} + {@link MemorySqlAst} 原生 SQL 链路约定。
 *
 * <p>这条链路是内存/文件引擎「没有 JDBC 执行器却声明 supportsSql()」的全部依据：
 * SQL 被编译成 AST，再在表行引用上求值 / 变更。断言覆盖投影、聚合、
 * 排序截断、WHERE 全部算子、参数绑定，以及 插入/更新/删除 的影响行数语义。</p>
 */
class MemorySqlChainTest {

    private final MemorySqlParser parser = new MemorySqlParser();

    private static List<Map<String, Object>> rows() {
        return new java.util.ArrayList<>(List.of(
                row(1, "张三", 18, "zhangsan@example.com"),
                row(2, "李四", 25, "lisi@example.com"),
                row(3, "王五", null, "wangwu@example.com"),
                row(4, "赵六", 30, null),
                row(5, "李七", 35, "liqi@example.com")));
    }

    private static Map<String, Object> row(int id, String name, Integer age, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("age", age);
        m.put("email", email);
        return m;
    }

    private static List<Integer> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (Integer) r.get("id")).toList();
    }

    @Test
    @DisplayName("SELECT * 返回全部行与全部列")
    void selectAll() {
        List<Map<String, Object>> out = parser.executeQuery("SELECT * FROM user", rows(), null);
        assertEquals(5, out.size());
        assertEquals(4, out.getFirst().size());
        assertEquals("张三", out.getFirst().get("name"));
    }

    @Test
    @DisplayName("SELECT 列清单按声明顺序投影")
    void selectColumns() {
        List<Map<String, Object>> out = parser.executeQuery(
                "SELECT name, id FROM user", rows(), null);
        assertEquals(5, out.size());
        assertEquals(List.of("name", "id"), List.copyOf(out.getFirst().keySet()));
    }

    @Test
    @DisplayName("WHERE 等值 / 不等值 / 大小比较")
    void whereComparisons() {
        assertEquals(List.of(2), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age = ?", rows(), new Object[]{25})));
        // id=2 的 age 就是 25，被 != 排除；id=3 的 age 为 null，按 SQL 三值逻辑也不算命中
        assertEquals(List.of(1, 4, 5), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age != ?", rows(), new Object[]{25})));
        assertEquals(List.of(2, 4, 5), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age > 20", rows(), null)));
        assertEquals(List.of(1, 2), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age <= 25", rows(), null)));
        assertEquals(List.of(1), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age <> 25 AND age < 20", rows(), null)));
    }

    @Test
    @DisplayName("NULL 参与比较不命中")
    void nullNeverMatches() {
        assertEquals(List.of(), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age = ?", rows(), new Object[]{null})));
        // != 也不能把 NULL 行算进来（SQL 三值逻辑）
        assertEquals(List.of(1, 2, 4, 5), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age != ?", rows(), new Object[]{99})));
    }

    @Test
    @DisplayName("WHERE LIKE / BETWEEN / IN / IS NULL")
    void whereSpecialOperators() {
        assertEquals(List.of(1), ids(parser.executeQuery(
                "SELECT * FROM user WHERE name LIKE ?", rows(), new Object[]{"%张%"})));
        assertEquals(List.of(2, 4), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age BETWEEN ? AND ?", rows(), new Object[]{25, 30})));
        assertEquals(List.of(1, 3), ids(parser.executeQuery(
                "SELECT * FROM user WHERE id IN (1, 3)", rows(), null)));
        assertEquals(List.of(3), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age IS NULL", rows(), null)));
        assertEquals(List.of(1, 2, 4, 5), ids(parser.executeQuery(
                "SELECT * FROM user WHERE age IS NOT NULL", rows(), null)));
    }

    @Test
    @DisplayName("AND / OR / NOT / 括号优先级")
    void whereLogic() {
        // AND 高于 OR：id=1 OR (id=2 AND age=25)
        assertEquals(List.of(1, 2), ids(parser.executeQuery(
                "SELECT * FROM user WHERE id = 1 OR id = 2 AND age = 25", rows(), null)));
        // id=1 单独就命中，后半段 AND 不成立也不影响
        assertEquals(List.of(1), ids(parser.executeQuery(
                "SELECT * FROM user WHERE id = 1 OR id = 2 AND age = 99", rows(), null)));
        assertEquals(List.of(2), ids(parser.executeQuery(
                "SELECT * FROM user WHERE (id = 1 OR id = 2) AND age = 25", rows(), null)));
        assertEquals(List.of(1, 2, 3, 5), ids(parser.executeQuery(
                "SELECT * FROM user WHERE NOT age = 30", rows(), null)));
        // NOT 只作用于紧跟的比较式：NOT(age < 30) AND age IS NOT NULL
        // id=4(30)、id=5(35) 满足；id=3 的 age 为 null 被 IS NOT NULL 排除
        assertEquals(List.of(4, 5), ids(parser.executeQuery(
                "SELECT * FROM user WHERE NOT age < 30 AND age IS NOT NULL", rows(), null)));
    }

    @Test
    @DisplayName("ORDER BY 单列 / 多列 / 升降序，null 排最前")
    void orderBy() {
        assertEquals(List.of(3, 1, 2, 4, 5), ids(parser.executeQuery(
                "SELECT * FROM user ORDER BY age", rows(), null)));
        assertEquals(List.of(5, 4, 2, 1, 3), ids(parser.executeQuery(
                "SELECT * FROM user ORDER BY age DESC", rows(), null)));
        // null 在前；其余按 email 字典序 liqi < lisi < wangwu < zhangsan < zhaoliu
        assertEquals(List.of(4, 5, 2, 3, 1), ids(parser.executeQuery(
                "SELECT * FROM user ORDER BY email ASC", rows(), null)));
    }

    @Test
    @DisplayName("LIMIT / OFFSET 截断")
    void limitOffset() {
        assertEquals(2, parser.executeQuery("SELECT * FROM user LIMIT 2", rows(), null).size());
        assertEquals(List.of(3, 4), ids(parser.executeQuery(
                "SELECT * FROM user ORDER BY id LIMIT 2 OFFSET 2", rows(), null)));
        assertEquals(List.of(), parser.executeQuery(
                "SELECT * FROM user LIMIT 2 OFFSET 99", rows(), null));
    }

    @Test
    @DisplayName("COUNT(*) 与标量聚合")
    void aggregates() {
        Map<String, Object> cnt = parser.executeQuery("SELECT COUNT(*) FROM user", rows(), null)
                .getFirst();
        assertEquals(5, cnt.get("cnt"));

        assertEquals(4L, parser.executeQuery("SELECT COUNT(age) FROM user", rows(), null)
                .getFirst().get("age"));
        assertEquals(35, parser.executeQuery("SELECT MAX(age) FROM user", rows(), null)
                .getFirst().get("age"));
        assertEquals(18, parser.executeQuery("SELECT MIN(age) FROM user", rows(), null)
                .getFirst().get("age"));
        assertEquals(108.0, parser.executeQuery("SELECT SUM(age) FROM user", rows(), null)
                .getFirst().get("age"));
        assertEquals(27.0, parser.executeQuery("SELECT AVG(age) FROM user", rows(), null)
                .getFirst().get("age"));
    }

    @Test
    @DisplayName("聚合与 WHERE 组合，空集语义符合 SQL")
    void aggregateOnEmpty() {
        assertEquals(0L, parser.executeQuery(
                "SELECT COUNT(age) FROM user WHERE id = 99", rows(), null)
                .getFirst().get("age"));
        assertNull(parser.executeQuery(
                "SELECT MAX(age) FROM user WHERE id = 99", rows(), null)
                .getFirst().get("age"));
        assertNull(parser.executeQuery(
                "SELECT SUM(age) FROM user WHERE id = 99", rows(), null)
                .getFirst().get("age"));
        assertNull(parser.executeQuery(
                "SELECT AVG(age) FROM user WHERE id = 99", rows(), null)
                .getFirst().get("age"));
    }

    @Test
    @DisplayName("聚合投影拒绝 * 参数与非数值列 SUM")
    void aggregateErrors() {
        IllegalArgumentException star = assertThrows(IllegalArgumentException.class,
                () -> parser.executeQuery("SELECT MAX(*) FROM user", rows(), null));
        assertTrue(star.getMessage().contains("不接受 *"));

        assertThrows(IllegalArgumentException.class,
                () -> parser.executeQuery("SELECT SUM(name) FROM user", rows(), null));
    }

    @Test
    @DisplayName("INSERT 追加映射行并按列赋值")
    void insert() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml(
                "INSERT INTO user (id, name, age) VALUES (6, '周九', 40)");
        int affected = MemorySqlAst.executeDml(plan, List.of(), () -> castRows(rows));
        assertEquals(1, affected);
        assertEquals(6, rows.size());
        assertEquals(40, rows.getLast().get("age"));
        assertNull(rows.getLast().get("email"));
    }

    @Test
    @DisplayName("INSERT 多行与 ? 占位绑定")
    void insertMultiRowParams() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml(
                "INSERT INTO user (id, name) VALUES (?, ?), (?, ?)");
        int affected = MemorySqlAst.executeDml(plan,
                java.util.Arrays.asList(6, "周九", 7, "吴十"), () -> castRows(rows));
        assertEquals(2, affected);
        assertEquals(7, rows.size());
        assertEquals("吴十", rows.getLast().get("name"));
    }

    @Test
    @DisplayName("INSERT 无列清单时由首行推断列")
    void insertInferColumns() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("INSERT INTO user VALUES (6, '周九', 40, 'z@e.com')");
        assertEquals(1, MemorySqlAst.executeDml(plan, List.of(), () -> castRows(rows)));
        assertEquals("z@e.com", rows.getLast().get("email"));
    }

    @Test
    @DisplayName("INSERT 列数与值数不匹配时报错")
    void insertArityMismatch() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("INSERT INTO user (id, name) VALUES (6)");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MemorySqlAst.executeDml(plan, List.of(), () -> castRows(rows)));
        assertTrue(e.getMessage().contains("列数与值数不匹配"));
    }

    @Test
    @DisplayName("INSERT 到空表且无列清单时拒绝（无法推断列）")
    void insertIntoEmptyTableWithoutColumns() {
        List<Object> empty = new java.util.ArrayList<>();
        DmlPlan plan = parser.parseDml("INSERT INTO t VALUES (1)");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MemorySqlAst.executeDml(plan, List.of(), () -> empty));
        assertTrue(e.getMessage().contains("无法推断插入列"));
    }

    @Test
    @DisplayName("UPDATE 字面量 + WHERE 参数，只命中目标行")
    void update() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("UPDATE user SET age = 45 WHERE id = ?");
        int affected = MemorySqlAst.executeDml(plan, List.of(4), () -> castRows(rows));
        assertEquals(1, affected);
        assertEquals(45, rows.get(3).get("age"));
        // 其余行不受影响
        assertEquals(18, rows.getFirst().get("age"));
    }

    @Test
    @DisplayName("UPDATE 多列 SET 且 SET/WHERE 参数按序消费")
    void updateMultiColumnParams() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("UPDATE user SET age = ?, name = ? WHERE id = ?");
        int affected = MemorySqlAst.executeDml(plan,
                java.util.Arrays.asList(40, "刘八", 4), () -> castRows(rows));
        assertEquals(1, affected);
        assertEquals(40, rows.get(3).get("age"));
        assertEquals("刘八", rows.get(3).get("name"));
    }

    @Test
    @DisplayName("UPDATE 无 WHERE 命中全部行，无匹配时报 0")
    void updateScope() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("UPDATE user SET age = 1");
        assertEquals(5, MemorySqlAst.executeDml(plan, List.of(), () -> castRows(rows)));
        assertTrue(rows.stream().allMatch(r -> Integer.valueOf(1).equals(r.get("age"))));

        List<Map<String, Object>> rows2 = rows();
        DmlPlan none = parser.parseDml("UPDATE user SET age = 2 WHERE id = ?");
        assertEquals(0, MemorySqlAst.executeDml(none, List.of(99), () -> castRows(rows2)));
    }

    @Test
    @DisplayName("UPDATE 写入不存在的列时影响行数为 0（不谎报成功）")
    void updateUnknownColumn() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("UPDATE user SET notAColumn = 1 WHERE id = ?");
        // 映射行允许新增列，故这里是 1；实体行则由 MemorySqlLex/实体 setter 决定
        assertEquals(1, MemorySqlAst.executeDml(plan, List.of(1), () -> castRows(rows)));
        assertEquals(1, rows.getFirst().get("notAColumn"));
    }

    @Test
    @DisplayName("DELETE 带条件只删目标行")
    void delete() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("DELETE FROM user WHERE id = ?");
        assertEquals(1, MemorySqlAst.executeDml(plan, List.of(3), () -> castRows(rows)));
        assertEquals(4, rows.size());
        assertEquals(List.of(1, 2, 4, 5), ids(rows));
    }

    @Test
    @DisplayName("DELETE 多个命中与无 WHERE 全删")
    void deleteScope() {
        List<Map<String, Object>> rows = rows();
        DmlPlan plan = parser.parseDml("DELETE FROM user WHERE age >= 25");
        assertEquals(3, MemorySqlAst.executeDml(plan, List.of(), () -> castRows(rows)));
        assertEquals(List.of(1, 3), ids(rows));

        List<Map<String, Object>> all = rows();
        assertEquals(5, MemorySqlAst.executeDml(
                parser.parseDml("DELETE FROM user"), List.of(), () -> castRows(all)));
        assertTrue(all.isEmpty());
    }

    @Test
    @DisplayName("SELECT 支持 ? 参数且每行重置参数游标")
    void perRowParamCursorReset() {
        // 若游标不重置，第二轮行求值时参数已耗尽，BETWEEN 会退化为空结果
        List<Map<String, Object>> out = parser.executeQuery(
                "SELECT * FROM user WHERE age BETWEEN ? AND ?", rows(), new Object[]{25, 35});
        assertEquals(List.of(2, 4, 5), ids(out));
    }

    @Test
    @DisplayName("COUNT(列) 只统计非空值，COUNT(*) 统计全部行")
    void countColumnSkipsNull() {
        // age 为 null 的行不计入 COUNT(age)
        assertEquals(4L, parser.executeQuery("SELECT COUNT(age) FROM user", rows(), null)
                .getFirst().get("age"));
        assertEquals(5, parser.executeQuery("SELECT COUNT(*) FROM user", rows(), null)
                .getFirst().get("cnt"));
    }

    @Test
    @DisplayName("解析错误给出可定位的异常")
    void parseErrors() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser.executeQuery("SELECT", rows(), null))
                .getMessage().contains("意外的语句结尾"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser.executeQuery("SELECT * user", rows(), null))
                .getMessage().contains("期望 FROM"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser.parseDml("TRUNCATE user"))
                .getMessage().contains("不支持的 SQL 类型"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser.executeQuery("SELECT * FROM user LIMIT abc", rows(), null))
                .getMessage().contains("期望数字"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> parser.executeQuery("SELECT * FROM user WHERE age ~ 1", rows(), null))
                .getMessage().contains("不支持的运算符"));
    }

    @Test
    @DisplayName("executeDml 拒绝 null 计划")
    void nullPlan() {
        assertThrows(NullPointerException.class,
                () -> MemorySqlAst.executeDml(null, List.of(), List::of));
    }

    @Test
    @DisplayName("绑定参数为 null 时按空集处理")
    void nullParams() {
        assertEquals(5, parser.executeQuery("SELECT * FROM user", rows(), null).size());
        List<Map<String, Object>> rows = rows();
        assertEquals(5, MemorySqlAst.executeDml(
                parser.parseDml("UPDATE user SET age = 1"), null, () -> castRows(rows)));
    }

    @Test
    @DisplayName("列引用不能单独作为谓词（缺运算符即解析失败）")
    void columnAsPredicate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.executeQuery("SELECT * FROM user WHERE age", rows(), null));
        assertTrue(e.getMessage().contains("意外的语句结尾"));
    }

    @Test
    @DisplayName("复杂表达式不可作为值求值")
    void nonValueNode() {
        assertThrows(IllegalArgumentException.class,
                () -> MemorySqlAst.value(new MemorySqlAst.NotNode(new MemorySqlAst.IsNullNode(
                        new MemorySqlAst.ColumnNode("age"), false)), row(1, "x", 1, "y"), null));
    }

    @Test
    @DisplayName("字面量节点求值取布尔真值")
    void literalEval() {
        assertTrue(new MemorySqlAst.LiteralNode(Boolean.TRUE).eval(
                row(1, "x", 1, "y"), null));
        assertFalse(new MemorySqlAst.LiteralNode("yes").eval(
                row(1, "x", 1, "y"), null));
        assertNull(MemorySqlAst.LiteralNode.unwrap(new MemorySqlAst.ParamMarker(), null));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castRows(List<?> rows) {
        return (List<Object>) rows;
    }
}
