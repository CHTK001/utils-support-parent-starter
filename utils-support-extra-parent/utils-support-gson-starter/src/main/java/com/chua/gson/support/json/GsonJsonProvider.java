package com.chua.gson.support.json;

import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.lang.json.JsonProvider;
import com.chua.common.support.lang.json.JsonReference;
import com.chua.common.support.lang.json.annotation.JsonIgnore;
import com.chua.common.support.lang.json.annotation.JsonName;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ast.support.annotation.AutoSpi;
import com.google.gson.FieldNamingStrategy;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.chua.common.support.constant.DateFormatConstant.HH_MM_SS;
import static com.chua.common.support.constant.DateFormatConstant.YYYY_MM_DD;
import static com.chua.common.support.constant.DateFormatConstant.YYYY_MM_DD_HH_MM_SS;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
* 基于 Gson 的 JSON 实现。
*
* <p>通过 {@link JsonProvider} 接口对外提供契约，可作为 {@link Json} 门面类的实现之一，
* 通过 {@code Json.setImplementation(new GsonJsonProvider())} 全局切换。</p>
*
* <p>统一门户注解适配：本实现通过 Gson 的 {@link FieldNamingStrategy} / {@link ExclusionStrategy}
* 识别 common-starter 的 {@link JsonName} / {@link JsonIgnore} / {@link JsonFormat} 注解，
* 业务代码无需依赖 Gson 专属注解（{@code @SerializedName} 等）。</p>
*
* <p>通过 {@code @Spi("gson")} 注册为 {@link JsonProvider} 的 SPI 实现，
* 并由 {@code @AutoSpi} 在编译期自动生成 {@code META-INF/extensions} SPI 索引。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("gson")
@AutoSpi(value = "com.chua.common.support.lang.json.JsonProvider")
public class GsonJsonProvider implements JsonProvider {

    /**
    * Gson 实例（线程安全，配置后不可变）
     */
    private final Gson gson;

    /**
    * 美化输出 Gson 实例
     */
    private final Gson prettyGson;

    /**
    * 构造 gsonjson提供者，注册统一门户注解适配策略与日期格式。
     */
    public GsonJsonProvider() {
 // 统一注解 @json名称 → Gson 字段名；@jsonignore → 双向忽略；
 // 注册 Java.时间 / 日期 类型适配器（Gson 默认不支持 Java.时间，需显式注册）
        GsonBuilder builder = new GsonBuilder()
                .setFieldNamingStrategy(new JsonNameFieldNamingStrategy())
                .addSerializationExclusionStrategy(new JsonIgnoreExclusionStrategy())
                .addDeserializationExclusionStrategy(new JsonIgnoreExclusionStrategy())
                .registerTypeAdapter(LocalDateTime.class, dateTimeAdapter(DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS)))
                .registerTypeAdapter(LocalDate.class, dateTimeAdapter(DateTimeFormatter.ofPattern(YYYY_MM_DD)))
                .registerTypeAdapter(LocalTime.class, dateTimeAdapter(DateTimeFormatter.ofPattern(HH_MM_SS)))
                .setDateFormat(YYYY_MM_DD_HH_MM_SS);
        this.gson = builder.create();
        this.prettyGson = new GsonBuilder()
                .setFieldNamingStrategy(new JsonNameFieldNamingStrategy())
                .addSerializationExclusionStrategy(new JsonIgnoreExclusionStrategy())
                .addDeserializationExclusionStrategy(new JsonIgnoreExclusionStrategy())
                .registerTypeAdapter(LocalDateTime.class, dateTimeAdapter(DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS)))
                .registerTypeAdapter(LocalDate.class, dateTimeAdapter(DateTimeFormatter.ofPattern(YYYY_MM_DD)))
                .registerTypeAdapter(LocalTime.class, dateTimeAdapter(DateTimeFormatter.ofPattern(HH_MM_SS)))
                .setDateFormat(YYYY_MM_DD_HH_MM_SS)
                .setPrettyPrinting()
                .create();
    }

    /**
    * 创建 Java.时间 类型的 Gson 序列化 / 反序列化适配器。
    *
    * @param formatter 日期时间格式化器
    * @param <T>       Java.时间 类型
    * @return Gson 类型适配器
     */
    @SuppressWarnings("unchecked")
    private static <T> TypeAdapter<T> dateTimeAdapter(DateTimeFormatter formatter) {
        return new TypeAdapter<T>() {
            @Override
            /** 写入 */
            public void write(JsonWriter out, T value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                out.value(formatter.format((java.time.temporal.TemporalAccessor) value));
            }

            @Override
            /** 读取 */
            public T read(JsonReader in) throws IOException {
                if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                String value = in.nextString();
                if (value == null || value.isEmpty()) {
                    return null;
                }
                return (T) formatter.parse(value);
            }
        };
    }

    /**
    * 统一注解 {@link JsonName} 的 Gson 字段命名策略适配器。
    * @author CH
    * @since 4.0.0
     */
    static class JsonNameFieldNamingStrategy implements FieldNamingStrategy {

        @Override
        /** translate名称 */
        public String translateName(Field field) {
            JsonName jsonName = field.getAnnotation(JsonName.class);
            if (null != jsonName && !jsonName.value().isEmpty()) {
                return jsonName.value();
            }
            return field.getName();
        }
    }

    /**
    * 统一注解 {@link JsonIgnore} 的 Gson 排除策略适配器。
    * @author CH
    * @since 4.0.0
     */
    static class JsonIgnoreExclusionStrategy implements ExclusionStrategy {

        @Override
        /** 是否应该跳过字段 */
        public boolean shouldSkipField(FieldAttributes fieldAttributes) {
            return null != fieldAttributes.getAnnotation(JsonIgnore.class);
        }

        @Override
        /** 是否应该跳过类 */
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    }

    @Override
    /** 创建json对象 */
    public JsonObject createJsonObject() {
        return new GsonJsonObject();
    }

    @Override
    /** 创建json对象 */
    public JsonObject createJsonObject(Map map) {
        return new GsonJsonObject(map);
    }

    @Override
    /** 创建jsonarray */
    public JsonArray createJsonArray() {
        return new GsonJsonArray();
    }

    @Override
    /** 创建jsonarray */
    public JsonArray createJsonArray(Collection collection) {
        return new GsonJsonArray(collection);
    }

    @Override
    /** 创建json节点 */
    public JsonNode createJsonNode(Object value) {
        return new GsonJsonNode(value);
    }

    @Override
    /** 解析 */
    public JsonNode parse(String json) {
        if (null == json) {
            return createJsonNode(createJsonObject());
        }
        try {
            Object value = gson.fromJson(json, Object.class);
            return createJsonNode(value);
        } catch (JsonSyntaxException e) {
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
            return createJsonObject(gson.fromJson(json, Map.class));
        } catch (JsonSyntaxException e) {
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
            return createJsonArray(gson.fromJson(json, List.class));
        } catch (JsonSyntaxException e) {
            return createJsonArray();
        }
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(byte[] bytes) {
        try {
            return createJsonObject(gson.fromJson(new String(bytes, UTF_8), Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 获取json对象 */
    public JsonObject getJsonObject(InputStreamReader inputStreamReader) {
        try {
            return createJsonObject(gson.fromJson(inputStreamReader, Map.class));
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
            Type listType = TypeToken.getParameterized(List.class, targetType).getType();
            return gson.fromJson(json, listType);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(String json, Class<T> target) {
        try {
            return gson.fromJson(json, target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(byte[] bytes, Class<T> target) {
        try {
            return gson.fromJson(new String(bytes, UTF_8), target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public JsonObject fromJson(byte[] bytes, Charset charset) {
        return createJsonObject(gson.fromJson(new String(bytes, charset), Map.class));
    }

    @Override
    /** 从json */
    public <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target) {
        try {
            return gson.fromJson(inputStreamReader, target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(InputStream inputStream, Class<T> target) {
        return fromJson(readString(inputStream), target);
    }

    @Override
    /** 转为json */
    public String toJson(Object object, String... ignores) {
        if (null == ignores || ignores.length == 0) {
            return gson.toJson(object);
        }
 // 需要忽略字段：先转为 json对象 树，再剔除忽略字段
        com.google.gson.JsonElement tree = gson.toJsonTree(object);
        if (tree.isJsonObject()) {
            for (String ignore : ignores) {
                tree.getAsJsonObject().remove(ignore);
            }
        }
        return gson.toJson(tree);
    }

    @Override
    /** Pretty格式化 */
    public String prettyFormat(Object object) {
        return prettyGson.toJson(object);
    }

    @Override
    /** 转为prettyjson */
    public String toPrettyJson(Object obj) {
        return prettyFormat(obj);
    }

    @Override
    /** 转为jsonbyte */
    public byte[] toJsonByte(Object object) {
        return gson.toJson(object).getBytes(UTF_8);
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
            return gson.fromJson(string, List.class);
        } catch (JsonSyntaxException e) {
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
            gson.fromJson(jsonStr, Object.class);
        } catch (JsonSyntaxException e) {
            return false;
        }
        return true;
    }

    @Override
    /** 从json */
    public Map<String, Object> fromJson(String string) {
        try {
            return gson.fromJson(string, new TypeToken<Map<String, Object>>() {
            }.getType());
        } catch (JsonSyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(String stringValue, Type type) {
        try {
            return gson.fromJson(stringValue, type);
        } catch (JsonSyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 从json */
    public <T> T fromJson(Reader reader, Class<T> target) {
        try {
            return gson.fromJson(reader, target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    /** 转为json */
    public void toJson(Object object, Writer writer) {
        try {
            gson.toJson(object, writer);
        } catch (Exception e) {
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
        try {
            return gson.fromJson(reader, type);
        } catch (JsonSyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
    * 读取输入流为字符串。
    *
    * @param stream 输入流
    * @return 字符串内容
     */
    private String readString(InputStream stream) {
        try (InputStreamReader reader = new InputStreamReader(stream, UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
