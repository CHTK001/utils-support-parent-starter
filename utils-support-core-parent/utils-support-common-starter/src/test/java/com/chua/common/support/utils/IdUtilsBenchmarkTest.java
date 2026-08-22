package com.chua.common.support.utils;

import org.junit.jupiter.api.Test;

/**
 * IdUtils 性能基准测试。
 *
 * @author CH
 * @since 4.0.0.43
 */
class IdUtilsBenchmarkTest {

    static class Person {
        String name;
        int age;
        String email;
        double salary;
        String department;
        String phone;
        String address;
        String city;
        String country;
        boolean active;

        Person(String name, int age, String email, double salary, String dept,
               String phone, String address, String city, String country, boolean active) {
            this.name = name;
            this.age = age;
            this.email = email;
            this.salary = salary;
            this.department = dept;
            this.phone = phone;
            this.address = address;
            this.city = city;
            this.country = country;
            this.active = active;
        }
    }

    private static final Person PERSON = new Person("Alice", 30, "alice@test.com",
            8000.0, "IT", "13800138000", "123 Main St", "Beijing", "CN", true);

    @Test
    void benchmarkGetId() {
        int iterations = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            IdUtils.getId(PERSON);
        }
        long elapsed = System.nanoTime() - start;
        double usPerCall = elapsed / (double) iterations / 1000.0;
        System.out.printf("getId: %.2f us/call%n", usPerCall);
    }

    @Test
    void benchmarkGetPartialId() {
        int iterations = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            IdUtils.getPartialId(PERSON, 0.6);
        }
        long elapsed = System.nanoTime() - start;
        double usPerCall = elapsed / (double) iterations / 1000.0;
        System.out.printf("getPartialId(0.6): %.2f us/call%n", usPerCall);
    }
}
