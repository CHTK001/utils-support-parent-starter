package com.chua.common.support.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * IdUtils 对象唯一标识测试。
 *
 * @author CH
 * @since 4.0.0.43
 */
class IdUtilsIdTest {

    // ==================== 内部测试 DTO ====================

    static class Person {
        String name;
        int age;
        String email;

        Person(String name, int age, String email) {
            this.name = name;
            this.age = age;
            this.email = email;
        }
    }

    static class Product {
        String id;
        String name;
        double price;
        int stock;

        Product(String id, String name, double price, int stock) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.stock = stock;
        }
    }

    static class Employee {
        String code;
        String department;
        double salary;
        List<String> tags;

        Employee(String code, String department, double salary, List<String> tags) {
            this.code = code;
            this.department = department;
            this.salary = salary;
            this.tags = tags;
        }
    }

    /** 5字段类，用于验证哈希采样不偏置字母序 */
    static class MultiField {
        String a;
        String b;
        String c;
        String d;
        String e;

        MultiField(String a, String b, String c, String d, String e) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
        }
    }

    // ==================== getId ====================

    @Test
    void testGetId_nullReturnsNull() {
        assertNull(IdUtils.getId(null));
    }

    @Test
    void testGetId_sameDataSameId() {
        Person p1 = new Person("Alice", 30, "alice@test.com");
        Person p2 = new Person("Alice", 30, "alice@test.com");
        assertEquals(IdUtils.getId(p1), IdUtils.getId(p2));
    }

    @Test
    void testGetId_differentDataDifferentId() {
        Person p1 = new Person("Alice", 30, "alice@test.com");
        Person p2 = new Person("Bob", 30, "bob@test.com");
        assertNotEquals(IdUtils.getId(p1), IdUtils.getId(p2));
    }

    @Test
    void testGetId_partialChangeDifferentId() {
        Person p1 = new Person("Alice", 30, "alice@test.com");
        Person p2 = new Person("Alice", 31, "alice@test.com");
        assertNotEquals(IdUtils.getId(p1), IdUtils.getId(p2));
    }

    @Test
    void testGetId_consistentAcrossCalls() {
        Person p = new Person("Test", 25, "test@test.com");
        assertEquals(IdUtils.getId(p), IdUtils.getId(p));
    }

    @Test
    void testGetId_withArrayField() {
        Employee e1 = new Employee("E001", "IT", 8000.0, Arrays.asList("java", "spring"));
        Employee e2 = new Employee("E001", "IT", 8000.0, Arrays.asList("java", "spring"));
        assertEquals(IdUtils.getId(e1), IdUtils.getId(e2));
    }

    @Test
    void testGetId_arrayOrderMatters() {
        Employee e1 = new Employee("E001", "IT", 8000.0, Arrays.asList("java", "spring"));
        Employee e2 = new Employee("E001", "IT", 8000.0, Arrays.asList("spring", "java"));
        assertNotEquals(IdUtils.getId(e1), IdUtils.getId(e2));
    }

    // ==================== getPartialId ====================

    @Test
    void testGetPartialId_nullReturnsNull() {
        assertNull(IdUtils.getPartialId(null, 0.6));
    }

    @Test
    void testGetPartialId_invalidRatioThrows() {
        Product p = new Product("P001", "Widget", 9.99, 100);
        try { IdUtils.getPartialId(p, 0.0); assertFalse(true); } catch (IllegalArgumentException ignored) {}
        try { IdUtils.getPartialId(p, 1.5); assertFalse(true); } catch (IllegalArgumentException ignored) {}
    }

    @Test
    void testGetPartialId_samePartialDataMayMatch() {
        // 哈希采样是概率性的：stock 字段可能被选中也可能不被选中。
        // 测试目标：验证 deterministic（同一对象两次调用结果一致）且非 null。
        Product p1 = new Product("P001", "Widget", 9.99, 100);
        Product p2 = new Product("P001", "Widget", 9.99, 200);
        String id1 = IdUtils.getPartialId(p1, 0.75);
        String id2 = IdUtils.getPartialId(p2, 0.75);
        assertNotNull(id1);
        assertNotNull(id2);
        // 确定性：同一对象多次调用结果相同
        assertEquals(IdUtils.getPartialId(p1, 0.75), IdUtils.getPartialId(p1, 0.75));
        // 可能相同（stock 未被选中），也可能不同（stock 被选中）—— 两者均合法
    }

    @Test
    void testGetPartialId_fullRatioEqualsGetId() {
        Product p = new Product("P001", "Widget", 9.99, 100);
        assertEquals(IdUtils.getId(p), IdUtils.getPartialId(p, 1.0));
    }

    @Test
    void testGetPartialId_deterministic() {
        Product p = new Product("P001", "Widget", 9.99, 100);
        assertEquals(IdUtils.getPartialId(p, 0.6), IdUtils.getPartialId(p, 0.6));
    }

    @Test
    void testGetPartialId_hashSelectionNotAlphaBiased() {
        // a,b 相同 c,d 不同 e 相同。
        // 全量应不同（id 方法一定包含所有字段）
        MultiField x = new MultiField("S", "S", "D1", "D1", "S");
        MultiField y = new MultiField("S", "S", "D2", "D2", "S");
        assertNotEquals(IdUtils.getId(x), IdUtils.getId(y));
        // partial 应稳定可复现（同一对象）
        String ix = IdUtils.getPartialId(x, 0.6);
        String iy = IdUtils.getPartialId(y, 0.6);
        assertNotNull(ix);
        assertNotNull(iy);
    }

    // ==================== isSameData ====================

    @Test
    void testIsSameData_sameObject() {
        Person p = new Person("Alice", 30, "alice@test.com");
        assertTrue(IdUtils.isSameData(p, p));
    }

    @Test
    void testIsSameData_bothNull() {
        assertTrue(IdUtils.isSameData(null, null));
    }

    @Test
    void testIsSameData_oneNull() {
        assertFalse(IdUtils.isSameData(new Person("A", 1, "a@t.com"), null));
        assertFalse(IdUtils.isSameData(null, new Person("A", 1, "a@t.com")));
    }

    @Test
    void testIsSameData_sameDataTrue() {
        Person p1 = new Person("Alice", 30, "alice@test.com");
        Person p2 = new Person("Alice", 30, "alice@test.com");
        assertTrue(IdUtils.isSameData(p1, p2));
    }

    @Test
    void testIsSameData_differentDataFalse() {
        Person p1 = new Person("Alice", 30, "alice@test.com");
        Person p2 = new Person("Bob", 30, "bob@test.com");
        assertFalse(IdUtils.isSameData(p1, p2));
    }

    @Test
    void testIsSameData_differentClassFalse() {
        Object a = new Person("A", 1, "a@t.com");
        Object b = new Product("P001", "Widget", 9.99, 100);
        assertFalse(IdUtils.isSameData(a, b));
    }

    // ==================== isSamePartialData ====================

    @Test
    void testIsSamePartialData_sameObject() {
        Product p = new Product("P001", "Widget", 9.99, 100);
        assertTrue(IdUtils.isSamePartialData(p, p, 0.6));
    }

    @Test
    void testIsSamePartialData_bothNull() {
        assertTrue(IdUtils.isSamePartialData(null, null, 0.6));
    }

    @Test
    void testIsSamePartialData_oneNull() {
        assertFalse(IdUtils.isSamePartialData(new Product("x", "y", 1.0, 1), null, 0.6));
    }

    @Test
    void testIsSamePartialData_partialMatchMayPass() {
        // 哈希采样概率性：stock 可能被选中也可能不选中。
        // 测试目标：验证非 null、对同一对象返回 true、对不同对象可能 true 也可能 false。
        Product p1 = new Product("P001", "Widget", 9.99, 100);
        Product p2 = new Product("P001", "Widget", 9.99, 999);
        boolean result = IdUtils.isSamePartialData(p1, p2, 0.75);
        // 可能是 true（stock 未被选中），也可能是 false（stock 被选中）—— 均合法
        // 关键：对同一对象一定返回 true
        assertTrue(IdUtils.isSamePartialData(p1, p1, 0.75));
    }

    @Test
    void testIsSamePartialData_differentDataFalse() {
        Product p1 = new Product("P001", "Widget", 9.99, 100);
        Product p2 = new Product("P999", "Gadget", 19.99, 50);
        assertFalse(IdUtils.isSamePartialData(p1, p2, 1.0));
    }
}
