package com.chua.json.support;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * JSONPath 工具类，基于 <a href="https://github.com/json-path/JsonPath">jayway JsonPath</a> 提供 JSON 数据提取与操作功能。
 *
 * <p>JSONPath 是一种类似 XPath 的 JSON 查询语言，用于从 JSON 结构中定位和提取数据。
 * 以下是常用表达式示例：</p>
 * <pre>{@code
 * $                   — 根对象
 * $.store.book[0]     — store 下第一本书
 * $.store.book[*]     — store 下所有书
 * $.store.book[?(@.price < 10)]  — 价格小于10的书
 * $.store.book[0:3]   — 前三本书
 * $..author           — 所有 author 字段（递归查找）
 * $.store.*           — store 下所有字段
 * }</pre>
 *
 * <p><b>使用方式：</b></p>
 * <pre>{@code
 * String json = "{\"store\":{\"book\":[{\"title\":\"Java\",\"price\":29.9}]}}";
 *
 * // 读取单个值（自动类型推断）
 * String title = JsonPathUtils.read(json, "$.store.book[0].title");
 *
 * // 读取指定类型的值
 * double price = JsonPathUtils.read(json, "$.store.book[0].price", double.class);
 *
 * // 读取列表
 * List<String> titles = JsonPathUtils.readList(json, "$.store.book[*].title");
 *
 * // 条件过滤
 * List<Map<String, Object>> cheapBooks = JsonPathUtils.readList(json,
 *         "$.store.book[?(@.price < 30)]");
 *
 * // 修改值
 * String updated = JsonPathUtils.set(json, "$.store.book[0].price", 35.0);
 *
 * // 判断路径是否存在
 * boolean exists = JsonPathUtils.isExist(json, "$.store.book[0]");
 *
 * // 链式操作（多次修改）
 * DocumentContext ctx = JsonPathUtils.parse(json);
 * ctx.set("$.store.book[0].price", 35.0)
 *    .delete("$.store.book[1]");
 * String result = ctx.jsonString();
 * }</pre>
 *
 * <p><b>默认配置：</b></p>
 * <ul>
 *   <li>JSON 提供者 — Jackson（与项目统一）</li>
 *   <li>映射提供者 — Jackson（支持 POJO 反序列化）</li>
 *   <li>选项 — {@link Option#DEFAULT_PATH_LEAF_TO_NULL}（路径不存在时返回 null 而非抛异常）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JsonPathUtils {

    /**
     * 默认配置：Jackson 序列化 + 路径不存在时返回 null
     */
    private static final Configuration DEFAULT_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    /**
     * 严格配置：路径不存在时抛出异常
     */
    private static final Configuration STRICT_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .build();

    /**
     * 可读的解析上下文（带默认配置）
     */
    private static final ParseContext PARSE_CTX = JsonPath.using(DEFAULT_CONFIG);

    private JsonPathUtils() {
    }

    // ==================== 读取操作 ====================

    /**
     * 从 JSON 字符串中读取 JSONPath 表达式的值，自动推断返回类型。
     *
     * <p>返回类型根据 JSON 数据类型自动映射：</p>
     * <ul>
     *   <li>字符串 → {@link String}</li>
     *   <li>数字 → {@link Double}</li>
     *   <li>布尔 → {@link Boolean}</li>
     *   <li>数组 → {@link List}</li>
     *   <li>对象 → {@link java.util.LinkedHashMap}</li>
     *   <li>null → {@code null}</li>
     * </ul>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式，如 {@code "$.store.book[0].title"}
     * @param <T>      返回值类型
     * @return JSONPath 指向的值，路径不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T read(String json, String jsonPath) {
        return PARSE_CTX.parse(json).read(jsonPath);
    }

    /**
     * 从 JSON 字符串中读取指定类型的值。
     *
     * <p>常用于读取标量值，自动将 JSON 节点映射为 Java 类型：</p>
     * <pre>{@code
     * String name = JsonPathUtils.read(json, "$.name", String.class);
     * double price = JsonPathUtils.read(json, "$.price", double.class);
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式
     * @param type     目标类型
     * @param <T>      返回值类型
     * @return 解析后的值，路径不存在时返回 null
     */
    public static <T> T read(String json, String jsonPath, Class<T> type) {
        return PARSE_CTX.parse(json).read(jsonPath, type);
    }

    /**
     * 从 JSON 字符串中读取值，使用 {@link TypeRef} 处理泛型类型。
     *
     * <p>适用于复杂泛型，如 {@code List<User>}、{@code Map<String, Object>}：</p>
     * <pre>{@code
     * List<String> titles = JsonPathUtils.read(json, "$..title",
     *         new TypeRef<List<String>>() {});
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式
     * @param typeRef  类型引用
     * @param <T>      返回值类型
     * @return 解析后的值
     */
    public static <T> T read(String json, String jsonPath, TypeRef<T> typeRef) {
        return PARSE_CTX.parse(json).read(jsonPath, typeRef);
    }

    /**
     * 从 JSON 输入流中读取 JSONPath 表达式的值。
     *
     * @param input    JSON 输入流（不会自动关闭，调用方负责关闭）
     * @param jsonPath JSONPath 表达式
     * @param <T>      返回值类型
     * @return JSONPath 指向的值
     */
    @SuppressWarnings("unchecked")
    public static <T> T read(InputStream input, String jsonPath) {
        return PARSE_CTX.parse(input).read(jsonPath);
    }

    /**
     * 从 JSON 字符串中读取 JSONPath 表达式指向的列表。
     *
     * <p>等效于 {@link #read(String, String)}，但明确返回 {@link List} 类型：</p>
     * <pre>{@code
     * List<String> titles = JsonPathUtils.readList(json, "$.store.book[*].title");
     * List<Map<String, Object>> books = JsonPathUtils.readList(json, "$.store.book[*]");
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式，应指向数组或产生多个结果的路径
     * @param <T>      列表元素类型
     * @return 解析后的列表，路径不存在返回空列表
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> readList(String json, String jsonPath) {
        return PARSE_CTX.parse(json).read(jsonPath);
    }

    /**
     * 从 JSON 字符串中读取 JSONPath 表达式指向的值，以 Map 形式返回。
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式，应指向 JSON 对象
     * @return 解析后的 Map，路径不存在返回空 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json, String jsonPath) {
        return PARSE_CTX.parse(json).read(jsonPath);
    }

    // ==================== 写入操作 ====================

    /**
     * 在 JSON 字符串中设置指定路径的值，返回新的 JSON 字符串。
     *
     * <p>如果路径不存在，JSONPath 会根据配置尝试创建中间节点。</p>
     *
     * <pre>{@code
     * String result = JsonPathUtils.set(json, "$.store.book[0].price", 35.0);
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式
     * @param value    要设置的新值
     * @return 设置后的 JSON 字符串
     */
    public static String set(String json, String jsonPath, Object value) {
        return PARSE_CTX.parse(json).set(jsonPath, value).jsonString();
    }

    /**
     * 在 JSON 字符串中删除指定路径的值，返回新的 JSON 字符串。
     *
     * <pre>{@code
     * String result = JsonPathUtils.delete(json, "$.store.book[1]");
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式
     * @return 删除后的 JSON 字符串
     */
    public static String delete(String json, String jsonPath) {
        return PARSE_CTX.parse(json).delete(jsonPath).jsonString();
    }

    /**
     * 在 JSON 数组末尾追加元素。
     *
     * <pre>{@code
     * // 在 book 数组末尾添加一本新书
     * String result = JsonPathUtils.add(json,
     *         "$.store.book",
     *         Map.of("title", "Python", "price", 39.9));
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式，应指向数组
     * @param value    要添加的元素
     * @return 添加后的 JSON 字符串
     */
    public static String add(String json, String jsonPath, Object value) {
        return PARSE_CTX.parse(json).add(jsonPath, value).jsonString();
    }

    /**
     * 在 JSON 对象中设置键值对（相当于 Map.put）。
     *
     * <pre>{@code
     * String result = JsonPathUtils.put(json,
     *         "$.store", "discount", 0.8);
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式，应指向 JSON 对象
     * @param key      键名
     * @param value    键值
     * @return 设置后的 JSON 字符串
     */
    public static String put(String json, String jsonPath, String key, Object value) {
        return PARSE_CTX.parse(json).put(jsonPath, key, value).jsonString();
    }

    // ==================== 路径判断 ====================

    /**
     * 判断 JSONPath 路径在 JSON 中是否存在。
     *
     * <pre>{@code
     * if (JsonPathUtils.isExist(json, "$.store.book[0]")) {
     *     // 存在
     * }
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式
     * @return 路径存在返回 true，否则返回 false
     */
    public static boolean isExist(String json, String jsonPath) {
        try {
            return PARSE_CTX.parse(json).read(jsonPath) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 JSONPath 路径对应的值长度（适用于数组或字符串）。
     *
     * <pre>{@code
     * int count = JsonPathUtils.length(json, "$.store.book[*]");
     * int len = JsonPathUtils.length(json, "$.store.book[0].title");
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式，应指向数组或字符串
     * @return 数组长度或字符串长度，路径不存在返回 0
     */
    public static int length(String json, String jsonPath) {
        Object value = read(json, jsonPath);
        if (value == null) {
            return 0;
        }
        if (value instanceof List) {
            return ((List<?>) value).size();
        }
        if (value instanceof String) {
            return ((String) value).length();
        }
        return 1;
    }

    // ==================== 高级操作 ====================

    /**
     * 解析 JSON 字符串为 {@link DocumentContext}，支持链式读写操作。
     *
     * <p>适用于需要多次修改 JSON 的场景：</p>
     * <pre>{@code
     * DocumentContext ctx = JsonPathUtils.parse(json);
     * ctx.set("$.name", "New Name")
     *    .delete("$.temp")
     *    .add("$.items", newItem);
     * String result = ctx.jsonString();
     * }</pre>
     *
     * @param json JSON 字符串
     * @return DocumentContext，支持链式调用
     */
    public static DocumentContext parse(String json) {
        return PARSE_CTX.parse(json);
    }

    /**
     * 使用严格模式读取 JSONPath 值（路径不存在时抛出异常而非返回 null）。
     *
     * <pre>{@code
     * try {
     *     String val = JsonPathUtils.readStrict(json, "$.required.field");
     * } catch (PathNotFoundException e) {
     *     // 路径不存在
     * }
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式
     * @param <T>      返回值类型
     * @return JSONPath 指向的值
     * @throws com.jayway.jsonpath.PathNotFoundException 路径不存在时抛出
     */
    @SuppressWarnings("unchecked")
    public static <T> T readStrict(String json, String jsonPath) {
        return JsonPath.using(STRICT_CONFIG).parse(json).read(jsonPath);
    }

    /**
     * 使用自定义配置读取 JSONPath 值。
     *
     * <pre>{@code
     * Configuration config = Configuration.builder()
     *         .options(Option.ALWAYS_RETURN_LIST)
     *         .build();
     * List<Object> result = JsonPathUtils.read(json, "$..items", config);
     * }</pre>
     *
     * @param json     JSON 字符串
     * @param jsonPath JSONPath 表达式
     * @param config   自定义配置
     * @param <T>      返回值类型
     * @return JSONPath 指向的值
     */
    @SuppressWarnings("unchecked")
    public static <T> T read(String json, String jsonPath, Configuration config) {
        return JsonPath.using(config).parse(json).read(jsonPath);
    }

    /**
     * 获取默认配置实例。
     *
     * @return 默认 Configuration
     */
    public static Configuration getDefaultConfig() {
        return DEFAULT_CONFIG;
    }
}
