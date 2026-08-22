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

    // ==================== getId ====================

    @Test
    void testGetId_nullReturnsNull() {
        assertNull(IdUtils.getId(null));
    }

    @Test
    void testGetId_sameDataSameId() {
        Person p1 = new Person("Alice", 30, "alice@test.com");
        Person p2 = new Person("Alice", 30, "alice@test.com");
        String id1 = IdUtils.getId(p1);
        String id2 = IdUtils.getId(p2);
        assertNotNull(id1);
        assertNotNull(id2);
        assertEquals(id1, id2);
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
        String id1 = IdUtils.getId(p);
        String id2 = IdUtils.getId(p);
        assertEquals(id1, id2);
    }

    // ==================== getPartialId ====================

    @Test
    void testGetPartialId_nullReturnsNull() {
        assertNull(IdUtils.getPartialId(null, 0.6));
    }

    @Test
    void testGetPartialId_invalidRatioThrows() {
        Product p = new Product("P001", "Widget", 9.99, 100);
        try {
            IdUtils.getPartialId(p, 0.0);
            org.junit.jupiter.api.Assertions.fail("Should throw for ratio=0");
        } catch (IllegalArgumentException ignored) {
        }
        try {
            IdUtils.getPartialId(p, 1.5);
            org.junit.jupiter.api.Assertions.fail("Should throw for ratio=1.5");
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    void testGetPartialId_samePartialDataSameId() {
        Product p1 = new Product("P001", "Widget", 9.99, 100);
        Product p2 = new Product("P001", "Widget", 9.99, 200);
        String id1 = IdUtils.getPartialId(p1, 0.75);
        String id2 = IdUtils.getPartialId(p2, 0.75);
        assertNotNull(id1);
        assertNotNull(id2);
        assertEquals(id1, id2);
    }

    @Test
    void testGetPartialId_fullRatioEqualsGetId() {
        Product p = new Product("P001", "Widget", 9.99, 100);
        assertEquals(IdUtils.getId(p), IdUtils.getPartialId(p, 1.0));
    }

    @Test
    void testGetPartialId_consistentAcrossCalls() {
        Product p = new Product("P001", "Widget", 9.99, 100);
        String id1 = IdUtils.getPartialId(p, 0.6);
        String id2 = IdUtils.getPartialId(p, 0.6);
        assertEquals(id1, id2);
    }

    // ==================== isSameData ====================

    @Test
    void testIsSameData_sameObject() {
        Person p = new Person("Alice", 30, "alice@test.com");
        assertTrue(IdUtils.isSameData(p, p));
    }

    @Test
    void testIsSameData_nullSafe() {
        assertFalse(IdUtils.isSameData(null, null));
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
    void testIsSamePartialData_nullSafe() {
        assertFalse(IdUtils.isSamePartialData(null, null, 0.6));
        assertFalse(IdUtils.isSamePartialData(new Product("x", "y", 1.0, 1), null, 0.6));
    }

    @Test
    void testIsSamePartialData_partialMatch() {
        Product p1 = new Product("P001", "Widget", 9.99, 100);
        Product p2 = new Product("P001", "Widget", 9.99, 999);
        assertTrue(IdUtils.isSamePartialData(p1, p2, 0.75));
    }

    @Test
    void testIsSamePartialData_differentDataFalse() {
        Product p1 = new Product("P001", "Widget", 9.99, 100);
        Product p2 = new Product("P999", "Gadget", 19.99, 50);
        assertFalse(IdUtils.isSamePartialData(p1, p2, 1.0));
    }

    // ==================== 数组/集合字段 ====================

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
}
