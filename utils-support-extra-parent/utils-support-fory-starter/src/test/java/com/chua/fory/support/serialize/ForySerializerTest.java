package com.chua.fory.support.serialize;

import com.chua.common.support.serialize.Serializer;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ForySerializer / ForySerialization 往返序列化测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class ForySerializerTest {

    /**
     * 测试用户实体（含循环引用）
     */
    @lombok.Data
    static class User implements Serializable {
        private String name;
        private int age;
        private List<String> tags = new ArrayList<>();
        private User friend;
    }

    /**
     * 验证 ForySerializer 基本往返序列化。
     */
    @Test
    void testRoundTrip() {
        User user = new User();
        user.setName("chua");
        user.setAge(18);
        user.getTags().add("java");
        user.getTags().add("fory");
        user.setFriend(user); // 循环引用

        Serializer<User> serializer = new ForySerializer<>(User.class);
        byte[] bytes = serializer.serialize(user);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        User result = serializer.deserialize(bytes);
        assertNotNull(result);
        assertEquals("chua", result.getName());
        assertEquals(18, result.getAge());
        assertEquals(2, result.getTags().size());
        assertEquals("fory", result.getTags().get(1));
        // 循环引用保持对象身份（引用一致性检查，避免 Lombok equals 递归）
        assertSame(result, result.getFriend());
    }

    /**
     * 验证 null 输入返回空数组 / null。
     */
    @Test
    void testNullInput() {
        Serializer<User> serializer = new ForySerializer<>(User.class);
        assertEquals(0, serializer.serialize(null).length);
        assertNull(serializer.deserialize(null));
        assertNull(serializer.deserialize(new byte[0]));
    }

    /**
     * 验证 ForySerialization 接口实现。
     */
    @Test
    void testSerializationInterface() throws Exception {
        ForySerialization serialization = new ForySerialization();
        assertEquals("fory", serialization.name());

        User user = new User();
        user.setName("interface");
        user.setAge(30);

        byte[] bytes = serialization.serialize(user);
        User result = serialization.deserialize(bytes, User.class);
        assertNotNull(result);
        assertEquals("interface", result.getName());
        assertEquals(30, result.getAge());
    }
}
