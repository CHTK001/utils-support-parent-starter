package com.chua.common.support.lang.json;

import com.chua.common.support.lang.loader.SupplierLazyLoader;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.IoUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.PropertyFilter;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.databind.ser.std.DateSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import static com.chua.common.support.constant.DateFormatConstant.HH_MM_SS_FMT;
import static com.chua.common.support.constant.DateFormatConstant.YYYY_MM_DD_FMT;
import static com.chua.common.support.constant.DateFormatConstant.YYYY_MM_DD_HH_MM_SS_FMT;
import static com.chua.common.support.constant.DateFormatConstant.YYYY_MM_DD_HH_MM_SS_SDF;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 基于 Jackson 的 JSON 实现（默认实现）。
 *
 * <p>原 {@link Json} 工具类的全部逻辑迁移至此，通过 {@link JsonProvider} 接口对外提供契约，
 * 由 {@link Json} 静态门面类持有并委托调用。支持多种数据格式转换、日期格式化及字段过滤等高级特性。
 *
 * <p>线程安全：内部的 ObjectMapper 均为懒加载单例，配置完成后不可变，可安全并发使用。
 *
 * <p>通过 {@code @Spi("jackson")} + {@code @SpiDefault} 注册为 {@link JsonProvider} 的默认 SPI 实现，
 * 由 {@link Json} 门面类在初始化时通过 {@link ServiceProvider} SPI 机制自动发现。
 *
 * <p>统一门户注解适配：本实现通过 {@link CommonJsonAnnotationIntrospector} 识别
 * common-starter 的 {@code @JsonName} / {@code @JsonIgnore} / {@code @JsonFormat} 注解
 * （见 {@code com.chua.common.support.lang.json.annotation} 包），业务代码无需依赖 Jackson 专属注解。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("jackson")
@SpiDefault
public class JacksonJsonProvider implements JsonProvider {

    /**
     * 懒加载普通 ObjectMapper 实例，默认配置为宽松模式（JSON5 风格）。
     */
    private static final SupplierLazyLoader<ObjectMapper> MAPPER_LOADER =
            SupplierLazyLoader.of(JacksonJsonProvider::createJson5Mapper);

    /**
     * 懒加载美化输出格式的 ObjectMapper 实例，用于生成缩进友好的 JSON 字符串。
     */
    private static final SupplierLazyLoader<ObjectMapper> PRETTY_FORMAT_MAPPER_LOADER =
            SupplierLazyLoader.of(JacksonJsonProvider::createPrettyFormatMapper);

    /**
     * 创建并返回配置了美化输出的 ObjectMapper 实例。
     *
     * @return 配置好的 ObjectMapper
     */
    private static ObjectMapper createPrettyFormatMapper() {
        ObjectMapper mapper = createJson5Mapper();
        // 启用输出缩进，使生成的 JSON 更易读
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // 强制对字段名进行双引号包裹，符合标准 JSON 规范
        mapper.configure(JsonWriteFeature.QUOTE_FIELD_NAMES.mappedFeature(), true);
        return mapper;
    }

    /**
     * 获取默认的 ObjectMapper 实例（懒加载）。
     *
     * @return ObjectMapper 实例
     */
    public static ObjectMapper getMapper() {
        return MAPPER_LOADER.get();
    }

    /**
     * 获取用于美化输出的 ObjectMapper 实例（懒加载）。
     *
     * @return 配置了缩进的 ObjectMapper 实例
     */
    private static ObjectMapper getPrettyFormatMapper() {
        return PRETTY_FORMAT_MAPPER_LOADER.get();
    }

    /**
     * 创建一个高度兼容的 ObjectMapper 实例，支持类似 JSON5 的语法特性。
     * 配置包括：忽略未知属性、允许单引号/注释/未加引号字段名等。
     *
     * @return 配置好的 ObjectMapper
     */
    private static ObjectMapper createJson5Mapper() {
        ObjectMapper objectMapper = JsonMapper.builder()
                // --- 解析特性配置 (Parser Features) ---
                // 允许属性名大小写不敏感
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                // 允许在 JSON 中包含注释
                .enable(JsonParser.Feature.ALLOW_COMMENTS)
                // 允许使用单引号代替双引号
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                // 允许字段名不加引号
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                // 允许数字前导零 (如 "01")
                .enable(JsonParser.Feature.ALLOW_NUMERIC_LEADING_ZEROS)
                // 允许非数字字符表示数字 (如 ".5" 或 "inf")
                .enable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS)
                // 允许对象和数组末尾存在多余的逗号 (JSON5 风格)
                .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
                // --- 反序列化特性配置 (Deserialization Features) ---
                // 遇到未知属性时不抛出异常，直接忽略
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // 遇到非法子类型时不抛出异常
                .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                // 再次确认属性名大小写不敏感
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                // --- 序列化特性配置 (Serialization Features) ---
                // 空 Bean 对象不抛出异常，而是序列化为 null 或空对象
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                // 不包装类型标识符
                .configure(SerializationFeature.FAIL_ON_UNWRAPPED_TYPE_IDENTIFIERS, false)
                // 关闭自动关闭源流，由调用者管理资源
                .configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false)
                // 读取重复树键时抛出异常
                .configure(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, true)
                .build();

        // 注册统一门户注解适配器：@JsonName / @JsonIgnore / @JsonFormat
        objectMapper.setAnnotationIntrospector(new CommonJsonAnnotationIntrospector());
        // 设置默认的属性包含策略：始终包含所有属性
        objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);
        // 设置 Date 类型的默认格式
        objectMapper.setDateFormat(YYYY_MM_DD_HH_MM_SS_SDF);

        // 注册 Java 8 Time 模块以处理 java.time 包下的类
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        // 添加 LocalDateTime 的序列化器
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(YYYY_MM_DD_HH_MM_SS_FMT));
        // 添加 LocalDate 的序列化器
        javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(YYYY_MM_DD_FMT));
        // 添加 LocalTime 的序列化器
        javaTimeModule.addSerializer(LocalTime.class, new LocalTimeSerializer(HH_MM_SS_FMT));

        // 添加 LocalDateTime 的反序列化器
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(YYYY_MM_DD_HH_MM_SS_FMT));
        // 添加 LocalDate 的反序列化器
        javaTimeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(YYYY_MM_DD_FMT));
        // 添加 LocalTime 的反序列化器
        javaTimeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(HH_MM_SS_FMT));

        // 添加传统 Date 类的序列化器
        javaTimeModule.addSerializer(Date.class, new DateSerializer(true, YYYY_MM_DD_HH_MM_SS_SDF));

        // 设置时区为系统默认时区
        objectMapper.setTimeZone(TimeZone.getDefault());

        // 注册 Java 8 Time 模块
        objectMapper.registerModule(javaTimeModule);

        // 动态注册可选模块，提高兼容性
        if (ClassUtils.isPresent("com.fasterxml.jackson.datatype.jdk8.Jdk8Module")) {
            objectMapper.registerModule(ClassUtils.forObject("com.fasterxml.jackson.datatype.jdk8.Jdk8Module"));
        }
        if (ClassUtils.isPresent("org.springframework.boot.jackson.JsonMixinModule")) {
            objectMapper.registerModule(ClassUtils.forObject("org.springframework.boot.jackson.JsonMixinModule"));
        }
        if (ClassUtils.isPresent("com.fasterxml.jackson.module.paramnames.ParameterNamesModule")) {
            objectMapper.registerModule(ClassUtils.forObject("com.fasterxml.jackson.module.paramnames.ParameterNamesModule"));
        }

        return objectMapper;
    }

    @Override
    public JsonNode parse(String json) {
        if (null == json) {
            return createJsonNode(createJsonObject());
        }
        try {
            Object value = getMapper().readValue(json, Object.class);
            return createJsonNode(value);
        } catch (Exception e) {
            return createJsonNode(createJsonObject());
        }
    }

    @Override
    public JsonNode parse(byte[] json) {
        if (null == json) {
            return createJsonNode(createJsonObject());
        }
        return parse(new String(json, UTF_8));
    }

    @Override
    public JsonNode build() {
        return createJsonNode(createJsonObject());
    }

    @Override
    public JsonNode buildArray() {
        return createJsonNode(createJsonArray());
    }

    @Override
    public JsonObject getJsonObject(String json) {
        try {
            return createJsonObject(getMapper().readValue(json, Map.class));
        } catch (Exception e) {
            return createJsonObject();
        }
    }

    @Override
    public JsonReference getJsonReference(String json) {
        return new JsonReference(json);
    }

    @Override
    public JsonArray getJsonArray(byte[] jsonArray) {
        if (null == jsonArray) {
            return createJsonArray();
        }
        return getJsonArray(new String(jsonArray, UTF_8));
    }

    @Override
    public JsonArray getJsonArray(String json) {
        if (null == json) {
            return createJsonArray();
        }
        try {
            return createJsonArray(getMapper().readValue(json, List.class));
        } catch (JsonProcessingException e) {
            return createJsonArray();
        }
    }

    @Override
    public JsonObject getJsonObject(byte[] bytes) {
        try {
            return createJsonObject(getMapper().readValue(bytes, Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public JsonObject getJsonObject(InputStreamReader inputStreamReader) {
        try {
            return createJsonObject(getMapper().readValue(inputStreamReader, Map.class));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public JsonObject getJsonObject(InputStream inputStream) {
        return getJsonObject(new InputStreamReader(inputStream, UTF_8));
    }

    @Override
    public JsonObject getJsonObject(InputStream inputStream, String charset) {
        try {
            return getJsonObject(new InputStreamReader(inputStream, charset));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> List<T> fromJsonToList(InputStream inputStream, Class<T> targetType) {
        return fromJsonToList(IoUtils.asString(new InputStreamReader(inputStream, UTF_8)), targetType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> fromJsonToList(String json, Class<T> targetType) {
        if (null == json) {
            return Collections.emptyList();
        }
        try {
            // 将 JSON 数组字符串反序列化为 List（readValues 仅适用于流式多顶层值，标准数组需用 readValue）
            return (List<T>) getMapper().readerForListOf(targetType).readValue(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> target) {
        try {
            return getMapper().readValue(json, target);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(byte[] bytes, Class<T> target) {
        try {
            return getMapper().readValue(bytes, target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public JsonObject fromJson(byte[] bytes, Charset charset) {
        try {
            return createJsonObject(getMapper().readValue(new String(bytes, charset), Map.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target) {
        try {
            return getMapper().readValue(inputStreamReader, target);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(InputStream inputStream, Class<T> target) {
        try {
            return getMapper().readValue(inputStream, target);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toJson(Object object, String... ignores) {
        try {
            // 收集需要忽略的字段名
            Set<String> ignoreFields = new HashSet<>();
            for (String field : ignores) {
                ignoreFields.add(field);
            }

            // 定义自定义属性过滤器，用于控制字段的序列化
            PropertyFilter filter = new SimpleBeanPropertyFilter() {

                /**
                 * 判断字段是否应该作为属性序列化
                 */
                @Override
                public void serializeAsField(Object pojo, JsonGenerator jgen, SerializerProvider provider, PropertyWriter writer) throws IOException {
                    // 如果字段名在忽略列表中，则跳过该字段
                    if (ignoreFields.contains(writer.getName())) {
                        return;
                    }
                    // 执行正常的字段序列化
                    try {
                        writer.serializeAsField(pojo, jgen, provider);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                /**
                 * 判断元素是否应该作为数组元素序列化
                 */
                @Override
                public void serializeAsElement(Object elementValue, JsonGenerator jgen, SerializerProvider provider, PropertyWriter writer) throws IOException {
                    // 如果字段名在忽略列表中，则跳过该元素
                    if (ignoreFields.contains(writer.getName())) {
                        return;
                    }
                    // 执行正常的元素序列化
                    try {
                        writer.serializeAsElement(elementValue, jgen, provider);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };

            // 将过滤器注册到 FilterProvider 中
            FilterProvider filters = new SimpleFilterProvider().addFilter("dynamicFilter", filter);

            // 使用配置的过滤器写入 JSON 字符串
            return getMapper().writer(filters).writeValueAsString(object);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String prettyFormat(Object object) {
        try {
            return getPrettyFormatMapper().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toPrettyJson(Object obj) {
        return prettyFormat(obj);
    }

    @Override
    public byte[] toJsonByte(Object object) {
        try {
            return getMapper().writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isJson(Object ext) {
        if (null == ext) {
            return false;
        }
        if (ext instanceof String) {
            String trimmed = ((String) ext).trim();
            // 检查是否为数组或对象格式
            return (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
                    (trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
        return false;
    }

    @Override
    public List<?> toList(String string) {
        try {
            // 注意：此处使用了 getPrettyFormatMapper，通常应使用标准 getMapper，但保留原逻辑
            return getPrettyFormatMapper().readValue(string, JsonArray.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] toJSONBytes(Object object) {
        return toJsonByte(object);
    }

    @Override
    public String toJSONString(Object object) {
        return toJson(object);
    }

    @Override
    public boolean validate(String jsonStr) {
        try {
            getMapper().readTree(jsonStr);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public Map<String, Object> fromJson(String string) {
        try {
            return getMapper().readerForMapOf(Object.class).readValue(string);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(String stringValue, Type type) {
        try {
            ObjectMapper mapper = getMapper();
            // java.lang.reflect.Type 需转换为 Jackson JavaType 后才能调用 readValue
            return mapper.readValue(stringValue, mapper.getTypeFactory().constructType(type));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(Reader reader, Class<T> target) {
        try {
            return getMapper().readValue(reader, target);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void toJson(Object object, Writer writer) {
        try {
            getMapper().writeValue(writer, object);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(InputStream stream, Type type) {
        try (stream) {
            ObjectMapper mapper = getMapper();
            return mapper.readValue(stream, mapper.getTypeFactory().constructType(type));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T fromJson(Reader reader, Type type) {
        try {
            ObjectMapper mapper = getMapper();
            return mapper.readValue(reader, mapper.getTypeFactory().constructType(type));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
