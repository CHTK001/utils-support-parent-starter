package com.chua.fastjson.support.json;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.Json5;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.lang.json.JsonProvider;
import com.chua.common.support.lang.json.annotation.JsonIgnore;
import com.chua.common.support.lang.json.annotation.JsonName;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FastjsonJsonProvider 测试：验证 SPI 发现、统一门户注解兼容与编解码行为。
 *
 * @author CH
 * @since 4.0.0.42
 */
class FastjsonJsonProviderTest {

    @AfterEach
    void restoreDefault() {
        Json.setImplementation(new com.chua.common.support.lang.json.JacksonJsonProvider());
    }

    /**
     * 统一门户注解测试实体。
     */
    static class User implements Serializable {
        @JsonName("user_name")
        private String name;

        @JsonIgnore
        private String password;

        private int age;

        public User() {
        }

        public User(String name, String password, int age) {
            this.name = name;
            this.password = password;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    /**
     * 验证 Fastjson 实现可通过 SPI 名称发现（@Spi("fastjson") + @AutoSpi 生成的索引）。
     */
    @Test
    void testSpiDiscovery() {
        JsonProvider byName = ServiceProvider.of(JsonProvider.class).getExtension("fastjson");
        assertNotNull(byName, "应能通过 SPI 发现 fastjson 实现");
        assertTrue(byName instanceof FastjsonJsonProvider);
    }

    /**
     * 验证切换实现后门面委托到 Fastjson。
     */
    @Test
    void testSetImplementation() {
        FastjsonJsonProvider provider = new FastjsonJsonProvider();
        Json.setImplementation(provider);
        assertSame(provider, Json.getImplementation());
    }

    /**
     * 验证统一门户注解 @JsonName / @JsonIgnore 通过 JsonBeanMapper 桥接生效。
     */
    @Test
    void testUnifiedAnnotations() {
        FastjsonJsonProvider provider = new FastjsonJsonProvider();
        Json.setImplementation(provider);

        String json = Json.toJson(new User("chua", "secret", 18));
        assertTrue(json.contains("user_name"));
        assertTrue(json.contains("\"age\":18"));
        assertFalse(json.contains("password"), "被 @JsonIgnore 标记的字段不应序列化");

        // prettyFormat 同样应走门户注解桥接（@JsonName / @JsonIgnore）
        String pretty = Json.prettyFormat(new User("chua", "secret", 18));
        assertTrue(pretty.contains("user_name"));
        assertFalse(pretty.contains("password"), "prettyFormat 也应排除 @JsonIgnore 字段");

        User user = Json.fromJson("{\"user_name\":\"chua\",\"password\":\"x\",\"age\":18}", User.class);
        assertEquals("chua", user.getName());
        assertEquals(18, user.getAge());
        assertEquals(null, user.getPassword());
    }

    /**
     * 验证基础编解码往返。
     */
    @Test
    void testRoundTrip() {
        FastjsonJsonProvider provider = new FastjsonJsonProvider();
        Json.setImplementation(provider);

        JsonObject obj = Json.getJsonObject("{\"k\":\"v\"}");
        assertEquals("v", obj.get("k"));

        JsonArray array = Json.getJsonArray("[1,2,3]");
        assertEquals(3, array.size());

        assertTrue(Json.isJson("{\"a\":1}"));
        assertFalse(Json.isJson("plain"));
        assertTrue(Json.validate("{\"a\":1}"));
        assertFalse(Json.validate("not json"));

        Map<String, Object> map = Json.fromJson("{\"a\":1}");
        assertEquals(1, ((Number) map.get("a")).intValue());
    }

    /**
     * 验证 toJson 忽略字段参数。
     */
    @Test
    void testToJsonIgnores() {
        FastjsonJsonProvider provider = new FastjsonJsonProvider();
        String json = provider.toJson(new User("chua", "secret", 18), "age");
        assertFalse(json.contains("age"));
        assertTrue(json.contains("user_name"));
    }

    /**
     * 验证通用 Type 反序列化。
     */
    @Test
    void testTypeDeserialization() {
        FastjsonJsonProvider provider = new FastjsonJsonProvider();
        String json = "[{\"name\":\"a\",\"age\":1}]";
        Type listType = new com.alibaba.fastjson.TypeReference<List<Map<String, Object>>>() {
        }.getType();
        List<Map<String, Object>> list = provider.fromJson(json, listType);
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("a", list.get(0).get("name"));
    }

    /**
     * 验证 stream / reader 入口。
     */
    @Test
    void testStreamEntries() {
        FastjsonJsonProvider provider = new FastjsonJsonProvider();
        InputStream stream = new java.io.ByteArrayInputStream("{\"k\":\"v\"}".getBytes());
        assertEquals("v", provider.getJsonObject(stream).get("k"));

        Reader reader = new java.io.StringReader("{\"k\":\"v\"}");
        assertEquals("v", provider.getJsonObject(new InputStreamReader(new java.io.ByteArrayInputStream("{\"k\":\"v\"}".getBytes()))).get("k"));
        assertEquals("v", provider.fromJson(reader, Map.class).get("k"));

        Writer writer = new java.io.StringWriter();
        provider.toJson(new User("chua", "secret", 18), writer);
        assertTrue(writer.toString().contains("user_name"));
    }

    /**
     * 验证节点工厂返回 Fastjson 节点子类（节点类型随实现切换）。
     */
    @Test
    void testNodeFactorySubclasses() {
        FastjsonJsonProvider provider = new FastjsonJsonProvider();
        Json.setImplementation(provider);

        assertInstanceOf(FastjsonJsonObject.class, Json.createJsonObject());
        assertInstanceOf(FastjsonJsonObject.class, JsonObject.create());
        assertInstanceOf(FastjsonJsonObject.class, Json.getJsonObject("{\"a\":1}"));
        assertInstanceOf(FastjsonJsonObject.class, Json5.getJsonObject("{\"a\":1}"));
        assertInstanceOf(FastjsonJsonArray.class, Json.createJsonArray());
        assertInstanceOf(FastjsonJsonArray.class, JsonArray.of());
        assertInstanceOf(FastjsonJsonArray.class, Json.getJsonArray("[1,2]"));
        assertInstanceOf(FastjsonJsonArray.class, Json5.getJsonArray("[1,2]"));
        assertInstanceOf(FastjsonJsonNode.class, Json.createJsonNode("v"));
        assertInstanceOf(FastjsonJsonNode.class, JsonNode.valueOf("v"));
        assertInstanceOf(FastjsonJsonNode.class, Json.parse("{\"a\":1}"));
        assertInstanceOf(FastjsonJsonNode.class, Json.build());
        assertInstanceOf(FastjsonJsonNode.class, Json.buildArray());
    }
}
