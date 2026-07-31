package com.chua.common.support.lang.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


/**
 * JSON5 工具类
 * <p>
 * 提供对 JSON5 格式的支持，扩展了标准 JSON 的解析能力。
 * JSON5 相比标准 JSON 的主要特性包括：
 * 1. 允许使用单引号 (') 代替双引号 (") 包裹字符串和键名。
 * 2. 允许对象或数组末尾存在尾随逗号 (Trailing Comma)。
 * 3. 支持注释：单行注释 (//) 和多行注释 ().
 * 4. 允许键名不加引号 (Unquoted Field Names)。
 * 5. 允许数字前导零 (Numeric Leading Zeros)。
 * 6. 支持非数字数值 (如 Infinity, -Infinity, NaN)。
 * </p>
 *
 * @author CH
 * @since 2024/8/7
 */
public class Json5 {

    private static final Logger log = LoggerFactory.getLogger(Json5.class);

    /**
     * 配置好的用于处理 JSON5 格式的 ObjectMapper 实例
     * 该实例已启用所有必要的 JSON5 特性解析功能
     */
    private static final ObjectMapper JSON5_MAPPER = createJson5Mapper();

    /**
     * 创建并配置支持 JSON5 特性的 ObjectMapper
     * <p>
     * 通过启用 Jackson 的特定 Parser Feature 来模拟 JSON5 的行为：
     * - ALLOW_COMMENTS: 允许解析 // 和 注释
     * - ALLOW_TRAILING_COMMA: 允许数组或对象元素后跟逗号
     * - ALLOW_SINGLE_QUOTES: 允许使用单引号作为字符串定界符
     * - ALLOW_UNQUOTED_FIELD_NAMES: 允许键名不加引号
     * - ALLOW_NUMERIC_LEADING_ZEROS: 允许数字以 0 开头 (如 09)
     * - ALLOW_NON_NUMERIC_NUMBERS: 允许 Infinity 和 NaN 等特殊值
     * </p>
     *
     * @return 配置完成的 ObjectMapper 实例
     */
    private static ObjectMapper createJson5Mapper() {
        JsonFactory factory = new JsonFactory();

        // 启用 JSON5 兼容的特性
        factory.enable(JsonParser.Feature.ALLOW_COMMENTS);
        factory.enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);
        factory.enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES);
        factory.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
        factory.enable(JsonParser.Feature.ALLOW_NUMERIC_LEADING_ZEROS);
        factory.enable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS);

        return new ObjectMapper(factory);
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
        try {
            // 使用 TypeReference 确保正确获取泛型信息
            return JSON5_MAPPER.readValue(json, new TypeReference<List<T>>() {
                @Override
                public Type getType() {
                    return clazz;
                }
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON5 列表解析失败", e);
        }
    }

    /**
     * 将 JSON5 格式的字符串反序列化为指定的 Java 对象
     * <p>
     * 实现机制：
     * 1. 首先尝试使用配置了 JSON5 特性的 ObjectMapper 进行解析。
     * 2. 如果 JSON5 解析失败（例如遇到不支持的特殊字符），则回退到标准 JSON 解析器尝试解析。
     * 3. 如果两者都失败，则抛出异常。
     * </p>
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

        try {
            // 优先尝试使用 JSON5 模式解析
            return JSON5_MAPPER.readValue(json5, target);
        } catch (JsonProcessingException e) {
            if (log.isDebugEnabled()) {
                log.debug("JSON5 解析失败，尝试回退到标准 JSON 解析：{}", e.getMessage());
            }

            // 回退方案：尝试使用标准 JSON 解析器解析
            try {
                return Json.fromJson(json5, target);
            } catch (Exception fallbackException) {
                log.error("JSON5 和标准 JSON 解析均失败：{}", fallbackException.getMessage());
                throw new RuntimeException("JSON5 解析失败且无法回退：" + e.getMessage(), e);
            }
        }
    }

    /**
     * 将 JSON5 格式的字符串解析为 JsonObject 对象
     * <p>
     * 如果解析失败，记录警告日志并返回一个空的 JsonObject 实例，避免程序中断。
     * </p>
     *
     * @param json5 JSON5 格式的字符串内容
     * @return JsonObject 对象，解析失败时返回空对象
     */
    public static JsonObject getJsonObject(String json5) {
        try {
            return fromJson(json5, JsonObject.class);
        } catch (Exception e) {
            log.warn("JSON5 解析为 JsonObject 失败，返回空对象：{}", e.getMessage());
            return new JsonObject();
        }
    }

    /**
     * 将 JSON5 格式的字符串解析为 JsonArray 对象
     * <p>
     * 如果解析失败，记录警告日志并返回一个空的 JsonArray 实例，避免程序中断。
     * </p>
     *
     * @param json5 JSON5 格式的字符串内容
     * @return JsonArray 对象，解析失败时返回空数组
     */
    public static JsonArray getJsonArray(String json5) {
        try {
            return fromJson(json5, JsonArray.class);
        } catch (Exception e) {
            log.warn("JSON5 解析为 JsonArray 失败，返回空数组：{}", e.getMessage());
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
        return fromJson(new String(bytes, StandardCharsets.UTF_8), target);
    }

    /**
     * 将字节数组根据指定字符集转换为字符串后，解析为 JsonObject
     *
     * @param bytes   包含 JSON5 数据的字节数组
     * @param charset 字符集编码
     * @return 解析后的 JsonObject
     */
    public static JsonObject fromJson(byte[] bytes, Charset charset) {
        return fromJson(new String(bytes, charset), JsonObject.class);
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
        try {
            return JSON5_MAPPER.readValue(inputStreamReader, target);
        } catch (IOException e) {
            log.error("从 InputStreamReader 读取 JSON5 数据失败：{}", e.getMessage());
            throw new RuntimeException("JSON5 解析异常", e);
        }
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
        try {
            return JSON5_MAPPER.readValue(inputStream, target);
        } catch (IOException e) {
            log.error("从 InputStream 读取 JSON5 数据失败：{}", e.getMessage());
            throw new RuntimeException("JSON5 解析异常", e);
        }
    }

    /**
     * 将 JSON5 格式的字符串解析为通用的 Map<String, Object>
     * <p>
     * 适用于结构不确定的 JSON5 对象解析场景。
     * </p>
     *
     * @param json5 JSON5 格式的字符串内容
     * @return 解析后的 Map 对象
     */
    public static Map<String, Object> fromJson(String json5) {
        try {
            return JSON5_MAPPER.readerForMapOf(Object.class).readValue(json5);
        } catch (JsonProcessingException e) {
            log.error("JSON5 解析为 Map 失败：{}", e.getMessage());
            throw new RuntimeException("JSON5 解析异常", e);
        }
    }

    /**
     * 将 Java 对象序列化为 JSON5 格式的字符串
     * <p>
     * 注意：此方法生成的输出是标准 JSON 格式，但使用了 JSON5 的 ObjectMapper，
     * 因此如果对象中包含特殊属性（如 NaN），可能会保留这些值。
     * </p>
     *
     * @param object 需要序列化的 Java 对象
     * @return JSON5 格式的字符串
     */
    public static String toJson(Object object) {
        try {
            return JSON5_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Java 对象序列化为 JSON5 失败：{}", e.getMessage());
            throw new RuntimeException("JSON5 序列化异常", e);
        }
    }

    /**
     * 将 Java 对象序列化为 JSON5 格式的字节数组
     *
     * @param object 需要序列化的 Java 对象
     * @return JSON5 格式的字节数组
     */
    public static byte[] toJsonByte(Object object) {
        try {
            return JSON5_MAPPER.writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            log.error("Java 对象序列化为 JSON5 字节数组失败：{}", e.getMessage());
            throw new RuntimeException("JSON5 序列化异常", e);
        }
    }

    /**
     * 验证给定的字符串是否为有效的 JSON5 格式
     *
     * @param json5Str 待验证的字符串
     * @return 如果是有效的 JSON5 返回 true，否则返回 false
     */
    public static boolean validate(String json5Str) {
        try {
            // 尝试解析树节点，成功则说明格式有效
            JSON5_MAPPER.readTree(json5Str);
            return true;
        } catch (Exception e) {
            return false;
        }
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
     * 2. 移除多行注释： 及其中间的所有内容。
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
