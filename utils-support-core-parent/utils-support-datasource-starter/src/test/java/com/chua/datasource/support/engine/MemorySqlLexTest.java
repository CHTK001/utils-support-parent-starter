// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemorySqlLex} 行为约定。
 *
 * <p>{@code like} 是全包唯一的 SQL LIKE 实现，其余调用方（包装器条件、
 * WHERE 解析器、AST LIKE 节点）都复用它，因此这里对通配语义做完整约定：
 * {@code %} 匹配任意序列（含空串）、{@code _} 匹配恰好一个字符、大小写不敏感、
 * 空值恒假。</p>
 *
 * <p>{@link MemorySqlLex.RowAccessor} 同时服务 Map 行与 Bean 行两种行模型，
 * 读取需忽略列名大小写、写入需按 setter 形参类型收敛。</p>
 * @author CH
 * @since 4.0.0.50
 */
class MemorySqlLexTest {

    @Test
    @DisplayName("LIKE：% 匹配任意序列（含空串）")
    void likeStar() {
        assertTrue(MemorySqlLex.like("abc", "%"));
        assertTrue(MemorySqlLex.like("", "%"));
        assertTrue(MemorySqlLex.like("abc", "%%"));
        assertTrue(MemorySqlLex.like("abc", "a%"));
        assertTrue(MemorySqlLex.like("abc", "%c"));
        assertTrue(MemorySqlLex.like("abc", "%b%"));
        assertTrue(MemorySqlLex.like("abc", "a%c"));
        assertTrue(MemorySqlLex.like("abc", "abc%"));
        assertTrue(MemorySqlLex.like("abc", "%abc"));
        assertTrue(MemorySqlLex.like("abc", "abc"));
    }

    @Test
    @DisplayName("LIKE：多个 % 需要回溯（%x%% 形态）")
    void likeBacktracking() {
        // 包装器会把用户输入再包一层 %：like("三") → "%三%"，
        // 用户自己写 "%三" 则变成 "%%三%" —— 首字符即失配后必须回溯，不能提前返回 false
        assertTrue(MemorySqlLex.like("张三", "%%三%"));
        assertTrue(MemorySqlLex.like("张三", "%%%三%%%"));
        assertTrue(MemorySqlLex.like("aXbXc", "%X%X%"));
        assertTrue(MemorySqlLex.like("aaab", "%a%b"));
        assertFalse(MemorySqlLex.like("aaab", "%a%c"));
        assertFalse(MemorySqlLex.like("abc", "a%c%c"));
    }

    @Test
    @DisplayName("LIKE：_ 匹配恰好一个字符")
    void likeUnderscore() {
        assertTrue(MemorySqlLex.like("abc", "a_c"));
        assertTrue(MemorySqlLex.like("abc", "___"));
        assertTrue(MemorySqlLex.like("a五c", "a_c"));
        assertFalse(MemorySqlLex.like("abc", "__"));
        assertFalse(MemorySqlLex.like("ab", "___"));
        assertFalse(MemorySqlLex.like("abc", "a_"));
        assertTrue(MemorySqlLex.like("王五", "_五"));
        assertTrue(MemorySqlLex.like("王五", "%_五"));
    }

    @Test
    @DisplayName("LIKE：大小写不敏感")
    void likeCaseInsensitive() {
        assertTrue(MemorySqlLex.like("Hello", "hello"));
        assertTrue(MemorySqlLex.like("HELLO", "%ell%"));
        assertTrue(MemorySqlLex.like("hello", "H%"));
        assertFalse(MemorySqlLex.like("hello", "HELLO!"));
    }

    @Test
    @DisplayName("LIKE：空值恒假")
    void likeNull() {
        assertFalse(MemorySqlLex.like(null, "%"));
        assertFalse(MemorySqlLex.like("abc", null));
        assertFalse(MemorySqlLex.like(null, null));
    }

    @Test
    @DisplayName("LIKE：整串匹配而非子串包含")
    void likeAnchored() {
        assertFalse(MemorySqlLex.like("abcdef", "abc"));
        assertFalse(MemorySqlLex.like("abcdef", "def"));
        assertTrue(MemorySqlLex.like("abcdef", "abc%"));
        assertTrue(MemorySqlLex.like("abcdef", "%def"));
    }

    @Test
    @DisplayName("TokenStream：切分标识符/数字/字符串/运算符/符号")
    void tokenize() {
        MemorySqlLex.TokenStream ts = new MemorySqlLex.TokenStream(
                "SELECT * FROM t WHERE a >= 1 AND b <> 'x y' LIMIT 2 OFFSET 3");
        assertEquals("SELECT", ts.next());
        assertEquals("*", ts.next());
        assertEquals("FROM", ts.next());
        assertEquals("t", ts.next());
        assertEquals("WHERE", ts.next());
        assertEquals("a", ts.next());
        assertEquals(">=", ts.next());
        assertEquals("1", ts.next());
        assertEquals("AND", ts.next());
        assertEquals("b", ts.next());
        assertEquals("<>", ts.next());
        assertEquals("'x y'", ts.next());
        assertEquals("LIMIT", ts.next());
        assertEquals("2", ts.next());
        assertEquals("OFFSET", ts.next());
        assertEquals("3", ts.next());
        assertTrue(ts.eof());
    }

    @Test
    @DisplayName("TokenStream：peek 不消费、next 越界抛异常")
    void tokenStreamCursor() {
        MemorySqlLex.TokenStream ts = new MemorySqlLex.TokenStream("a b");
        assertEquals("a", ts.peek());
        assertEquals("a", ts.peek());
        assertEquals("a", ts.next());
        assertEquals("b", ts.next());
        assertTrue(ts.eof());
        assertThrows(IllegalArgumentException.class, ts::next);
        assertThrows(IllegalArgumentException.class, ts::peek);
    }

    @Test
    @DisplayName("TokenStream：未闭合字符串报错")
    void tokenizeUnclosedString() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new MemorySqlLex.TokenStream("WHERE a = 'abc"));
        assertTrue(e.getMessage().contains("字符串未闭合"));
    }

    @Test
    @DisplayName("TokenStream：<=、>=、<>= 与单字符运算符边界")
    void tokenizeOperators() {
        MemorySqlLex.TokenStream ts = new MemorySqlLex.TokenStream("a<=b>=c<>d=e<f>g");
        assertEquals("a", ts.next());
        assertEquals("<=", ts.next());
        assertEquals("b", ts.next());
        assertEquals(">=", ts.next());
        assertEquals("c", ts.next());
        assertEquals("<>", ts.next());
        assertEquals("d", ts.next());
        assertEquals("=", ts.next());
        assertEquals("e", ts.next());
        assertEquals("<", ts.next());
        assertEquals("f", ts.next());
        assertEquals(">", ts.next());
        assertEquals("g", ts.next());
        assertTrue(ts.eof());
    }

    @Test
    @DisplayName("RowAccessor：Map 行按列名读取（忽略大小写）")
    void rowAccessorMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "张三");
        row.put("Age", 18);
        assertEquals("张三", MemorySqlLex.RowAccessor.value(row, "name"));
        assertEquals("张三", MemorySqlLex.RowAccessor.value(row, "NAME"));
        assertEquals(18, MemorySqlLex.RowAccessor.value(row, "age"));
        assertEquals(18, MemorySqlLex.RowAccessor.value(row, "AGE"));
        assertNull(MemorySqlLex.RowAccessor.value(row, "missing"));
    }

    @Test
    @DisplayName("RowAccessor：Bean 行按 getter 读取")
    void rowAccessorBean() {
        Bean bean = new Bean(7, "李四", 25);
        assertEquals(7, MemorySqlLex.RowAccessor.value(bean, "id"));
        assertEquals("李四", MemorySqlLex.RowAccessor.value(bean, "name"));
        assertEquals(25, MemorySqlLex.RowAccessor.value(bean, "age"));
        assertEquals(25, MemorySqlLex.RowAccessor.value(bean, "Age"));
        assertNull(MemorySqlLex.RowAccessor.value(bean, "missing"));
    }

    @Test
    @DisplayName("RowAccessor：allColumns 投影 Map 行保持原有列名")
    void allColumnsMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("name", "张三");
        Map<String, Object> cols = MemorySqlLex.RowAccessor.allColumns(row);
        assertEquals(Map.of("id", 1, "name", "张三"), cols);
    }

    @Test
    @DisplayName("RowAccessor：allColumns 投影 Bean 行为去前缀的列名")
    void allColumnsBean() {
        Map<String, Object> cols = MemorySqlLex.RowAccessor.allColumns(new Bean(1, "张三", 18));
        assertEquals(1, cols.get("id"));
        assertEquals("张三", cols.get("name"));
        assertEquals(18, cols.get("age"));
    }

    @Test
    @DisplayName("RowAccessor：Map 行写入忽略大小写覆盖同名键")
    void setValueMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Age", 18);
        assertTrue(MemorySqlLex.RowAccessor.setValue(row, "age", 30));
        assertEquals(1, row.size());
        assertEquals(30, row.get("Age"));
        // 不存在的列直接新增
        assertTrue(MemorySqlLex.RowAccessor.setValue(row, "name", "张三"));
        assertEquals("张三", row.get("name"));
    }

    @Test
    @DisplayName("RowAccessor：Bean 行写入按 setter 形参类型收敛")
    void setValueBean() {
        Bean bean = new Bean(1, "张三", 18);
        // Long → Integer 的收窄是 SQL 字面量的常态（字面量解析为 Long）
        assertTrue(MemorySqlLex.RowAccessor.setValue(bean, "age", 45L));
        assertEquals(45, bean.getAge());
        assertTrue(MemorySqlLex.RowAccessor.setValue(bean, "name", "李四"));
        assertEquals("李四", bean.getName());
        // 无对应 setter 时返回 false 而不是抛异常
        assertFalse(MemorySqlLex.RowAccessor.setValue(bean, "missing", 1));
    }

    @Test
    @DisplayName("RowAccessor：toBean 把投影行还原成实体")
    void toBean() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9);
        row.put("name", "赵六");
        row.put("age", 30L);
        Bean bean = MemorySqlLex.RowAccessor.toBean(row, Bean.class);
        assertEquals(9, bean.getId());
        assertEquals("赵六", bean.getName());
        assertEquals(30, bean.getAge());
    }

    /**
     * 供行访问器测试用的简单 Bean。
     */
    public static class Bean {
        private Integer id;
        private String name;
        private Integer age;

        /**
         * 无参构造（供反射实例化）。
         */
        public Bean() {
        }

        /**
         * 构造 Bean。
         *
         * @param id   主键
         * @param name 姓名
         * @param age  年龄
         */
        Bean(Integer id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
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
         * 设置主键。
         *
         * @param id 主键值
         */
        public void setId(Integer id) {
            this.id = id;
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
         * 设置姓名。
         *
         * @param name 姓名
         */
        public void setName(String name) {
            this.name = name;
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
         * 设置年龄。
         *
         * @param age 年龄
         */
        public void setAge(Integer age) {
            this.age = age;
        }
    }
}