package com.chua.fastjson.support.json;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.lang.json.JsonProvider;
import com.chua.common.support.lang.json.JsonReference;
import com.chua.common.support.lang.json.annotation.JsonBeanMapper;
import com.chua.common.support.lang.json.annotation.JsonFormat;
import com.chua.common.support.lang.json.annotation.JsonIgnore;
import com.chua.common.support.lang.json.annotation.JsonName;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.utils.ClassUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 基于 Fastjson 的 JSON 实现。
 *
 * <p>通过 {@link JsonProvider} 接口对外提供契约，可作为 {@link Json} 门面类的实现之一，
 * 通过 {@code Json.setImplementation(new FastjsonJsonProvider())} 全局切换。</p>
 *
 * <p>统一门户注解适配：fastjson 原生只识别自身注解（{@code @JSONField}），无法原生识别
 * common-starter 的门户注解。因此本实现通过 {@link JsonBeanMapper} 桥接：
 * 目标类型携带 {@link JsonName} / {@link JsonIgnore} / {@link JsonFormat} 注解时，
 * 先转换为普通 映射 再交给 fastjson 编解码，保证门户注解在各套实现间行为一致。</p>
 *
 * <p>通过 {@code @Spi("fastjson")} 注册为 {@link JsonProvider} 的 SPI 实现，
 * 并由 {@code @AutoSpi} 在编译期自动生成 {@code META-INF/extensions} SPI 索引。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("fastjson")
@AutoSpi(value = "com.chua.common.support.lang.json.JsonProvider")
public class FastjsonJsonProvider implements JsonProvider {

    @Override
    /** 创建json对象 */
    public JsonObject createJsonObject() {
        return new FastjsonJsonObject();
    }

    @Override
    /** 创建json对象 */
    public JsonObject createJsonObject(Map map) {
        return new FastjsonJsonObject(map);
    }

    @Override
    /** 创建jsonarray */
    public JsonArray createJsonArray() {
        return new FastjsonJsonArray();
    }

    @Override
    /** 创建jsonarray */
    public JsonArray createJsonArray(Collection collection) {
        return new FastjsonJsonArray(collection);
    }

    @Override
    /** 创建json节点 */
    public JsonNode createJsonNode(Object value) {
        return new FastjsonJsonNode(value);
    }

    @Override
    /** 解析 */
    public JsonNode parse(String json) {
        if (null == json) {
            return createJsonNode(createJsonObject());
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                return createJsonNode(createJsonArray(JSON.parseArray(trimmed)));
            }
            return createJsonNode(createJsonObject(JSON.parseObject(trimmed, Map.class)));
        } catch (Exception e) {
            return createJsonNode(createJsonObject());
        }
    }

    @Override
    /** 解析 */
    public JsonNode parse(byte[] json) {
        if (null == json) {
            return createJsonNode(createJsonObject());
        }
        return parse(new String(json, UTF_8));
    }

    @Override
    /** 构建 */
    public JsonNode build() {
        return createJsonNode(createJsonObject());
    }

    @Override
    /** 构建Array */
    public JsonNode buildArray() {
        return createJsonNode(createJsonArray());
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(String json) {
        if (null == json) {
            return createJsonObject();
        }
        try {
            return createJsonObject(JSON.parseObject(json, Map.class));
        } catch (Exception e) {
            return createJsonObject();
        }
    }

    @Override
    /** 获取json引用 */
    public JsonReference getJsonReference(String json) {
        return new JsonReference(json);
    }

    @Override
    /** 获取jsonarray */
    public JsonArray getJsonArray(byte[] jsonArray) {
        if (null == jsonArray) {
            return createJsonArray();
        }
        return getJsonArray(new String(jsonArray, UTF_8));
    }

    @Override
    /** 获取jsonarray */
    public JsonArray getJsonArray(String json) {
        if (null == json) {
            return createJsonArray();
        }
        try {
            return createJsonArray(JSON.parseArray(json));
        } catch (Exception e) {
            return createJsonArray();
        }
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(byte[] bytes) {
        try {
            return createJsonObject(JSON.parseObject(new String(bytes, UTF_8), Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(InputStreamReader inputStreamReader) {
        try {
            return createJsonObject(JSON.parseObject(readString(inputStreamReader), Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(InputStream inputStream) {
        return getJsonObject(new InputStreamReader(inputStream, UTF_8));
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(InputStream inputStream, String charset) {
        try {
            return getJsonObject(new InputStreamReader(inputStream, charset));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json转为列表 */
    public <T> List<T> fromJsonToList(InputStream inputStream, Class<T> targetType) {
        return fromJsonToList(readString(inputStream), targetType);
    }

    @Override
    /** 从json转为列表 */
    public <T> List<T> fromJsonToList(String json, Class<T> targetType) {
        if (null == json) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(json, targetType);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(String json, Class<T> target) {
        try {
            if (hasUnifiedAnnotations(target)) {
                Map<String, Object> map = JSON.parseObject(json, Map.class);
                return JsonBeanMapper.fromMap(map, target);
            }
            return JSON.parseObject(json, target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(byte[] bytes, Class<T> target) {
        return fromJson(new String(bytes, UTF_8), target);
    }

    @Override
    /** 从json */
    public JsonObject fromJson(byte[] bytes, Charset charset) {
        try {
            return createJsonObject(JSON.parseObject(new String(bytes, charset), Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target) {
        return fromJson(readString(inputStreamReader), target);
    }

    @Override
    /** 从json */
    public <T> T fromJson(InputStream inputStream, Class<T> target) {
        return fromJson(readString(inputStream), target);
    }

    @Override
    /** 转为json */
    public String toJson(Object object, String... ignores) {
        if (null == object) {
            return "null";
        }
        Object mapped = object;
        if (hasUnifiedAnnotations(object.getClass())) {
            mapped = JsonBeanMapper.toMap(object);
        }
        if (null != ignores && ignores.length > 0 && mapped instanceof Map) {
            JSONObject tree = new JSONObject(true);
            tree.putAll((Map<? extends String, ?>) mapped);
            for (String ignore : ignores) {
                tree.remove(ignore);
            }
            return JSON.toJSONString(tree);
        }
        return JSON.toJSONString(mapped, SerializerFeature.WriteDateUseDateFormat);
    }

    @Override
    /** Pretty格式化 */
    public String prettyFormat(Object object) {
        Object mapped = object;
        if (null != object && hasUnifiedAnnotations(object.getClass())) {
            mapped = JsonBeanMapper.toMap(object);
        }
        return JSON.toJSONString(mapped, SerializerFeature.PrettyFormat, SerializerFeature.WriteDateUseDateFormat);
    }

    @Override
    /** 转为prettyjson */
    public String toPrettyJson(Object obj) {
        return prettyFormat(obj);
    }

    @Override
    /** 转为jsonbyte */
    public byte[] toJsonByte(Object object) {
        if (null == object) {
            return new byte[0];
        }
        if (hasUnifiedAnnotations(object.getClass())) {
            return JSON.toJSONBytes(JsonBeanMapper.toMap(object), SerializerFeature.WriteDateUseDateFormat);
        }
        return JSON.toJSONBytes(object, SerializerFeature.WriteDateUseDateFormat);
    }

    @Override
    /** 是否Json */
    public boolean isJson(Object ext) {
        if (null == ext) {
            return false;
        }
        if (ext instanceof String) {
            String trimmed = ((String) ext).trim();
            return (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
                    (trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
        return false;
    }

    @Override
    public List<?> toList(String string) {
        try {
            return JSON.parseArray(string);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 转为jsonbytes */
    public byte[] toJSONBytes(Object object) {
        return toJsonByte(object);
    }

    @Override
    /** 转为json字符串 */
    public String toJSONString(Object object) {
        return toJson(object);
    }

    @Override
    /** 校验 */
    public boolean validate(String jsonStr) {
        try {
            JSON.parse(jsonStr);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    /** 从json */
    public Map<String, Object> fromJson(String string) {
        try {
            return JSON.parseObject(string, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(String stringValue, Type type) {
        try {
            if (type instanceof Class && hasUnifiedAnnotations((Class<?>) type)) {
                Map<String, Object> map = JSON.parseObject(stringValue, Map.class);
                return JsonBeanMapper.fromMap(map, (Class<T>) type);
            }
            return JSON.parseObject(stringValue, type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(Reader reader, Class<T> target) {
        return fromJson(readString(reader), target);
    }

    @Override
    /** 转为json */
    public void toJson(Object object, Writer writer) {
        try {
            writer.write(toJson(object));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(InputStream stream, Type type) {
        return fromJson(readString(stream), type);
    }

    @Override
    /** 从json */
    public <T> T fromJson(Reader reader, Type type) {
        return fromJson(readString(reader), type);
    }

    /**
     * 判断类型是否携带统一门户注解（{@link JsonName} / {@link JsonIgnore} / {@link JsonFormat}）。
     *
     * @param type 目标类型
     * @return true 表示携带门户注解，需走 {@link JsonBeanMapper} 桥接
     */
    private static boolean hasUnifiedAnnotations(Class<?> type) {
        for (Field field : ClassUtils.getFields(type)) {
            if (field.isAnnotationPresent(JsonName.class)
                    || field.isAnnotationPresent(JsonIgnore.class)
                    || field.isAnnotationPresent(JsonFormat.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取 Reader 为字符串。
     *
     * @param reader 读取器
     * @return 字符串内容
     */
    private String readString(Reader reader) {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[1024];
        int len;
        try {
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return sb.toString();
    }

    /**
     * 读取输入流为字符串。
     *
     * @param stream 输入流
     * @return 字符串内容
     */
    private String readString(InputStream stream) {
        return readString(new InputStreamReader(stream, UTF_8));
    }
}
