package com.chua.common.support.lang.json;

import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Json5 门面测试：验证 JSON5 语法经 SPI 默认实现解析，以及与 {@link Json} 门面的统一切换。
 *
 * @author CH
 * @since 4.0.0.42
 */
class Json5Test {

    /**
     * 测试数据实体。
     */
    static class User {
        private String name;
        private int age;

        public User() {
        }

        public User(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    /**
     * 测试开始前的 SPI 默认实现，用于 {@link #restoreDefault()} 精确还原。
     */
    private JsonProvider originalImplementation;

    /**
     * 记录测试前的 SPI 实现，便于用例结束后精确还原。
     */
    @BeforeEach
    void captureDefault() {
        originalImplementation = Json.getImplementation();
    }

    /**
     * 每个用例结束后恢复测试前的实现，避免影响其他测试。
     */
    @AfterEach
    void restoreDefault() {
        Json.setImplementation(originalImplementation);
    }

    /**
     * 验证 JSON5 语法（单引号 / 注释 / 尾随逗号 / 未加引号键名）经 SPI 默认实现可解析。
     */
    @Test
    void testJson5SyntaxParsing() {
        // 单引号 + 注释 + 尾随逗号 + 未加引号键名的 JSON5 文档
        String json5 = "{\n" +
                "  name: 'chua', // 单行注释\n" +
                "  age: 18,\n" +
                "}";
        User user = Json5.fromJson(json5, User.class);
        assertNotNull(user);
        assertEquals("chua", user.getName());
        assertEquals(18, user.getAge());

        Map<String, Object> map = Json5.fromJson(json5);
        assertEquals("chua", map.get("name"));

        JsonObject jsonObject = Json5.getJsonObject(json5);
        assertEquals("chua", jsonObject.get("name"));

        assertTrue(Json5.isJson5(json5));
        assertTrue(Json5.validate("{\"a\":1}"));
        assertFalse(Json5.validate("not json"));
    }

    /**
     * 验证注释预处理与数组解析。
     */
    @Test
    void testPreprocessAndList() {
        String json5 = "[\n" +
                "  // 注释\n" +
                "  {\"name\":\"a\",\"age\":1},\n" +
                "  {\"name\":\"b\",\"age\":2},\n" +
                "]";

        // 先去除注释再解析（严格模式下注释会解析失败，这里验证预处理能力）
        String clean = Json5.preprocessJson5(json5);
        assertFalse(clean.contains("//"));

        List<User> users = Json5.fromJsonList(clean, User.class);
        assertNotNull(users);
        assertEquals(2, users.size());
        assertEquals("a", users.get(0).getName());
        assertEquals(2, users.get(1).getAge());

        JsonArray array = Json5.getJsonArray(clean);
        assertEquals(2, array.size());
    }

    /**
     * 验证 Json5 与 Json 门面共享同一 SPI 实现，切换后行为统一。
     */
    @Test
    void testUnifiedSwitching() {
        // 默认实现应一致（SPI 发现的 Jackson 实现）
        assertSame(Json.getImplementation(), Json5.getImplementation());
        assertTrue(Json5.getImplementation() instanceof JacksonJsonProvider);

        // 通过 Json5 切换实现，Json 门面跟随
        JsonProvider stub = new StubJsonProvider();
        Json5.setImplementation(stub);
        assertSame(stub, Json.getImplementation());
        assertEquals("{\"provider\":\"stub\"}", Json5.toJson(new User("chua", 18)));
        assertEquals("{\"provider\":\"stub\"}", Json.toJson(new User("chua", 18)));

        // 通过 Json 切换实现，Json5 门面跟随
        Json.setImplementation(stub);
        assertSame(stub, Json5.getImplementation());
    }

    /**
     * 验证 Json5 序列化 / 字节数组 / 基础类型转换。
     */
    @Test
    void testSerialization() {
        String json = Json5.toJson(new User("chua", 18));
        assertTrue(json.contains("\"name\":\"chua\""));

        byte[] bytes = Json5.toJsonByte(new User("c", 3));
        User user = Json5.fromJson(bytes, User.class);
        assertEquals("c", user.getName());
        assertEquals(3, user.getAge());

        JsonObject obj = Json5.fromJson("{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals("v", obj.get("k"));
    }

    /**
     * 验证 SPI 名称发现与门面默认实现（回归 JsonFacadeTest 行为）。
     */
    @Test
    void testSpiConsistency() {
        JsonProvider byName = ServiceProvider.of(JsonProvider.class).getExtension("jackson");
        assertNotNull(byName);
        assertTrue(byName instanceof JacksonJsonProvider);
        assertTrue(Json5.getImplementation() instanceof JacksonJsonProvider);
    }

    /**
     * 简单桩实现，仅覆盖 toJson / fromJson 验证委托链路。
     */
    static class StubJsonProvider implements JsonProvider {
        @Override
        public JsonNode parse(String json) {
            return new JsonNode(new JsonObject());
        }

        @Override
        public JsonNode parse(byte[] json) {
            return parse(new String(json));
        }

        @Override
        public JsonNode build() {
            return new JsonNode(new JsonObject());
        }

        @Override
        public JsonNode buildArray() {
            return new JsonNode(new JsonArray());
        }

        @Override
        public JsonObject getJsonObject(String json) {
            return new JsonObject();
        }

        @Override
        public JsonReference getJsonReference(String json) {
            return new JsonReference(json);
        }

        @Override
        public JsonArray getJsonArray(byte[] jsonArray) {
            return new JsonArray();
        }

        @Override
        public JsonArray getJsonArray(String json) {
            return new JsonArray();
        }

        @Override
        public JsonObject getJsonObject(byte[] bytes) {
            return new JsonObject();
        }

        @Override
        public JsonObject getJsonObject(java.io.InputStreamReader inputStreamReader) {
            return new JsonObject();
        }

        @Override
        public JsonObject getJsonObject(java.io.InputStream inputStream) {
            return new JsonObject();
        }

        @Override
        public JsonObject getJsonObject(java.io.InputStream inputStream, String charset) {
            return new JsonObject();
        }

        @Override
        public <T> List<T> fromJsonToList(java.io.InputStream inputStream, Class<T> targetType) {
            return List.of();
        }

        @Override
        public <T> List<T> fromJsonToList(String json, Class<T> targetType) {
            return List.of();
        }

        @Override
        public <T> T fromJson(String json, Class<T> target) {
            return target.cast(new User("stub", 1));
        }

        @Override
        public <T> T fromJson(byte[] bytes, Class<T> target) {
            return fromJson(new String(bytes), target);
        }

        @Override
        public JsonObject fromJson(byte[] bytes, Charset charset) {
            return new JsonObject();
        }

        @Override
        public <T> T fromJson(java.io.InputStreamReader inputStreamReader, Class<T> target) {
            return fromJson("", target);
        }

        @Override
        public <T> T fromJson(java.io.InputStream inputStream, Class<T> target) {
            return fromJson("", target);
        }

        @Override
        public String toJson(Object object, String... ignores) {
            return "{\"provider\":\"stub\"}";
        }

        @Override
        public String prettyFormat(Object object) {
            return toJson(object);
        }

        @Override
        public String toPrettyJson(Object obj) {
            return prettyFormat(obj);
        }

        @Override
        public byte[] toJsonByte(Object object) {
            return toJson(object).getBytes();
        }

        @Override
        public boolean isJson(Object ext) {
            return false;
        }

        @Override
        public List<?> toList(String string) {
            return List.of();
        }

        @Override
        public byte[] toJSONBytes(Object object) {
            return toJsonByte(object);
        }

        @Override
        public String toJSONString(Object object) {
            return toJson(object);
        }

        @Override
        public boolean validate(String jsonStr) {
            return true;
        }

        @Override
        public Map<String, Object> fromJson(String string) {
            return Map.of();
        }

        @Override
        public <T> T fromJson(String stringValue, java.lang.reflect.Type type) {
            return fromJson(stringValue, (Class<T>) Object.class);
        }

        @Override
        public <T> T fromJson(java.io.Reader reader, Class<T> target) {
            return fromJson("", target);
        }

        @Override
        public void toJson(Object object, java.io.Writer writer) {
            try {
                writer.write(toJson(object));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <T> T fromJson(java.io.InputStream stream, java.lang.reflect.Type type) {
            return fromJson("", (Class<T>) Object.class);
        }

        @Override
        public <T> T fromJson(java.io.Reader reader, java.lang.reflect.Type type) {
            return fromJson("", (Class<T>) Object.class);
        }
    }
}
