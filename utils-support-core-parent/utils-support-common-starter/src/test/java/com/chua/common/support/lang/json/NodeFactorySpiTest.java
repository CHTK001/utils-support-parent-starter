package com.chua.common.support.lang.json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 节点类型随 {@link JsonProvider} SPI 实现切换的测试。
 *
 * <p>验证 {@link JsonProvider} 的节点工厂方法（{@code createJsonObject} / {@code createJsonArray} / {@code createJsonNode}）
 * 被 {@link Json} 门面及 {@link JsonObject} / {@link JsonArray} / {@link JsonNode} 静态工厂委托调用，
 * 节点类型随当前实现切换（默认通用类，自定义实现返回其节点子类）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class NodeFactorySpiTest {

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
     * 验证默认实现（Jackson）下节点工厂返回通用节点类型。
     */
    @Test
    void testDefaultFactoryReturnsCommonNodes() {
        assertInstanceOf(JsonObject.class, Json.createJsonObject());
        assertInstanceOf(JsonObject.class, JsonObject.create());
        assertInstanceOf(JsonObject.class, Json.createJsonObject(Map.of("a", 1)));
        assertInstanceOf(JsonArray.class, Json.createJsonArray());
        assertInstanceOf(JsonArray.class, JsonArray.of());
        assertInstanceOf(JsonArray.class, Json.createJsonArray(List.of(1, 2)));
        assertInstanceOf(JsonNode.class, Json.createJsonNode("v"));
        assertInstanceOf(JsonNode.class, JsonNode.valueOf("v"));
    }

    /**
     * 验证切换到自定义实现后，节点工厂返回该实现的节点子类。
     */
    @Test
    void testFactorySwitchesWithImplementation() {
        Json.setImplementation(new StubNodeProvider());

        // 门面委托
        assertInstanceOf(StubJsonObject.class, Json.createJsonObject());
        assertInstanceOf(StubJsonArray.class, Json.createJsonArray());
        assertInstanceOf(StubJsonNode.class, Json.createJsonNode("v"));

        // 静态工厂委托
        assertInstanceOf(StubJsonObject.class, JsonObject.create());
        assertInstanceOf(StubJsonArray.class, JsonArray.of());
        assertInstanceOf(StubJsonNode.class, JsonNode.valueOf("v"));

        // 嵌套导航委托（Map 包装走节点工厂）
        JsonObject obj = Json.getJsonObject("{\"child\":{\"k\":1}}");
        assertInstanceOf(StubJsonObject.class, obj.getJsonObject("child"));

        // 解析路径（parse / build）也应返回子类
        assertInstanceOf(StubJsonNode.class, Json.parse("{\"a\":1}"));
        assertInstanceOf(StubJsonNode.class, Json.build());
        assertInstanceOf(StubJsonNode.class, Json.buildArray());
    }

    /**
     * 验证 Json5 门面与 Json 门面共享同一节点工厂（随实现切换）。
     */
    @Test
    void testJson5SharesNodeFactory() {
        Json.setImplementation(new StubNodeProvider());
        assertInstanceOf(StubJsonObject.class, Json5.getJsonObject("{\"a\":1}"));
        assertInstanceOf(StubJsonArray.class, Json5.getJsonArray("[1,2]"));
        assertSame(Json.getImplementation(), Json5.getImplementation());
    }

    /**
     * 测试用节点子类（对象）。
     */
    static class StubJsonObject extends JsonObject {
        /** 创建 StubJsonObject 实例 */
        public StubJsonObject() {
        }

        /**
         * 创建 StubJsonObject 实例
         * @param m m
         */
        public StubJsonObject(Map m) {
            super(m);
        }
    }

    /**
     * 测试用节点子类（数组）。
     */
    static class StubJsonArray extends JsonArray {
        /** 创建 StubJsonArray 实例 */
        public StubJsonArray() {
        }

        /**
         * 创建 StubJsonArray 实例
         * @param collection collection
         */
        public StubJsonArray(Collection collection) {
            super(collection);
        }
    }

    /**
     * 测试用节点子类（节点）。
     */
    static class StubJsonNode extends JsonNode {
        /**
         * 创建 StubJsonNode 实例
         * @param value value
         */
        public StubJsonNode(Object value) {
            super(value);
        }
    }

    /**
     * 覆写节点工厂方法的自定义实现（基于 Jackson 复用其余解析逻辑）。
     */
    static class StubNodeProvider extends JacksonJsonProvider {
        @Override
        /** 创建JsonObject */
        public JsonObject createJsonObject() {
            return new StubJsonObject();
        }

        @Override
        /** 创建JsonObject */
        public JsonObject createJsonObject(Map map) {
            return new StubJsonObject(map);
        }

        @Override
        /** 创建JsonArray */
        public JsonArray createJsonArray() {
            return new StubJsonArray();
        }

        @Override
        /** 创建JsonArray */
        public JsonArray createJsonArray(Collection collection) {
            return new StubJsonArray(collection);
        }

        @Override
        /** 创建JsonNode */
        public JsonNode createJsonNode(Object value) {
            return new StubJsonNode(value);
        }
    }
}
