package com.chua.common.support.lang.json;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具门面类，提供便捷的 JSON 序列化和反序列化功能。
 *
 * <p>采用「静态门面 + 可插拔实现」设计：本类持有当前生效的 {@link JsonProvider} 实现，
 * 默认实现通过 SPI 机制自动发现（见 {@link ServiceProvider}）：优先使用标记
 * {@link SpiDefault} 的 {@link JacksonJsonProvider}（Jackson 实现），若 SPI 发现失败则回退到
 * {@code new JacksonJsonProvider()}。所有静态方法委托给当前实现，因此既有调用方式
 * （如 {@code Json.toJson(...)}、{@code Json.fromJson(...)}）完全不受影响。
 *
 * <p>如需全局切换实现（例如切换到 Gson、Fory 等），可调用 {@link #setImplementation(JsonProvider)}
 * 在运行时替换，例如：
 * <pre>{@code
 * Json.setImplementation(new GsonJson());
 * String json = Json.toJson(object);
 * }</pre>
 *
 * <p>注意：{@link #getMapper()} 与 {@link #fromJson(String, TypeReference)} 等返回/接收
 * Jackson 专属类型的重载，仅当当前实现为 {@link JacksonJsonProvider} 时可用；其余实现请使用
 * {@link #fromJson(String, Class)} 等通用方法。
 *
 * @author CH
 * @since 1.0.0
 */
public class Json {

    /**
     * 当前生效的 JSON 实现，默认通过 SPI 发现（回退到 Jackson 实现）
     */
    private static volatile JsonProvider implementation = loadDefaultImplementation();

    /**
     * 通过 SPI 机制加载默认的 JSON 实现。
     *
     * <p>优先使用 {@link ServiceProvider} 发现的默认实现（标记 {@link SpiDefault} 的实现类），
     * 若 SPI 发现失败或未注册任何实现，则回退到 {@link JacksonJsonProvider}，确保门面始终可用。</p>
     *
     * @return 默认 JSON 实现
     */
    private static JsonProvider loadDefaultImplementation() {
        JsonProvider provider = ServiceProvider.of(JsonProvider.class).getDefault();
        return provider != null ? provider : new JacksonJsonProvider();
    }

    /**
     * 私有构造器，禁止实例化工具类
     */
    private Json() {
    }

    /**
     * 全局替换当前 JSON 实现。
     *
     * <p>替换后所有 {@code Json.*} 静态方法立即委托给新实现。用于在 Jackson、Gson、Fory 等
     * 实现之间切换。传入 {@code null} 时抛出异常，避免破坏全局状态。</p>
     *
     * @param provider 新的 JSON 实现，不能为 null
     * @throws IllegalArgumentException 当 provider 为 null 时
     */
    public static void setImplementation(JsonProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("JsonProvider 不能为空");
        }
        implementation = provider;
    }

    /**
     * 获取当前生效的 JSON 实现。
     *
     * @return 当前 JSON 实现
     */
    public static JsonProvider getImplementation() {
        return implementation;
    }

    /**
     * 获取默认的 Jackson ObjectMapper 实例（懒加载）。
     *
     * <p>仅当当前实现为 {@link JacksonJsonProvider} 时可获取；否则抛出 {@link UnsupportedOperationException}。</p>
     *
     * @return ObjectMapper 实例
     * @throws UnsupportedOperationException 当前实现不支持 Jackson ObjectMapper 时
     */
    public static ObjectMapper getMapper() {
        JsonProvider current = implementation;
        if (current instanceof JacksonJsonProvider) {
            return JacksonJsonProvider.getMapper();
        }
        throw new UnsupportedOperationException(
                "当前 Json 实现不支持获取 Jackson ObjectMapper: " + current.getClass().getName());
    }

    /**
     * 创建一个空的 {@link JsonObject} 节点（委托当前 SPI 实现的节点工厂）。
     *
     * <p>节点类型随当前实现切换：默认 {@link JacksonJsonProvider} 返回通用 {@link JsonObject}，
     * 切换 Gson / Fory 实现后返回其各自的节点子类（如 GsonJsonObject）。</p>
     *
     * @return 空 JsonObject 节点
     * @since 4.0.0.42
     */
    public static JsonObject createJsonObject() {
        return implementation.createJsonObject();
    }

    /**
     * 基于已有 Map 创建 {@link JsonObject} 节点（委托当前 SPI 实现的节点工厂）。
     *
     * @param map 源数据 Map，可为 null
     * @return 包含源数据的 JsonObject 节点
     * @since 4.0.0.42
     */
    public static JsonObject createJsonObject(Map map) {
        return implementation.createJsonObject(map);
    }

    /**
     * 创建一个空的 {@link JsonArray} 节点（委托当前 SPI 实现的节点工厂）。
     *
     * <p>节点类型随当前实现切换：默认 {@link JacksonJsonProvider} 返回通用 {@link JsonArray}，
     * 切换 Gson / Fory 实现后返回其各自的节点子类（如 GsonJsonArray）。</p>
     *
     * @return 空 JsonArray 节点
     * @since 4.0.0.42
     */
    public static JsonArray createJsonArray() {
        return implementation.createJsonArray();
    }

    /**
     * 基于已有 Collection 创建 {@link JsonArray} 节点（委托当前 SPI 实现的节点工厂）。
     *
     * @param collection 源集合，可为 null
     * @return 包含源元素的 JsonArray 节点
     * @since 4.0.0.42
     */
    public static JsonArray createJsonArray(Collection collection) {
        return implementation.createJsonArray(collection);
    }

    /**
     * 基于原始值创建 {@link JsonNode} 节点（委托当前 SPI 实现的节点工厂）。
     *
     * <p>节点类型随当前实现切换：默认 {@link JacksonJsonProvider} 返回通用 {@link JsonNode}，
     * 切换 Gson / Fory 实现后返回其各自的节点子类（如 GsonJsonNode）。</p>
     *
     * @param value 原始 JSON 值（Map、List、String、Number、Boolean 或 null）
     * @return JsonNode 节点
     * @since 4.0.0.42
     */
    public static JsonNode createJsonNode(Object value) {
        return implementation.createJsonNode(value);
    }

    /**
     * 将 JSON 字符串解析为 JsonNode 对象，提供统一的树形遍历 API。
     *
     * @param json JSON 字符串（标准 JSON 或 JSON5 均可）
     * @return JsonNode 对象，解析失败时返回空 JsonObject 的 JsonNode
     */
    public static JsonNode parse(String json) {
        return implementation.parse(json);
    }

    /**
     * 将字节数组形式的 JSON 解析为 JsonNode 对象。
     *
     * @param json JSON 字节数组
     * @return JsonNode 对象
     */
    public static JsonNode parse(byte[] json) {
        return implementation.parse(json);
    }

    /**
     * 创建一个空的 JSON 对象节点，支持链式构建。
     *
     * @return 包装空 JsonObject 的 JsonNode，支持链式 put 操作
     */
    public static JsonNode build() {
        return implementation.build();
    }

    /**
     * 创建一个空的 JSON 数组节点，支持链式构建。
     *
     * @return 包装空 JsonArray 的 JsonNode，支持链式 add 操作
     */
    public static JsonNode buildArray() {
        return implementation.buildArray();
    }

    /**
     * 将 JSON 字符串解析为 JsonObject 对象，解析失败时返回空 JsonObject。
     *
     * @param json JSON 字符串
     * @return JsonObject 对象
     */
    public static JsonObject getJsonObject(String json) {
        return implementation.getJsonObject(json);
    }

    /**
     * 根据 JSON 字符串创建 JsonReference 对象，用于后续链式操作。
     *
     * @param json JSON 字符串
     * @return JsonReference 对象
     */
    public static JsonReference getJsonReference(String json) {
        return implementation.getJsonReference(json);
    }

    /**
     * 将字节数组形式的 JSON 解析为 JsonArray 对象。
     *
     * @param jsonArray 字节数组
     * @return JsonArray 对象
     */
    public static JsonArray getJsonArray(byte[] jsonArray) {
        return implementation.getJsonArray(jsonArray);
    }

    /**
     * 将 JSON 字符串解析为 JsonArray 对象，解析失败时返回空 JsonArray。
     *
     * @param json JSON 字符串
     * @return JsonArray 对象
     */
    public static JsonArray getJsonArray(String json) {
        return implementation.getJsonArray(json);
    }

    /**
     * 将字节数组形式的 JSON 解析为 JsonObject 对象。
     *
     * @param bytes JSON 字节数组
     * @return JsonObject 对象
     */
    public static JsonObject getJsonObject(byte[] bytes) {
        return implementation.getJsonObject(bytes);
    }

    /**
     * 通过 InputStreamReader 将 JSON 解析为 JsonObject 对象。
     *
     * @param inputStreamReader 输入流读取器
     * @return JsonObject 对象
     */
    public static JsonObject getJsonObject(InputStreamReader inputStreamReader) {
        return implementation.getJsonObject(inputStreamReader);
    }

    /**
     * 通过 InputStream 将 JSON 解析为 JsonObject 对象，默认使用 UTF-8 编码。
     *
     * @param inputStream 输入流
     * @return JsonObject 对象
     */
    public static JsonObject getJsonObject(InputStream inputStream) {
        return implementation.getJsonObject(inputStream);
    }

    /**
     * 通过 InputStream 将 JSON 解析为 JsonObject 对象，指定字符集。
     *
     * @param inputStream 输入流
     * @param charset     字符集名称
     * @return JsonObject 对象
     */
    public static JsonObject getJsonObject(InputStream inputStream, String charset) {
        return implementation.getJsonObject(inputStream, charset);
    }

    /**
     * 从 InputStream 读取 JSON 并转换为指定类型的 List。
     *
     * @param inputStream 输入流
     * @param targetType  目标元素类型
     * @param <T>         泛型类型
     * @return List 集合
     */
    public static <T> List<T> fromJsonToList(InputStream inputStream, Class<T> targetType) {
        return implementation.fromJsonToList(inputStream, targetType);
    }

    /**
     * 将 JSON 字符串转换为指定类型的 List 集合。
     *
     * @param json       JSON 字符串
     * @param targetType 列表元素的类型
     * @param <T>        泛型类型
     * @return List 集合
     */
    public static <T> List<T> fromJsonToList(String json, Class<T> targetType) {
        return implementation.fromJsonToList(json, targetType);
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象。
     *
     * @param json   JSON 字符串
     * @param target 目标类型
     * @param <T>    泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(String json, Class<T> target) {
        return implementation.fromJson(json, target);
    }

    /**
     * 将字节数组反序列化为指定类型的对象。
     *
     * @param bytes  JSON 字节数组
     * @param target 目标类型
     * @param <T>    泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(byte[] bytes, Class<T> target) {
        return implementation.fromJson(bytes, target);
    }

    /**
     * 将字节数组和指定字符集转换为 JsonObject。
     *
     * @param bytes   JSON 字节数组
     * @param charset 字符集
     * @return JsonObject 对象
     */
    public static JsonObject fromJson(byte[] bytes, Charset charset) {
        return implementation.fromJson(bytes, charset);
    }

    /**
     * 通过 InputStreamReader 将 JSON 反序列化为指定类型的对象。
     *
     * @param inputStreamReader 输入流读取器
     * @param target            目标类型
     * @param <T>               泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target) {
        return implementation.fromJson(inputStreamReader, target);
    }

    /**
     * 通过 InputStream 将 JSON 反序列化为指定类型的对象。
     *
     * @param inputStream 输入流
     * @param target      目标类型
     * @param <T>         泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(InputStream inputStream, Class<T> target) {
        return implementation.fromJson(inputStream, target);
    }

    /**
     * 将对象序列化为 JSON 字符串，并排除指定的字段。
     *
     * @param object  待序列化的对象
     * @param ignores 需要忽略的字段名数组
     * @return JSON 字符串
     */
    public static String toJson(Object object, String... ignores) {
        return implementation.toJson(object, ignores);
    }

    /**
     * 将对象序列化为格式美观（带缩进）的 JSON 字符串。
     *
     * @param object 待序列化的对象
     * @return 美化后的 JSON 字符串
     */
    public static String prettyFormat(Object object) {
        return implementation.prettyFormat(object);
    }

    /**
     * 别名方法：将对象序列化为格式美观的 JSON 字符串。
     *
     * @param obj 待序列化的对象
     * @return 美化后的 JSON 字符串
     */
    public static String toPrettyJson(Object obj) {
        return implementation.toPrettyJson(obj);
    }

    /**
     * 将对象序列化为 JSON 字节数组。
     *
     * @param object 待序列化的对象
     * @return JSON 字节数组
     */
    public static byte[] toJsonByte(Object object) {
        return implementation.toJsonByte(object);
    }

    /**
     * 检查给定对象是否为有效的 JSON 字符串。
     *
     * @param ext 待检查的对象
     * @return 是否为 JSON 字符串
     */
    public static boolean isJson(Object ext) {
        return implementation.isJson(ext);
    }

    /**
     * 将 JSON 字符串转换为 List 对象。
     *
     * @param string JSON 字符串
     * @return List 对象
     */
    public static List<?> toList(String string) {
        return implementation.toList(string);
    }

    /**
     * 将对象序列化为 JSON 字节数组（别名方法）。
     *
     * @param object 待序列化的对象
     * @return JSON 字节数组
     */
    public static byte[] toJSONBytes(Object object) {
        return implementation.toJSONBytes(object);
    }

    /**
     * 将对象序列化为 JSON 字符串（别名方法）。
     *
     * @param object 待序列化的对象
     * @return JSON 字符串
     */
    public static String toJSONString(Object object) {
        return implementation.toJSONString(object);
    }

    /**
     * 验证给定的字符串是否为合法的 JSON 格式。
     *
     * @param jsonStr JSON 字符串
     * @return 是否合法
     */
    public static boolean validate(String jsonStr) {
        return implementation.validate(jsonStr);
    }

    /**
     * 将 JSON 字符串转换为 Map&lt;String, Object&gt;。
     *
     * @param string JSON 字符串
     * @return Map 对象
     */
    public static Map<String, Object> fromJson(String string) {
        return implementation.fromJson(string);
    }

    /**
     * 将 JSON 字符串根据 TypeReference 反序列化为指定泛型类型的对象。
     *
     * <p>TypeReference 为 Jackson 专属类型，仅当前实现为 {@link JacksonJsonProvider} 时可用；
     * 其余实现请使用 {@link #fromJson(String, Class)} 或 {@link #fromJson(String, java.lang.reflect.Type)}。</p>
     *
     * @param stringValue   JSON 字符串
     * @param typeReference 类型引用
     * @param <T>           泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(String stringValue, TypeReference<T> typeReference) {
        return implementation.fromJson(stringValue, typeReference.getType());
    }

    /**
     * 从 Reader 读取 JSON 并反序列化为指定类型的对象。
     *
     * @param reader JSON Reader
     * @param target 目标类型
     * @param <T>    泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(Reader reader, Class<T> target) {
        return implementation.fromJson(reader, target);
    }

    /**
     * 将对象序列化为 JSON 并写入 Writer。
     *
     * @param object 待序列化的对象
     * @param writer 输出 Writer
     */
    public static void toJson(Object object, Writer writer) {
        implementation.toJson(object, writer);
    }

    /**
     * 从 InputStream 读取 JSON 并根据 TypeReference 反序列化为指定泛型类型的对象。
     *
     * <p>TypeReference 为 Jackson 专属类型，仅当前实现为 {@link JacksonJsonProvider} 时可用。</p>
     *
     * @param stream        JSON 输入流
     * @param typeReference 类型引用
     * @param <T>           泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(InputStream stream, TypeReference<T> typeReference) {
        return implementation.fromJson(stream, typeReference.getType());
    }

    /**
     * 从 Reader 读取 JSON 并根据 TypeReference 反序列化为指定泛型类型的对象。
     *
     * <p>TypeReference 为 Jackson 专属类型，仅当前实现为 {@link JacksonJsonProvider} 时可用。</p>
     *
     * @param reader        JSON Reader
     * @param typeReference 类型引用
     * @param <T>           泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(Reader reader, TypeReference<T> typeReference) {
        return implementation.fromJson(reader, typeReference.getType());
    }

    /**
     * 将 JSON 字符串根据 Type 反序列化为指定泛型类型的对象。
     *
     * @param stringValue JSON 字符串
     * @param type        类型引用
     * @param <T>         泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(String stringValue, Type type) {
        return implementation.fromJson(stringValue, type);
    }

    /**
     * 从 InputStream 读取 JSON 并根据 Type 反序列化为指定泛型类型的对象。
     *
     * @param stream JSON 输入流
     * @param type   类型引用
     * @param <T>    泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(InputStream stream, Type type) {
        return implementation.fromJson(stream, type);
    }

    /**
     * 从 Reader 读取 JSON 并根据 Type 反序列化为指定泛型类型的对象。
     *
     * @param reader JSON Reader
     * @param type   类型引用
     * @param <T>    泛型类型
     * @return 目标对象
     */
    public static <T> T fromJson(Reader reader, Type type) {
        return implementation.fromJson(reader, type);
    }
}
