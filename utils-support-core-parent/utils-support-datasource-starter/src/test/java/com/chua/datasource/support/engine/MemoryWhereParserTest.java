// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryWhereParser} 行为约定。
 *
 * <p>覆盖 SQL 风格 WHERE 子句的全部算子、连接符优先级、
 * 括号分组、占位符消费顺序与非法输入的异常语义。</p>
 * @author CH
 * @since 4.0.0.50
 */
class MemoryWhereParserTest {

    private final MemoryWhereParser parser = new MemoryWhereParser();

    private static final List<Row> ROWS = List.of(
            new Row(1, "张三", 18, "zhangsan@example.com"),
            new Row(2, "李四", 25, "lisi@example.com"),
            new Row(3, "王五", null, "wangwu@example.com"),
            new Row(4, "赵六", 30, null),
            new Row(5, "李七", 35, "liqi@example.com"));

    @Test
    @DisplayName("空 WHERE 恒真")
    void emptyWhere() {
        assertTrue(parser.parse(null, List.of()).test(new Row(1, "x", 1, "y")));
        assertTrue(parser.parse("", List.of()).test(new Row(1, "x", 1, "y")));
        assertTrue(parser.parse("   ", List.of()).test(new Row(1, "x", 1, "y")));
        assertTrue(parser.parse(null, null).test(new Row(1, "x", 1, "y")));
    }

    @Test
    @DisplayName("等值与不等值比较")
    void equality() {
        assertEquals(List.of(1), ids(parser.parse("id = ?", List.of(1))));
        assertEquals(List.of(2, 3, 4, 5), ids(parser.parse("id != ?", List.of(1))));
        assertEquals(List.of(2, 3, 4, 5), ids(parser.parse("id <> ?", List.of(1))));
    }

    @Test
    @DisplayName("NULL 参与等值比较不命中（SQL unknown 语义）")
    void nullIsUnknown() {
        // age 为 null 的行既不算 = 25 也不算 != 25
        assertEquals(List.of(2), ids(parser.parse("age = ?", List.of(25))));
        assertEquals(0, ids(parser.parse("age != ?", List.of(25))).stream()
                .filter(i -> i == 3).count());
    }

    @Test
    @DisplayName("大小比较与字符串形态的数值参数")
    void ordering() {
        assertEquals(List.of(2, 4, 5), ids(parser.parse("age > ?", List.of(20))));
        assertEquals(List.of(2, 4, 5), ids(parser.parse("age >= ?", List.of(25))));
        assertEquals(List.of(1, 2), ids(parser.parse("age < ?", List.of(26))));
        assertEquals(List.of(1, 2), ids(parser.parse("age <= ?", List.of(25))));
        // 参数字符串需按字段类型收敛，否则 Integer.compareTo(String) 会抛 CCE
        assertEquals(List.of(2, 4), ids(parser.parse("age >= ? AND age <= ?",
                List.of("25", "30"))));
    }

    @Test
    @DisplayName("类型不匹配的大小比较不抛异常")
    void orderingTypeMismatch() {
        // 字符串列与数值参数：参数被收敛成字符串后比较，不抛 ClassCastException
        Predicate<Row> p = parser.parse("name > ?", List.of(1));
        assertFalse(p.test(new Row(1, null, 1, "x")));
        assertTrue(p.test(new Row(1, "张三", 1, "x")));
    }

    @Test
    @DisplayName("LIKE 三种通配形态")
    void like() {
        assertEquals(List.of(1), ids(parser.parse("name LIKE ?", List.of("张%"))));
        assertEquals(List.of(1), ids(parser.parse("name LIKE ?", List.of("%三"))));
        // 「李四」「李七」含「李」，「张三」不含
        assertEquals(List.of(2, 5), ids(parser.parse("name LIKE ?", List.of("%李%"))));
        assertEquals(List.of(3), ids(parser.parse("name LIKE ?", List.of("%_五"))));
        assertEquals(List.of(), ids(parser.parse("name LIKE ?", List.of("%黄%"))));
        // 模式为 null 时恒不命中
        assertEquals(List.of(), ids(parser.parse("name LIKE ?",
                java.util.Collections.singletonList(null))));
    }

    @Test
    @DisplayName("BETWEEN 闭区间")
    void between() {
        assertEquals(List.of(2, 4), ids(parser.parse("age BETWEEN ? AND ?",
                List.of(25, 30))));
        assertEquals(List.of(1, 2), ids(parser.parse("age BETWEEN ? AND ?",
                List.of(18, 25))));
        assertEquals(List.of(), ids(parser.parse("age BETWEEN ? AND ?", List.of(5, 10))));
    }

    @Test
    @DisplayName("BETWEEN 边界为 NULL 时不命中")
    void betweenNullBounds() {
        assertEquals(List.of(), ids(parser.parse("age BETWEEN ? AND ?",
                java.util.Arrays.asList(null, null))));
    }

    @Test
    @DisplayName("BETWEEN 遇不可比较字段不抛异常")
    void betweenNonComparable() {
        assertEquals(List.of(), ids(parser.parse("name BETWEEN ? AND ?", List.of(18, 20))));
    }

    @Test
    @DisplayName("IN 与 NOT IN")
    void in() {
        assertEquals(List.of(1, 3), ids(parser.parse("id IN (?, ?)", List.of(1, 3))));
        assertEquals(List.of(2, 4, 5), ids(parser.parse("id NOT IN (?, ?)", List.of(1, 3))));
        assertEquals(List.of(), ids(parser.parse("id IN (?)",
                java.util.Collections.singletonList(null))));
    }

    @Test
    @DisplayName("IS NULL 与 IS NOT NULL")
    void isNull() {
        assertEquals(List.of(3), ids(parser.parse("age IS NULL", List.of())));
        assertEquals(List.of(1, 2, 4, 5), ids(parser.parse("age IS NOT NULL", List.of())));
        assertEquals(List.of(4), ids(parser.parse("email IS NULL", List.of())));
    }

    @Test
    @DisplayName("AND 与 OR 的优先级：AND 高于 OR")
    void andOrPrecedence() {
        // id = 1 OR id = 2 AND age = 25 等价于 id=1 OR (id=2 AND age=25)
        assertEquals(List.of(1, 2), ids(parser.parse(
                "id = ? OR id = ? AND age = ?", List.of(1, 2, 25))));
        assertEquals(List.of(1), ids(parser.parse(
                "id = ? OR id = ? AND age = ?", List.of(1, 2, 99))));
    }

    @Test
    @DisplayName("括号分组改变求值优先级")
    void parentheses() {
        assertEquals(List.of(2), ids(parser.parse(
                "(id = ? OR id = ?) AND age = ?", List.of(1, 2, 25))));
        assertEquals(List.of(), ids(parser.parse(
                "(id = ? OR id = ?) AND age = ?", List.of(1, 2, 99))));
        // 嵌套括号
        assertEquals(List.of(1), ids(parser.parse(
                "((id = ?))", List.of(1))));
    }

    @Test
    @DisplayName("LIKE / BETWEEN 位于子句开头时会丢弃后续条件（回归）")
    void nonTerminalPredicatesKeepRemainingClauses() {
        // 曾因整串匹配，name LIKE ? AND id = ? 落到 break，谓词退化成恒真
        assertEquals(List.of(1), ids(parser.parse("name LIKE ? AND id = ?",
                List.of("%张%", 1))));
        assertEquals(List.of(2), ids(parser.parse("age BETWEEN ? AND ? AND id = ?",
                List.of(20, 30, 2))));
        assertEquals(List.of(2), ids(parser.parse("id IN (?, ?) AND name LIKE ?",
                List.of(1, 2, "%李%"))));
        assertEquals(List.of(1), ids(parser.parse("age IS NOT NULL AND id = ?", List.of(1))));
        // 行 4 的 email 为 null，行 3 的 id 为 3，两者都应命中
        assertEquals(List.of(3, 4), ids(parser.parse("email IS NULL OR id = ?", List.of(3))));
    }

    @Test
    @DisplayName("数值常量条件（空集合 IN 渲染后的 1 = 0 / 1 = 1）")
    void constantConditions() {
        assertEquals(List.of(), ids(parser.parse("1 = 0", List.of())));
        assertEquals(ids(parser.parse("", List.of())), ids(parser.parse("1 = 1", List.of())));
        assertEquals(List.of(), ids(parser.parse("1 != 1", List.of())));
        assertEquals(List.of(), ids(parser.parse("1 > 2", List.of())));
        assertEquals(List.of(), ids(parser.parse("1 >= 2", List.of())));
        assertEquals(List.of(), ids(parser.parse("2 < 1", List.of())));
        assertEquals(List.of(), ids(parser.parse("2 <= 1", List.of())));
        assertEquals(ids(parser.parse("", List.of())), ids(parser.parse("2 >= 1", List.of())));
        assertEquals(ids(parser.parse("", List.of())), ids(parser.parse("1 <> 2", List.of())));
    }

    @Test
    @DisplayName("表别名限定的列名取列名本身")
    void qualifiedColumn() {
        assertEquals(List.of(2, 5), ids(parser.parse("u.name LIKE ?", List.of("%李%"))));
        assertEquals(List.of(2), ids(parser.parse("t.age = ?", List.of(25))));
    }

    @Test
    @DisplayName("AND/OR 关键字必须独立成词，不被当作连接符")
    void keywordBoundaries() {
        // androidCode 以 AND 开头但不是连接符：被当作独立列名解析，
        // 该列不存在 → 条件不命中（若误判为连接符，就会把后半段当新条件）
        assertEquals(List.of(), ids(parser.parse("id = ? AND androidCode = ?", List.of(1, 1))));
        assertEquals(List.of(), ids(parser.parse("id = ? ANDROID = ?", List.of(1, 1))));
        // orCode 同理，不得被当作 OR
        assertEquals(List.of(), ids(parser.parse("id = ? orCode = ?", List.of(1, 1))));
    }

    @Test
    @DisplayName("占位符多于参数时抛出可定位异常")
    void missingParams() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> parser.parse("id = ? AND age = ?", List.of(1))
                        .test(new Row(1, "x", 1, "y")));
        assertTrue(e.getMessage().contains("WHERE 占位符数量超过参数个数"));
    }

    @Test
    @DisplayName("无法解析的片段抛出带原文的异常")
    void unparsableFragment() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("id ~ ?", List.of(1)));
        assertTrue(e.getMessage().contains("无法解析的 WHERE 片段"));
    }

    @Test
    @DisplayName("括号未闭合抛出异常")
    void unclosedParenthesis() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("(id = ?", List.of(1)));
        assertTrue(e.getMessage().contains("WHERE 括号未闭合"));
    }

    @Test
    @DisplayName("未知列名不抛异常，条件视为不命中")
    void unknownColumn() {
        assertEquals(List.of(), ids(parser.parse("unknownColumn = ?", List.of(1))));
        assertEquals(ids(parser.parse("", List.of())),
                ids(parser.parse("unknownColumn IS NULL", List.of())));
    }

    private static List<Integer> ids(Predicate<Row> predicate) {
        return ROWS.stream().filter(predicate).map(Row::getId).toList();
    }

    /**
     * 便于反射取值的行实体
     */
    public static class Row {
        private final Integer id;
        private final String name;
        private final Integer age;
        private final String email;

        /**
         * 构造行实体。
         *
         * @param id    主键
         * @param name  姓名
         * @param age   年龄
         * @param email 邮箱
         */
        Row(Integer id, String name, Integer age, String email) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.email = email;
        }

        /**
         * 主键。
         *
         * @return 主键值
         */
        public Integer getId() {
            return id;
        }

        /**
         * 姓名。
         *
         * @return 姓名
         */
        public String getName() {
            return name;
        }

        /**
         * 年龄。
         *
         * @return 年龄
         */
        public Integer getAge() {
            return age;
        }

        /**
         * 邮箱。
         *
         * @return 邮箱
         */
        public String getEmail() {
            return email;
        }
    }
}