package com.chua.fory.support.json;

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
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
* 基于 Apache Fory（Fury）官方 fory-json 模块的 JSON 实现。
*
* <p>通过 {@link JsonProvider} 接口对外提供契约，可作为 {@link Json} 门面类的实现之一，
* 通过 {@code Json.setImplementation(new ForyJsonProvider())} 全局切换。</p>
*
* <p>统一门户注解适配：fory-json 基于代码生成、只读取自身注解模型（{@code org.apache.fory.json.annotation}），
* 无法原生识别 common-starter 的门户注解。因此本实现通过 {@link JsonBeanMapper} 桥接：
* 目标类型携带 {@link JsonName} / {@link JsonIgnore} / {@link JsonFormat} 注解时，
* 先转换为普通 映射 再交给 fory-json 编解码，保证门户注解在三套实现间行为一致。</p>
*
* <p>通过 {@code @Spi("fory")} 注册为 {@link JsonProvider} 的 SPI 实现，
* 并由 {@code @AutoSpi} 在编译期自动生成 {@code META-INF/extensions} SPI 索引。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("fory")
@AutoSpi(value = "com.chua.common.support.lang.json.JsonProvider")
public class ForyJsonProvider implements JsonProvider {

    /**
    * foryjson 实例（线程安全，配置后不可变）
     */
    private static final ForyJson FORY_JSON = ForyJson.builder().build();

    @Override
    /** 创建json对象 */
    public JsonObject createJsonObject() {
        return new ForyJsonObject();
    }

    @Override
    /** 创建json对象 */
    public JsonObject createJsonObject(Map map) {
        return new ForyJsonObject(map);
    }

    @Override
    /** 创建jsonarray */
    public JsonArray createJsonArray() {
        return new ForyJsonArray();
    }

    @Override
    /** 创建jsonarray */
    public JsonArray createJsonArray(Collection collection) {
        return new ForyJsonArray(collection);
    }

    @Override
    /** 创建json节点 */
    public JsonNode createJsonNode(Object value) {
        return new ForyJsonNode(value);
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
                return createJsonNode(createJsonArray(FORY_JSON.fromJson(json, List.class)));
            }
            return createJsonNode(createJsonObject(FORY_JSON.fromJson(json, Map.class)));
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
            return createJsonObject(FORY_JSON.fromJson(json, Map.class));
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
            return createJsonArray(FORY_JSON.fromJson(json, List.class));
        } catch (Exception e) {
            return createJsonArray();
        }
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(byte[] bytes) {
        try {
            return createJsonObject(FORY_JSON.fromJson(new String(bytes, UTF_8), Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(InputStreamReader inputStreamReader) {
        return getJsonObject(readString(inputStreamReader));
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
            Type listType = parameterizedListType(targetType);
            return FORY_JSON.fromJson(json, TypeRef.of(listType));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(String json, Class<T> target) {
        try {
            if (hasUnifiedAnnotations(target)) {
                Map<String, Object> map = FORY_JSON.fromJson(json, Map.class);
                return JsonBeanMapper.fromMap(map, target);
            }
            return FORY_JSON.fromJson(json, target);
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
        return createJsonObject(FORY_JSON.fromJson(new String(bytes, charset), Map.class));
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
            Map<String, Object> ignoreMap = (Map<String, Object>) mapped;
            for (String ignore : ignores) {
                ignoreMap.remove(ignore);
            }
            return FORY_JSON.toJson(ignoreMap);
        }
        return FORY_JSON.toJson(mapped);
    }

    @Override
    /** Pretty格式化 */
    public String prettyFormat(Object object) {
        // fory-json 无内置缩进美化，回退为紧凑输出（格式语义一致）
        return toJson(object);
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
            return FORY_JSON.toJsonBytes(JsonBeanMapper.toMap(object));
        }
        return FORY_JSON.toJsonBytes(object);
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
            return FORY_JSON.fromJson(string, List.class);
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
            String trimmed = jsonStr.trim();
            if (trimmed.startsWith("[")) {
                FORY_JSON.fromJson(jsonStr, List.class);
            } else {
                FORY_JSON.fromJson(jsonStr, Map.class);
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    /** 从json */
    public Map<String, Object> fromJson(String string) {
        try {
            return FORY_JSON.fromJson(string, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(String stringValue, Type type) {
        try {
            if (type instanceof Class && hasUnifiedAnnotations((Class<?>) type)) {
                Map<String, Object> map = FORY_JSON.fromJson(stringValue, Map.class);
                return JsonBeanMapper.fromMap(map, (Class<T>) type);
            }
            return FORY_JSON.fromJson(stringValue, TypeRef.of(type));
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
    * 构造 列表&lt;T&gt; 的 parameterized类型。
    *
    * @param elementType 元素类型
    * @return ParameterizedType
     */
    private static Type parameterizedListType(Class<?> elementType) {
        return new ParameterizedType() {
            @Override
            /** 获取actual类型参数 */
            public Type[] getActualTypeArguments() {
                return new Type[]{elementType};
            }

            @Override
            /** 获取raw类型 */
            public Type getRawType() {
                return List.class;
            }

            @Override
            /** 获取owner类型 */
            public Type getOwnerType() {
                return null;
            }
        };
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
