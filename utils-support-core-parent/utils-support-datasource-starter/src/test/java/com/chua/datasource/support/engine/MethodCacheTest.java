// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.datasource.support.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MethodCache} 行为约定。
 *
 * <p>本类是内存/文件引擎全部「按列名读写实体属性」的底层通道：
 * 读路径决定类型化查询能否取到值，写路径决定 UPDATE 是否真正落值。
 * 因此断言集中在三件事上：</p>
 * <ul>
 *   <li>getter 查找覆盖 {@code getXxx} / {@code isXxx} / 无 getter 直接读字段</li>
 *   <li>setter 写入必须把 {@link Long} 这类 SQL 字面量收窄到目标形参类型</li>
 *   <li>缺失属性走负缓存并返回 {@code null} / 静默无操作，不抛异常</li>
 * </ul>
 */
class MethodCacheTest {

    @Test
    @DisplayName("getValue 支持 getXxx 与 isXxx 两种 getter")
    void getValueGetters() {
        Sample bean = new Sample();
        bean.setId(7);
        bean.setName("张三");
        bean.setActive(true);

        assertEquals(7, MethodCache.getValue(bean, "id"));
        assertEquals("张三", MethodCache.getValue(bean, "name"));
        assertEquals(true, MethodCache.getValue(bean, "active"));
    }

    @Test
    @DisplayName("getValue 在无 getter 时直接读取字段")
    void getValueFieldFallback() {
        FieldOnly bean = new FieldOnly();
        bean.value = 9;
        assertEquals(9, MethodCache.getValue(bean, "value"));
        // 缺失 getter 的属性被负缓存后再次读取仍是字段值（不影响兜底路径）
        assertEquals(9, MethodCache.getValue(bean, "value"));
    }

    @Test
    @DisplayName("getValue 对不存在的属性返回 null 而不是抛异常")
    void getValueMissing() {
        assertNull(MethodCache.getValue(new Sample(), "noSuchField"));
        // 负缓存命中后再次调用仍是 null（不抛、不重扫）
        assertNull(MethodCache.getValue(new Sample(), "noSuchField"));
    }

    @Test
    @DisplayName("setValue 把 Long 字面量收窄到 Integer 形参")
    void setValueNarrowsLongToInteger() {
        Sample bean = new Sample();
        MethodCache.setValue(bean, "id", 45L);
        assertEquals(45, bean.getId());
    }

    @Test
    @DisplayName("setValue 覆盖全部数值基本类型与布尔、字符")
    void setValueCoercions() {
        Wide bean = new Wide();

        MethodCache.setValue(bean, "intValue", 1L);
        MethodCache.setValue(bean, "longValue", 2);
        MethodCache.setValue(bean, "doubleValue", 3);
        MethodCache.setValue(bean, "floatValue", 4L);
        MethodCache.setValue(bean, "shortValue", 5);
        MethodCache.setValue(bean, "byteValue", 6);
        MethodCache.setValue(bean, "booleanValue", "true");
        MethodCache.setValue(bean, "charValue", "x");

        assertEquals(1, bean.getIntValue());
        assertEquals(2L, bean.getLongValue());
        assertEquals(3.0d, bean.getDoubleValue());
        assertEquals(4.0f, bean.getFloatValue());
        assertEquals((short) 5, bean.getShortValue());
        assertEquals((byte) 6, bean.getByteValue());
        assertTrue(bean.isBooleanValue());
        assertEquals('x', bean.getCharValue());
    }

    @Test
    @DisplayName("setValue 接受数字字符串并转成目标数值类型")
    void setValueFromString() {
        Wide bean = new Wide();
        MethodCache.setValue(bean, "intValue", "12");
        MethodCache.setValue(bean, "doubleValue", "1.5");
        assertEquals(12, bean.getIntValue());
        assertEquals(1.5d, bean.getDoubleValue());
    }

    @Test
    @DisplayName("setValue 字符串列写入非字符串值转为 toString")
    void setValueToString() {
        Sample bean = new Sample();
        MethodCache.setValue(bean, "name", 123);
        assertEquals("123", bean.getName());
    }

    @Test
    @DisplayName("setValue 支持下划线列名转驼峰属性名")
    void setValueUnderscoreColumn() {
        Sample bean = new Sample();
        MethodCache.setValue(bean, "user_name", "李四");
        assertEquals("李四", bean.getUserName());
        assertEquals("李四", MethodCache.getValue(bean, "user_name"));
    }

    @Test
    @DisplayName("setValue 对不存在属性静默无操作，不抛异常")
    void setValueMissing() {
        Sample bean = new Sample();
        bean.setId(1);
        MethodCache.setValue(bean, "noSuchField", 2);
        assertEquals(1, bean.getId());
    }

    @Test
    @DisplayName("setValue 无 setter 时直接写字段")
    void setValueFieldFallback() {
        FieldOnly bean = new FieldOnly();
        MethodCache.setValue(bean, "value", 42L);
        assertEquals(42, bean.readValue());
    }

    @Test
    @DisplayName("不可收敛的文本不会污染已有值")
    void setValueUncoercibleText() {
        Wide bean = new Wide();
        bean.setIntValue(5);
        // "abc" 无法转成 int：coerce 原样返回，MethodHandle 调用抛错后被吞掉，
        // 形参收窄失败不能把字段写成 0 或抛给调用方
        MethodCache.setValue(bean, "intValue", "abc");
        assertEquals(5, bean.getIntValue());
    }

    @Test
    @DisplayName("getValue 读回集合类型不做任何转换")
    void getValueCollection() {
        Sample bean = new Sample();
        bean.setTags(java.util.List.of("a", "b"));
        Object value = MethodCache.getValue(bean, "tags");
        assertInstanceOf(java.util.List.class, value);
        assertEquals(2, ((java.util.List<?>) value).size());
    }

    /**
     * 常规实体：getter/setter 使用包装类型。
     */
    public static class Sample {
        private Integer id;
        private String name;
        private boolean active;
        private String userName;
        private java.util.List<String> tags;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public java.util.List<String> getTags() {
            return tags;
        }

        public void setTags(java.util.List<String> tags) {
            this.tags = tags;
        }
    }

    /**
     * 覆盖全部基本类型的 setter 载体。
     */
    public static class Wide {
        private int intValue;
        private long longValue;
        private double doubleValue;
        private float floatValue;
        private short shortValue;
        private byte byteValue;
        private boolean booleanValue;
        private char charValue;

        public int getIntValue() {
            return intValue;
        }

        public void setIntValue(int intValue) {
            this.intValue = intValue;
        }

        public long getLongValue() {
            return longValue;
        }

        public void setLongValue(long longValue) {
            this.longValue = longValue;
        }

        public double getDoubleValue() {
            return doubleValue;
        }

        public void setDoubleValue(double doubleValue) {
            this.doubleValue = doubleValue;
        }

        public float getFloatValue() {
            return floatValue;
        }

        public void setFloatValue(float floatValue) {
            this.floatValue = floatValue;
        }

        public short getShortValue() {
            return shortValue;
        }

        public void setShortValue(short shortValue) {
            this.shortValue = shortValue;
        }

        public byte getByteValue() {
            return byteValue;
        }

        public void setByteValue(byte byteValue) {
            this.byteValue = byteValue;
        }

        public boolean isBooleanValue() {
            return booleanValue;
        }

        public void setBooleanValue(boolean booleanValue) {
            this.booleanValue = booleanValue;
        }

        public char getCharValue() {
            return charValue;
        }

        public void setCharValue(char charValue) {
            this.charValue = charValue;
        }
    }

    /**
     * 只有字段、没有任何访问器的 POJO。
     */
    public static class FieldOnly {
        private int value;

        int readValue() {
            return value;
        }
    }}