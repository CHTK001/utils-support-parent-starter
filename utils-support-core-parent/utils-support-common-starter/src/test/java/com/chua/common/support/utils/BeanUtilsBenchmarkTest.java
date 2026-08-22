package com.chua.common.support.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * BeanUtils 性能基准测试。
 *
 * @author CH
 * @since 4.0.0.43
 */
class BeanUtilsBenchmarkTest {

    static class Source {
        String name;
        int age;
        double salary;
        String email;
        String phone;
        String address;
        boolean active;
        long createTime;

        Source(String name, int age, double salary, String email, String phone, String address, boolean active, long createTime) {
            this.name = name;
            this.age = age;
            this.salary = salary;
            this.email = email;
            this.phone = phone;
            this.address = address;
            this.active = active;
            this.createTime = createTime;
        }
    }

    static class Target {
        String name;
        int age;
        double salary;
        String email;
        String phone;
        String address;
        boolean active;
        long createTime;
    }

    private static final Source SOURCE = new Source("Alice", 30, 8000.0, "alice@test.com", "138", "Beijing", true, 1L);

    @Test
    void benchmarkBeanUtilsCopy() {
        int iterations = 500_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Target t = new Target();
            BeanUtils.copyProperties(SOURCE, t);
        }
        long elapsed = System.nanoTime() - start;
        double us = elapsed / (double) iterations / 1000.0;
        System.out.printf("BeanUtils.copyProperties: %.2f us/call%n", us);
    }

    @Test
    void benchmarkBeanUtilsCopyNew() {
        int iterations = 500_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            BeanUtils.copyProperties(SOURCE, Target.class);
        }
        long elapsed = System.nanoTime() - start;
        double us = elapsed / (double) iterations / 1000.0;
        System.out.printf("BeanUtils.copyProperties(new): %.2f us/call%n", us);
    }

    @Test
    void benchmarkBeanUtilsToMap() {
        int iterations = 500_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            BeanUtils.objectToMap(SOURCE);
        }
        long elapsed = System.nanoTime() - start;
        double us = elapsed / (double) iterations / 1000.0;
        System.out.printf("BeanUtils.objectToMap: %.2f us/call%n", us);
    }
}
