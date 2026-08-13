package com.chua.common.support.reflection;

import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldStation 单元测试：覆盖 camelCase 转换、字段读写、缓存、继承链查找等核心场景。
 *
 * @author CH
 */
class FieldStationTest {

    @Data
    static class TestBean {
        private boolean checkCodeOpen;
        private String systemName;
        private int sysUserId;
        private Integer loginCount;
    }

    @Data
    static class ChildBean extends TestBean {
        private boolean childOnly;
    }

    @Test
    @DisplayName("camelCase 转换：首字母大写转小写")
    void testCamelCase() {
        FieldStation station = FieldStation.of(new TestBean());
        // PascalCase -> camelCase
        assertTrue((Boolean) station.getValue("CheckCodeOpen"));
    }

    @Test
    @DisplayName("首字母已小写：不做转换")
    void testAlreadyLowerCase() {
        FieldBean bean = new FieldBean();
        bean.systemName = "test";
        FieldStation station = FieldStation.of(bean);
        assertEquals("test", station.getValue("systemName"));
    }

    @Test
    @DisplayName("写入：PascalCase 自动转换")
    void testSetPascalCase() {
        TestBean bean = new TestBean();
        FieldStation.of(bean).setIgnoreNameValue("CheckCodeOpen", true);
        assertTrue(bean.isCheckCodeOpen());

        FieldStation.of(bean).setIgnoreNameValue("SystemName", "ADMIN");
        assertEquals("ADMIN", bean.getSystemName());
    }

    @Test
    @DisplayName("写入：小写开头正常处理")
    void testSetCamelCase() {
        TestBean bean = new TestBean();
        FieldStation.of(bean).setIgnoreNameValue("loginCount", 42);
        assertEquals(42, bean.getLoginCount());
    }

    @Test
    @DisplayName("读取不存在的字段：返回 null 不抛异常")
    void testGetNonExistent() {
        FieldStation station = FieldStation.of(new TestBean());
        assertNull(station.getValue("nonExistentField"));
        assertNull(station.getValue("CheckNonExistent"));
    }

    @Test
    @DisplayName("写入不存在的字段：静默跳过（FieldStation 内部 catch 异常）")
    void testSetNonExistent() {
        TestBean bean = new TestBean();
        // 不存在的字段不会抛异常，也不会改变 bean
        FieldStation.of(bean).setIgnoreNameValue("nonExistentField", "value");
        assertFalse(bean.isCheckCodeOpen());
        assertNull(bean.getSystemName());
    }

    @Test
    @DisplayName("null 值写入：字段被设为 null")
    void testSetNull() {
        TestBean bean = new TestBean();
        bean.setSystemName("INITIAL");
        FieldStation.of(bean).setIgnoreNameValue("systemName", null);
        assertNull(bean.getSystemName());
    }

    @Test
    @DisplayName("继承链查找：父类字段")
    void testInheritedField() {
        ChildBean bean = new ChildBean();
        bean.setCheckCodeOpen(true);
        FieldStation station = FieldStation.of(bean);
        assertTrue((Boolean) station.getValue("CheckCodeOpen"));
        assertFalse((Boolean) station.getValue("ChildOnly"));
    }

    @Test
    @DisplayName("继承链写入：父类字段")
    void testSetInheritedField() {
        ChildBean bean = new ChildBean();
        FieldStation.of(bean).setIgnoreNameValue("SystemName", "CHILD");
        assertEquals("CHILD", bean.getSystemName());
    }

    @Test
    @DisplayName("of(null)：返回 null 查找的 Station")
    void testOfNull() {
        FieldStation station = FieldStation.of((Object) null);
        // 不应该抛 NullPointerException
        assertNull(station.getValue("anything"));
    }

    @Test
    @DisplayName("of(Class)：按类访问，instance 为 null")
    void testOfClass() {
        FieldStation station = FieldStation.of(TestBean.class);
        // instance 为 null，getValue 应当返回 null 而不抛异常
        assertNull(station.getValue("CheckCodeOpen"));
    }

    @Test
    @DisplayName("缓存命中：同一字段多次访问只反射一次")
    void testCacheEffectiveness() {
        TestBean bean1 = new TestBean();
        TestBean bean2 = new TestBean();
        // 两次访问应共享同一 Field 对象（通过 FIELD_CACHE）
        Field field1 = FieldStation.of(bean1).findFieldForTest(TestBean.class, "checkCodeOpen");
        Field field2 = FieldStation.of(bean2).findFieldForTest(TestBean.class, "checkCodeOpen");
        assertSame(field1, field2, "缓存命中应返回同一 Field 实例");
    }

    @Data
    static class FieldBean {
        private String systemName;
    }
}
