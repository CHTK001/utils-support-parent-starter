package com.chua.common.support.lang.json;

import com.chua.common.support.spi.ServiceProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Json 静态门面类测试：验证默认 Jackson 实现、全局实现替换与 Jackson 专属方法行为。
 *
 * @author CH
 * @since 4.0.0.42
 */
class JsonFacadeTest {

    /**
     * 每个用例结束后恢复默认实现，避免影响其他测试。
     */
    @AfterEach
    void restoreDefault() {
        Json.setImplementation(new JacksonJsonProvider());
    }

    /**
     * 测试数据实体。
     */
    static class User implements Serializable {
        /** 名称 */
        private String name;
        /** AGE */
        private int age;

        /** 创建 User 实例 */
        public User() {
        }

        /**
         * 创建 User 实例
         * @param name name
         * @param int int
         */
        public User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        /** 获取Name */
        public String getName() {
            return name;
        }

        /** 设置Name */
        public void setName(String name) {
            this.name = name;
        }

        /** 获取Age */
        public int getAge() {
            return age;
        }

        /** 设置Age */
        public void setAge(int age) {
            this.age = age;
        }
    }

    /**
     * 验证默认实现通过 SPI 自动发现为 JacksonJsonProvider（而非硬编码），且静态方法委托正常。
     */
    @Test
    void testDefaultImplementationIsJackson() {
        // 门面默认实现由 SPI 发现
        assertTrue(Json.getImplementation() instanceof JacksonJsonProvider);
        // SPI 提供者应能发现标记 @SpiDefault 的默认实现
        JsonProvider spiDefault = ServiceProvider.of(JsonProvider.class).getDefault();
        assertNotNull(spiDefault);
        assertTrue(spiDefault instanceof JacksonJsonProvider);
        // getMapper 仅在默认实现下可用
        assertNotNull(Json.getMapper());

        String json = Json.toJson(new User("chua", 18));
        assertTrue(json.contains("\"name\""));
        User user = Json.fromJson(json, User.class);
        assertEquals("chua", user.getName());
        assertEquals(18, user.getAge());
    }

    /**
     * 验证 SPI 名称注册（@Spi("jackson")）可通过名称获取实现。
     */
    @Test
    void testSpiByName() {
        JsonProvider byName = ServiceProvider.of(JsonProvider.class).getExtension("jackson");
        assertNotNull(byName);
        assertTrue(byName instanceof JacksonJsonProvider);
    }

    /**
     * 验证全局替换实现后，静态方法立即委托到新实现。
     */
    @Test
    void testSetImplementationSwitchesProvider() {
        // 自定义桩实现，仅覆盖 toJson/fromJson 验证委托链路
        JsonProvider stub = new JsonProvider() {
            @Override
            /** 解析 */
            public JsonNode parse(String json) {
                return new JsonNode(new JsonObject());
            }

            @Override
            /** 解析 */
            public JsonNode parse(byte[] json) {
                return parse(new String(json));
            }

            @Override
            /** 构建 */
            public JsonNode build() {
                return new JsonNode(new JsonObject());
            }

            @Override
            /** 构建Array */
            public JsonNode buildArray() {
                return new JsonNode(new JsonArray());
            }

            @Override
            /** 获取JsonObject */
            public JsonObject getJsonObject(String json) {
                return new JsonObject();
            }

            @Override
            /** 获取JsonReference */
            public JsonReference getJsonReference(String json) {
                return new JsonReference(json);
            }

            @Override
            /** 获取JsonArray */
            public JsonArray getJsonArray(byte[] jsonArray) {
                return new JsonArray();
            }

            @Override
            /** 获取JsonArray */
            public JsonArray getJsonArray(String json) {
                return new JsonArray();
            }

            @Override
            /** 获取JsonObject */
            public JsonObject getJsonObject(byte[] bytes) {
                return new JsonObject();
            }

            @Override
            /** 获取JsonObject */
            public JsonObject getJsonObject(InputStreamReader inputStreamReader) {
                return new JsonObject();
            }

            @Override
            /** 获取JsonObject */
            public JsonObject getJsonObject(InputStream inputStream) {
                return new JsonObject();
            }

            @Override
            /** 获取JsonObject */
            public JsonObject getJsonObject(InputStream inputStream, String charset) {
                return new JsonObject();
            }

            @Override
            /** FromJsonToList */
            public <T> List<T> fromJsonToList(InputStream inputStream, Class<T> targetType) {
                return List.of();
            }

            @Override
            /** FromJsonToList */
            public <T> List<T> fromJsonToList(String json, Class<T> targetType) {
                return List.of();
            }

            @Override
            /** FromJson */
            public <T> T fromJson(String json, Class<T> target) {
                return target.cast(new User("stub", 1));
            }

            @Override
            /** FromJson */
            public <T> T fromJson(byte[] bytes, Class<T> target) {
                return fromJson(new String(bytes), target);
            }

            @Override
            /** FromJson */
            public JsonObject fromJson(byte[] bytes, Charset charset) {
                return new JsonObject();
            }

            @Override
            /** FromJson */
            public <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target) {
                return fromJson("", target);
            }

            @Override
            /** FromJson */
            public <T> T fromJson(InputStream inputStream, Class<T> target) {
                return fromJson("", target);
            }

            @Override
            /** ToJson */
            public String toJson(Object object, String... ignores) {
                return "{\"provider\":\"stub\"}";
            }

            @Override
            /** Pretty格式化 */
            public String prettyFormat(Object object) {
                return toJson(object);
            }

            @Override
            /** ToPrettyJson */
            public String toPrettyJson(Object obj) {
                return prettyFormat(obj);
            }

            @Override
            /** ToJsonByte */
            public byte[] toJsonByte(Object object) {
                return toJson(object).getBytes();
            }

            @Override
            /** 是否Json */
            public boolean isJson(Object ext) {
                return false;
            }

            @Override
            public List<?> toList(String string) {
                return List.of();
            }

            @Override
            /** ToJSONBytes */
            public byte[] toJSONBytes(Object object) {
                return toJsonByte(object);
            }

            @Override
            /** ToJSONString */
            public String toJSONString(Object object) {
                return toJson(object);
            }

            @Override
            /** 校验 */
            public boolean validate(String jsonStr) {
                return true;
            }

            @Override
            /** FromJson */
            public Map<String, Object> fromJson(String string) {
                return Map.of();
            }

            @Override
            /** FromJson */
            public <T> T fromJson(String stringValue, Type type) {
                return fromJson(stringValue, (Class<T>) Object.class);
            }

            @Override
            /** FromJson */
            public <T> T fromJson(Reader reader, Class<T> target) {
                return fromJson("", target);
            }

            @Override
            /** ToJson */
            public void toJson(Object object, Writer writer) {
                try {
                    writer.write(toJson(object));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            /** FromJson */
            public <T> T fromJson(InputStream stream, Type type) {
                return fromJson("", (Class<T>) Object.class);
            }

            @Override
            /** FromJson */
            public <T> T fromJson(Reader reader, Type type) {
                return fromJson("", (Class<T>) Object.class);
            }
        };

        Json.setImplementation(stub);
        assertSame(stub, Json.getImplementation());
        assertEquals("{\"provider\":\"stub\"}", Json.toJson(new User("chua", 18)));
        User user = Json.fromJson("{}", User.class);
        assertEquals("stub", user.getName());
        // 非 Jackson 实现下 getMapper 应抛出异常
        assertThrows(UnsupportedOperationException.class, Json::getMapper);
    }

    /**
     * 验证 setImplementation(null) 抛出异常，避免破坏全局状态。
     */
    @Test
    void testSetImplementationRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> Json.setImplementation(null));
        // 实现未被破坏，仍为 Jackson
        assertTrue(Json.getImplementation() instanceof JacksonJsonProvider);
    }

    /**
     * 验证 TypeReference 泛型反序列化在默认实现下正常工作。
     */
    @Test
    void testTypeReferenceDeserialization() {
        String json = "[{\"name\":\"a\",\"age\":1},{\"name\":\"b\",\"age\":2}]";
        List<User> users = Json.fromJson(json, new TypeReference<List<User>>() {
        });
        assertEquals(2, users.size());
        assertEquals("a", users.get(0).getName());
        assertEquals(2, users.get(1).getAge());
    }

    /**
     * 验证常用静态方法在门面下行为与改造前一致。
     */
    @Test
    void testCommonStaticMethods() {
        JsonObject obj = Json.getJsonObject("{\"k\":\"v\"}");
        assertEquals("v", obj.get("k"));

        JsonArray array = Json.getJsonArray("[1,2,3]");
        assertEquals(3, array.size());

        assertTrue(Json.isJson("{\"a\":1}"));
        assertFalse(Json.isJson("plain text"));
        assertTrue(Json.validate("{\"a\":1}"));
        assertFalse(Json.validate("not json"));

        byte[] bytes = Json.toJsonByte(new User("c", 3));
        assertEquals("c", Json.fromJson(bytes, User.class).getName());
    }
}
