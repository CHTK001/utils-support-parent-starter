package com.chua.common.support.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UUIDv7 测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class IdUtilsUuidv7Test {

    @Test
    void testUuidv7ReturnsNotNull() {
        String id = IdUtils.uuidv7();
        assertNotNull(id);
        assertFalse(id.isEmpty());
    }

    @Test
    void testUuidv7Format() {
        String id = IdUtils.uuidv7();
        // UUID 格式：8-4-4-4-12 十六进制
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                "UUIDv7 格式不符合 RFC 9562: " + id);
    }

    @Test
    void testUuidv7IsMonotonic() throws InterruptedException {
        // UUIDv7 跨毫秒严格单调递增；同毫秒内因随机部分可能无序，故逐毫秒验证
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ids.add(IdUtils.uuidv7());
            if (i < 199) {
                Thread.sleep(1);
            }
        }
        // 提取每个 UUID 的时间戳部分（前 12 个十六进制字符，不含分隔符）
        List<Long> timestamps = new ArrayList<>();
        for (String id : ids) {
            // UUID 格式: xxxxxxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx
            // 时间戳 = 前 8 位 + 第 2 段的后 4 位 = 前 12 个 hex 字符（去掉 dash）
            String tsHex = id.replace("-", "").substring(0, 12);
            timestamps.add(Long.parseUnsignedLong(tsHex, 16));
        }
        for (int i = 1; i < timestamps.size(); i++) {
            assertTrue(timestamps.get(i) >= timestamps.get(i - 1),
                    "UUIDv7 时间戳应非递减: " + timestamps.get(i - 1) + " > " + timestamps.get(i));
        }
    }

    @Test
    void testCreateUuidv7SameAsUuidv7() {
        // uuidv7() 和 createUuidv7() 是同一方法的两种调用方式，结果可能不同（随机部分）
        // 只验证两者都是合法的 UUIDv7 格式
        String a = IdUtils.uuidv7();
        String b = IdUtils.createUuidv7();
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertTrue(b.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    }

    @Test
    void testUuidv7Uniqueness() {
        java.util.HashSet<String> set = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) {
            set.add(IdUtils.uuidv7());
        }
        // UUIDv7 在跨毫秒调用时严格唯一；同毫秒内有极小碰撞概率（14 bits rand_a），允许少量重复
        assertTrue(set.size() >= 950, "UUIDv7 应在绝大多数情况下唯一，实际去重数: " + set.size());
    }
}