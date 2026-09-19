package com.chua.common.support.lang.json;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * JSON5 工具类
 * <p>
 * 提供对 JSON5 格式的支持，扩展了标准 JSON 的解析能力。
 * JSON5 相比标准 JSON 的主要特性包括：
 * 1. 允许使用单引号 (') 代替双引号 (") 包裹字符串和键名。
 * 2. 允许对象或数组末尾存在尾随逗号 (Trailing Comma)。
 * 3. 支持注释：单行注释 (//) 和多行注释 (/* *\/)。
 * 4. 允许键名不加引号 (Unquoted Field Names)。
 * 5. 允许数字前导零 (Numeric Leading Zeros)。
 * 6. 支持非数字数值 (如 Infinity, -Infinity, NaN)。
 * </p>
 * <p>
 * 实现说明：本类为 {@link Json} 门面的 JSON5 语义视图，所有解析 / 序列化操作
 * 完全委托给 {@link Json}（即当前 {@link JsonProvider} SPI 实现）。默认的
 * {@link JacksonJsonProvider} 已原生启用全部 JSON5 特性（注释、单引号、未加引号键名、
 * 尾随逗号、前导零、非数字数值等），因此默认行为与改造前一致。
 * </p>
 * <p>
 * 统一切换：调用 {@link #setImplementation(JsonProvider)} 或 {@link Json#setImplementation(JsonProvider)}
 * 切换实现后，本类行为立即跟随，实现 {@code Json5} 与 {@code Json} 门面的统一 SPI 切换。
 * </p>
 * <p>
 * 注意：JSON5 扩展语法（注释、单引号、未加引号键名、尾随逗号等）的解析能力取决于当前
 * 激活的 {@link JsonProvider} 实现。仅默认的 {@link JacksonJsonProvider} 原生启用全部
 * JSON5 特性；切换为 Gson / Fory 等实现后，只有注释可经 {@link #preprocessJson5} 移除，
 * 单引号 / 未加引号键名等仍需调用方自行转换为严格 JSON 语法。
 * </p>
 *
 * @author CH
 * @since 2024/8/7
 */
public class Json5 {

    /**
     * 私有构造器，禁止实例化工具类
     */
    private Json5() {
    }

    /**
     * 全局替换当前 JSON 实现（透传 {@link Json#setImplementation(JsonProvider)}）。
     *
     * <p>替换后所有 {@code Json5.*} 静态方法立即委托给新实现，与 {@link Json} 门面保持统一。</p>
     *
     * @param provider 新的 JSON 实现，不能为 null
     * @throws IllegalArgumentException 当 provider 为 null 时
     */
    public static void setImplementation(JsonProvider provider) {
        Json.setImplementation(provider);
    }

    /**
     * 获取当前生效的 JSON 实现（透传 {@link Json#getImplementation()}）。
     *
     * @return 当前 JSON 实现
     */
    public static JsonProvider getImplementation() {
        return Json.getImplementation();
    }

    /**
     * 将 JSON5 格式的字符串反序列化为指定类型的列表
     *
     * @param json  JSON5 格式的字符串内容
     * @param clazz 目标列表元素的类型
     * @param <T>   列表中元素的泛型类型
     * @return 反序列化后的 List 对象
     * @throws RuntimeException 如果解析失败则抛出运行时异常
     */
    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        return Json.fromJsonToList(json, clazz);
    }

    /**
     * 将 JSON5 格式的字符串反序列化为指定的 Java 对象
     *
     * @param json5  JSON5 格式的字符串内容
     * @param target 目标对象的 Class 类型
     * @param <T>    目标对象的泛型类型
     * @return 反序列化后的对象，若输入为空则返回 null
     */
    public static <T> T fromJson(String json5, Class<T> target) {
        if (json5 == null || json5.trim().isEmpty()) {
            return null;
        }
        return Json.fromJson(json5, target);
    }

    /**
     * 将 JSON5 格式的字符串解析为 JsonObject 对象
     *
     * <p>如果解析失败，返回一个空的 JsonObject 实例，避免程序中断。</p>
     *
     * @param json5 JSON5 格式的字符串内容
     * @return JsonObject 对象，解析失败时返回空对象
     */
    public static JsonObject getJsonObject(String json5) {
        try {
            JsonObject result = Json.getJsonObject(json5);
            return result != null ? result : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /**
     * 将 JSON5 格式的字符串解析为 JsonArray 对象
     *
     * <p>如果解析失败，返回一个空的 JsonArray 实例，避免程序中断。</p>
     *
     * @param json5 JSON5 格式的字符串内容
     * @return JsonArray 对象，解析失败时返回空数组
     */
    public static JsonArray getJsonArray(String json5) {
        try {
            JsonArray result = Json.getJsonArray(json5);
            return result != null ? result : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    /**
     * 将字节数组转换为 UTF-8 字符串后，反序列化为指定类型的对象
     *
     * @param bytes  包含 JSON5 数据的字节数组
     * @param target 目标对象的 Class 类型
     * @param <T>    目标对象的泛型类型
     * @return 反序列化后的对象
     */
    public static <T> T fromJson(byte[] bytes, Class<T> target) {
        return Json.fromJson(bytes, target);
    }

    /**
     * 将字节数组根据指定字符集转换为字符串后，解析为 JsonObject
     *
     * @param bytes   包含 JSON5 数据的字节数组
     * @param charset 字符集编码
     * @return 解析后的 JsonObject
     */
    public static JsonObject fromJson(byte[] bytes, Charset charset) {
        JsonObject result = Json.fromJson(bytes, charset);
        return result != null ? result : new JsonObject();
    }

    /**
     * 使用 InputStreamReader 读取并反序列化为指定类型的对象
     *
     * @param inputStreamReader 输入流读取器
     * @param target            目标对象的 Class 类型
     * @param <T>               目标对象的泛型类型
     * @return 反序列化后的对象
     */
    public static <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target) {
        return Json.fromJson(inputStreamReader, target);
    }

    /**
     * 使用 InputStream 读取并反序列化为指定类型的对象
     *
     * @param inputStream 输入流
     * @param target      目标对象的 Class 类型
     * @param <T>         目标对象的泛型类型
     * @return 反序列化后的对象
     */
    public static <T> T fromJson(InputStream inputStream, Class<T> target) {
        return Json.fromJson(inputStream, target);
    }

    /**
     * 将 JSON5 格式的字符串解析为通用的 Map&lt;String, Object&gt;
     *
     * <p>适用于结构不确定的 JSON5 对象解析场景。</p>
     *
     * @param json5 JSON5 格式的字符串内容
     * @return 解析后的 Map 对象
     */
    public static Map<String, Object> fromJson(String json5) {
        return Json.fromJson(json5);
    }

    /**
     * 将 Java 对象序列化为 JSON 字符串
     *
     * <p>默认的 Jackson 实现输出为标准 JSON；若对象中包含特殊属性（如 NaN），
     * 配置了 JSON5 特性的 Mapper 会保留这些值。</p>
     *
     * @param object 需要序列化的 Java 对象
     * @return JSON 格式的字符串
     */
    public static String toJson(Object object) {
        return Json.toJson(object);
    }

    /**
     * 将 Java 对象序列化为 JSON 字节数组
     *
     * @param object 需要序列化的 Java 对象
     * @return JSON 格式的字节数组
     */
    public static byte[] toJsonByte(Object object) {
        return Json.toJsonByte(object);
    }

    /**
     * 验证给定的字符串是否为有效的 JSON 格式
     *
     * @param jsonStr 待验证的字符串
     * @return 如果是有效的 JSON 返回 true，否则返回 false
     */
    public static boolean validate(String jsonStr) {
        return Json.validate(jsonStr);
    }

    /**
     * 判断给定字符串是否可能为 JSON5 格式
     * <p>
     * 判断逻辑：
     * 1. 检查是否以 { 开头且 } 结尾（对象）。
     * 2. 检查是否以 [ 开头且 ] 结尾（数组）。
     * 3. 调用 validate 方法进行完整语法校验。
     * </p>
     *
     * @param text 待检测的字符串
     * @return 如果符合 JSON5 特征返回 true，否则返回 false
     */
    public static boolean isJson5(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        text = text.trim();

        // 快速检查：是否以对象或数组符号开始和结束
        boolean startsWithObject = text.startsWith("{") && text.endsWith("}");
        boolean startsWithArray = text.startsWith("[") && text.endsWith("]");

        // 如果形状匹配，或者完全校验通过，则认为是 JSON5
        return startsWithObject || startsWithArray || validate(text);
    }

    /**
     * 预处理 JSON5 字符串，移除其中的注释
     * <p>
     * 支持的注释移除规则：
     * 1. 移除单行注释：// 及其后面的内容。
     * 2. 移除多行注释：/* ... *\/ 及其中间的所有内容。
     * </p>
     *
     * @param json5 原始 JSON5 字符串
     * @return 去除注释后的纯净 JSON5 字符串
     */
    public static String preprocessJson5(String json5) {
        if (json5 == null) {
            return null;
        }

        // 移除单行注释 (// ...)
        json5 = json5.replaceAll("//.*", "");

        // 移除多行注释 (/* ... */)
        json5 = json5.replaceAll("/\\*[\\s\\S]*?\\*/", "");

        return json5.trim();
    }
}
