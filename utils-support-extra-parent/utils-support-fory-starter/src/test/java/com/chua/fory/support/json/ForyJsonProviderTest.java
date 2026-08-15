package com.chua.fory.support.json;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.lang.json.JsonProvider;
import com.chua.common.support.lang.json.annotation.JsonIgnore;
import com.chua.common.support.lang.json.annotation.JsonName;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ForyJsonProvider 测试：验证 SPI 发现、统一门户注解桥接与编解码行为。
 *
 * @author CH
 * @since 4.0.0.42
 */
class ForyJsonProviderTest {

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
     * 验证 Fory 实现可通过 SPI 名称发现（@Spi("fory") + @AutoSpi 生成的索引）。
     */
    @Test
    void testSpiDiscovery() {
        JsonProvider byName = ServiceProvider.of(JsonProvider.class).getExtension("fory");
        assertNotNull(byName, "应能通过 SPI 发现 fory 实现");
        assertTrue(byName instanceof ForyJsonProvider);
    }

    /**
     * 验证切换实现后门面委托到 Fory。
     */
    @Test
    void testSetImplementation() {
        ForyJsonProvider provider = new ForyJsonProvider();
        Json.setImplementation(provider);
        assertSame(provider, Json.getImplementation());
    }

    /**
     * 验证统一门户注解 @JsonName / @JsonIgnore 通过 JsonBeanMapper 桥接生效。
     */
    @Test
    void testUnifiedAnnotations() {
        ForyJsonProvider provider = new ForyJsonProvider();
        Json.setImplementation(provider);

        String json = Json.toJson(new User("chua", "secret", 18));
        assertTrue(json.contains("user_name"));
        assertTrue(json.contains("\"age\":18"));
        assertFalse(json.contains("password"), "被 @JsonIgnore 标记的字段不应序列化");

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
        ForyJsonProvider provider = new ForyJsonProvider();
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
        ForyJsonProvider provider = new ForyJsonProvider();
        String json = provider.toJson(new User("chua", "secret", 18), "age");
        assertFalse(json.contains("age"));
        assertTrue(json.contains("user_name"));
    }

    /**
     * 验证通用 Type 反序列化（TypeRef 桥接）。
     */
    @Test
    void testTypeDeserialization() {
        ForyJsonProvider provider = new ForyJsonProvider();
        String json = "[{\"name\":\"a\",\"age\":1}]";
        java.lang.reflect.Type listType = new java.lang.reflect.ParameterizedType() {
            @Override
            public java.lang.reflect.Type[] getActualTypeArguments() {
                return new java.lang.reflect.Type[]{Map.class};
            }

            @Override
            public java.lang.reflect.Type getRawType() {
                return List.class;
            }

            @Override
            public java.lang.reflect.Type getOwnerType() {
                return null;
            }
        };
        List<Map<String, Object>> list = provider.fromJson(json, listType);
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("a", list.get(0).get("name"));
    }
}
