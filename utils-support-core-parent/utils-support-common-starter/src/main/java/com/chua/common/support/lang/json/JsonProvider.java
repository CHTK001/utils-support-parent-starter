package com.chua.common.support.lang.json;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * JSON 序列化与反序列化服务提供接口（SPI）。
 *
 * <p>定义了 JSON 编解码的统一实例契约，由具体实现提供能力，如 {@link JacksonJsonProvider}（Jackson 实现，默认）、
 * 未来的 GsonJsonProvider（Gson 实现）、ForyJsonProvider 等。{@link Json} 静态门面类持有当前生效的实现，
 * 并可通过 {@link Json#setImplementation(JsonProvider)} 在运行时全局替换，实现零侵入切换。
 *
 * <p>设计约束：本接口仅依赖 JDK 类型与 common-starter 自有类型（{@link JsonObject}、{@link JsonArray}、
 * {@link JsonNode}、{@link JsonReference}），不引入任何第三方 JSON 库类型，以便任意实现接入。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface JsonProvider {

    /**
     * 将 JSON 字符串解析为 JsonNode 对象，提供统一的树形遍历 API。
     *
     * @param json JSON 字符串（标准 JSON 或 JSON5 均可）
     * @return JsonNode 对象，解析失败时返回空 JsonObject 的 JsonNode
     */
    JsonNode parse(String json);

    /**
     * 将字节数组形式的 JSON 解析为 JsonNode 对象。
     *
     * @param json JSON 字节数组
     * @return JsonNode 对象
     */
    JsonNode parse(byte[] json);

    /**
     * 创建一个空的 JSON 对象节点，支持链式构建。
     *
     * @return 包装空 JsonObject 的 JsonNode，支持链式 put 操作
     */
    JsonNode build();

    /**
     * 创建一个空的 JSON 数组节点，支持链式构建。
     *
     * @return 包装空 JsonArray 的 JsonNode，支持链式 add 操作
     */
    JsonNode buildArray();

    /**
     * 将 JSON 字符串解析为 JsonObject 对象，解析失败时返回空 JsonObject。
     *
     * @param json JSON 字符串
     * @return JsonObject 对象
     */
    JsonObject getJsonObject(String json);

    /**
     * 根据 JSON 字符串创建 JsonReference 对象，用于后续链式操作。
     *
     * @param json JSON 字符串
     * @return JsonReference 对象
     */
    JsonReference getJsonReference(String json);

    /**
     * 将字节数组形式的 JSON 解析为 JsonArray 对象。
     *
     * @param jsonArray 字节数组
     * @return JsonArray 对象
     */
    JsonArray getJsonArray(byte[] jsonArray);

    /**
     * 将 JSON 字符串解析为 JsonArray 对象，解析失败时返回空 JsonArray。
     *
     * @param json JSON 字符串
     * @return JsonArray 对象
     */
    JsonArray getJsonArray(String json);

    /**
     * 将字节数组形式的 JSON 解析为 JsonObject 对象。
     *
     * @param bytes JSON 字节数组
     * @return JsonObject 对象
     */
    JsonObject getJsonObject(byte[] bytes);

    /**
     * 通过 InputStreamReader 将 JSON 解析为 JsonObject 对象。
     *
     * @param inputStreamReader 输入流读取器
     * @return JsonObject 对象
     */
    JsonObject getJsonObject(InputStreamReader inputStreamReader);

    /**
     * 通过 InputStream 将 JSON 解析为 JsonObject 对象，默认使用 UTF-8 编码。
     *
     * @param inputStream 输入流
     * @return JsonObject 对象
     */
    JsonObject getJsonObject(InputStream inputStream);

    /**
     * 通过 InputStream 将 JSON 解析为 JsonObject 对象，指定字符集。
     *
     * @param inputStream 输入流
     * @param charset     字符集名称
     * @return JsonObject 对象
     */
    JsonObject getJsonObject(InputStream inputStream, String charset);

    /**
     * 从 InputStream 读取 JSON 并转换为指定类型的 List。
     *
     * @param inputStream 输入流
     * @param targetType  目标元素类型
     * @param <T>         泛型类型
     * @return List 集合
     */
    <T> List<T> fromJsonToList(InputStream inputStream, Class<T> targetType);

    /**
     * 将 JSON 字符串转换为指定类型的 List 集合。
     *
     * @param json       JSON 字符串
     * @param targetType 列表元素的类型
     * @param <T>        泛型类型
     * @return List 集合
     */
    <T> List<T> fromJsonToList(String json, Class<T> targetType);

    /**
     * 将 JSON 字符串反序列化为指定类型的对象。
     *
     * @param json   JSON 字符串
     * @param target 目标类型
     * @param <T>    泛型类型
     * @return 目标对象
     */
    <T> T fromJson(String json, Class<T> target);

    /**
     * 将字节数组反序列化为指定类型的对象。
     *
     * @param bytes  JSON 字节数组
     * @param target 目标类型
     * @param <T>    泛型类型
     * @return 目标对象
     */
    <T> T fromJson(byte[] bytes, Class<T> target);

    /**
     * 将字节数组和指定字符集转换为 JsonObject。
     *
     * @param bytes   JSON 字节数组
     * @param charset 字符集
     * @return JsonObject 对象
     */
    JsonObject fromJson(byte[] bytes, Charset charset);

    /**
     * 通过 InputStreamReader 将 JSON 反序列化为指定类型的对象。
     *
     * @param inputStreamReader 输入流读取器
     * @param target            目标类型
     * @param <T>               泛型类型
     * @return 目标对象
     */
    <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target);

    /**
     * 通过 InputStream 将 JSON 反序列化为指定类型的对象。
     *
     * @param inputStream 输入流
     * @param target      目标类型
     * @param <T>         泛型类型
     * @return 目标对象
     */
    <T> T fromJson(InputStream inputStream, Class<T> target);

    /**
     * 将对象序列化为 JSON 字符串，并排除指定的字段。
     *
     * @param object  待序列化的对象
     * @param ignores 需要忽略的字段名数组
     * @return JSON 字符串
     */
    String toJson(Object object, String... ignores);

    /**
     * 将对象序列化为格式美观（带缩进）的 JSON 字符串。
     *
     * @param object 待序列化的对象
     * @return 美化后的 JSON 字符串
     */
    String prettyFormat(Object object);

    /**
     * 别名方法：将对象序列化为格式美观的 JSON 字符串。
     *
     * @param obj 待序列化的对象
     * @return 美化后的 JSON 字符串
     */
    String toPrettyJson(Object obj);

    /**
     * 将对象序列化为 JSON 字节数组。
     *
     * @param object 待序列化的对象
     * @return JSON 字节数组
     */
    byte[] toJsonByte(Object object);

    /**
     * 检查给定对象是否为有效的 JSON 字符串。
     *
     * @param ext 待检查的对象
     * @return 是否为 JSON 字符串
     */
    boolean isJson(Object ext);

    /**
     * 将 JSON 字符串转换为 List 对象。
     *
     * @param string JSON 字符串
     * @return List 对象
     */
    List<?> toList(String string);

    /**
     * 将对象序列化为 JSON 字节数组（别名方法）。
     *
     * @param object 待序列化的对象
     * @return JSON 字节数组
     */
    byte[] toJSONBytes(Object object);

    /**
     * 将对象序列化为 JSON 字符串（别名方法）。
     *
     * @param object 待序列化的对象
     * @return JSON 字符串
     */
    String toJSONString(Object object);

    /**
     * 验证给定的字符串是否为合法的 JSON 格式。
     *
     * @param jsonStr JSON 字符串
     * @return 是否合法
     */
    boolean validate(String jsonStr);

    /**
     * 将 JSON 字符串转换为 Map&lt;String, Object&gt;。
     *
     * @param string JSON 字符串
     * @return Map 对象
     */
    Map<String, Object> fromJson(String string);

    /**
     * 将 JSON 字符串根据 Type 反序列化为指定泛型类型的对象。
     *
     * @param stringValue JSON 字符串
     * @param type        类型引用
     * @param <T>         泛型类型
     * @return 目标对象
     */
    <T> T fromJson(String stringValue, Type type);

    /**
     * 从 Reader 读取 JSON 并反序列化为指定类型的对象。
     *
     * @param reader JSON Reader
     * @param target 目标类型
     * @param <T>    泛型类型
     * @return 目标对象
     */
    <T> T fromJson(Reader reader, Class<T> target);

    /**
     * 将对象序列化为 JSON 并写入 Writer。
     *
     * @param object 待序列化的对象
     * @param writer 输出 Writer
     */
    void toJson(Object object, Writer writer);

    /**
     * 从 InputStream 读取 JSON 并根据 Type 反序列化为指定泛型类型的对象。
     *
     * @param stream JSON 输入流
     * @param type   类型引用
     * @param <T>    泛型类型
     * @return 目标对象
     */
    <T> T fromJson(InputStream stream, Type type);

    /**
     * 从 Reader 读取 JSON 并根据 Type 反序列化为指定泛型类型的对象。
     *
     * @param reader JSON Reader
     * @param type   类型引用
     * @param <T>    泛型类型
     * @return 目标对象
     */
    <T> T fromJson(Reader reader, Type type);
}
