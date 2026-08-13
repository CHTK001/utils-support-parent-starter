package com.chua.common.support.lang.json;

import com.chua.common.support.lang.loader.SupplierLazyLoader;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.IoUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
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

import static com.chua.common.support.constant.DateFormatConstant.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * JSON 工具类，提供便捷的 JSON 序列化和反序列化功能。
 * 基于 Jackson 实现，支持多种数据格式转换、日期格式化及字段过滤等高级特性。
 *
 * @author CH
 */
public class Json {

 /**
 * 懒加载普通 ObjectMapper 实例，默认配置为宽松模式（JSON5 风格）。
 */
 private static final SupplierLazyLoader<ObjectMapper> MAPPER_LOADER =
 SupplierLazyLoader.of(Json::createJson5Mapper);

 /**
 * 懒加载美化输出格式的 ObjectMapper 实例，用于生成缩进友好的 JSON 字符串。
 */
 private static final SupplierLazyLoader<ObjectMapper> PRETTY_FORMAT_MAPPER_LOADER =
 SupplierLazyLoader.of(Json::createPrettyFormatMapper);

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

 /**
 * 将 JSON 字符串解析为 JsonNode 对象，提供统一的树形遍历 API。
 *
 * <p>JsonNode 支持链式导航（{@code get(key)}、{@code get(index)}）、
 * JSONPath 查询（{@code path("$.store.book[0].title")}）以及类型安全取值
 * （{@code toIntValue()}、{@code toStringValue()} 等）。</p>
 *
 * <p>本方法使用 JSON5 兼容的解析器，支持末尾逗号、单引号、注释等非标准语法。</p>
 *
 * @param json JSON 字符串（标准 JSON 或 JSON5 均可）
 * @return JsonNode 对象，解析失败时返回空 JsonObject 的 JsonNode
 * @see JsonNode
 * @see JsonNode#get(String)
 * @see JsonNode#path(String)
 */
 public static JsonNode parse(String json) {
     if (null == json) {
         return new JsonNode(new JsonObject());
     }
     try {
         Object value = getMapper().readValue(json, Object.class);
         return new JsonNode(value);
     } catch (Exception e) {
         return new JsonNode(new JsonObject());
     }
 }

 /**
 * 将字节数组形式的 JSON 解析为 JsonNode 对象。
 *
 * @param json JSON 字节数组
 * @return JsonNode 对象
 * @see #parse(String)
 */
 public static JsonNode parse(byte[] json) {
     if (null == json) {
         return new JsonNode(new JsonObject());
     }
     return parse(new String(json, UTF_8));
 }

 /**
 * 将 JSON 字符串解析为 JsonObject 对象。
 * 如果解析失败或输入为空，返回空的 JsonObject。
 *
 * @param json JSON 字符串
 * @return JsonObject 对象
 */
 public static JsonObject getJsonObject(String json) {
 try {
 return getMapper().readValue(json, JsonObject.class);
 } catch (Exception e) {
 return new JsonObject();
 }
 }

 /**
 * 根据 JSON 字符串创建 JsonReference 对象，用于后续链式操作。
 *
 * @param json JSON 字符串
 * @return JsonReference 对象
 */
 public static JsonReference getJsonReference(String json) {
 return new JsonReference(json);
 }

 /**
 * 将字节数组形式的 JSON 解析为 JsonArray 对象。
 *
 * @param jsonArray 字节数组
 * @return JsonArray 对象
 */
 public static JsonArray getJsonArray(byte[] jsonArray) {
 if (null == jsonArray) {
 return new JsonArray();
 }
 return getJsonArray(new String(jsonArray, UTF_8));
 }

 /**
 * 将 JSON 字符串解析为 JsonArray 对象。
 * 如果解析失败或输入为空，返回空的 JsonArray。
 *
 * @param json JSON 字符串
 * @return JsonArray 对象
 */
 public static JsonArray getJsonArray(String json) {
 if (null == json) {
 return new JsonArray();
 }
 try {
 return getMapper().readValue(json, JsonArray.class);
 } catch (JsonProcessingException e) {
 return new JsonArray();
 }
 }

 /**
 * 将字节数组形式的 JSON 解析为 JsonObject 对象。
 *
 * @param bytes JSON 字节数组
 * @return JsonObject 对象
 */
 public static JsonObject getJsonObject(byte[] bytes) {
 try {
 return getMapper().readValue(bytes, JsonObject.class);
 } catch (Exception e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 通过 InputStreamReader 将 JSON 解析为 JsonObject 对象。
 *
 * @param inputStreamReader 输入流读取器
 * @return JsonObject 对象
 */
 public static JsonObject getJsonObject(InputStreamReader inputStreamReader) {
 try {
 return getMapper().readValue(inputStreamReader, JsonObject.class);
 } catch (IOException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 通过 InputStream 将 JSON 解析为 JsonObject 对象，默认使用 UTF-8 编码。
 *
 * @param inputStream 输入流
 * @return JsonObject 对象
 */
 public static JsonObject getJsonObject(InputStream inputStream) {
 return getJsonObject(new InputStreamReader(inputStream, UTF_8));
 }

 /**
 * 通过 InputStream 将 JSON 解析为 JsonObject 对象，指定字符集。
 *
 * @param inputStream 输入流
 * @param charset 字符集名称
 * @return JsonObject 对象
 */
 public static JsonObject getJsonObject(InputStream inputStream, String charset) {
 try {
 return getJsonObject(new InputStreamReader(inputStream, charset));
 } catch (UnsupportedEncodingException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 从 InputStream 读取 JSON 并转换为指定类型的 List。
 *
 * @param inputStream 输入流
 * @param targetType 目标元素类型
 * @param <T> 泛型类型
 * @return List 集合
 */
 public static <T> List<T> fromJsonToList(InputStream inputStream, Class<T> targetType) {
 return fromJsonToList(IoUtils.asString(new InputStreamReader(inputStream, UTF_8)), targetType);
 }

 /**
 * 将 JSON 字符串转换为指定类型的 List 集合。
 *
 * @param json JSON 字符串
 * @param targetType 列表元素的类型
 * @param <T> 泛型类型
 * @return List 集合
 */
 @SuppressWarnings("unchecked")
 public static <T> List<T> fromJsonToList(String json, Class<T> targetType) {
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

 /**
 * 将 JSON 字符串反序列化为指定类型的对象。
 *
 * @param json JSON 字符串
 * @param target 目标类型
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(String json, Class<T> target) {
 try {
 return getMapper().readValue(json, target);
 } catch (JsonProcessingException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 将字节数组反序列化为指定类型的对象。
 *
 * @param bytes JSON 字节数组
 * @param target 目标类型
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(byte[] bytes, Class<T> target) {
 try {
 return getMapper().readValue(bytes, target);
 } catch (Exception e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 将字节数组和指定字符集转换为 JsonObject。
 *
 * @param bytes JSON 字节数组
 * @param charset 字符集
 * @return JsonObject 对象
 */
 public static JsonObject fromJson(byte[] bytes, Charset charset) {
 return fromJson(new String(bytes, charset), JsonObject.class);
 }

 /**
 * 通过 InputStreamReader 将 JSON 反序列化为指定类型的对象。
 *
 * @param inputStreamReader 输入流读取器
 * @param target 目标类型
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(InputStreamReader inputStreamReader, Class<T> target) {
 try {
 return getMapper().readValue(inputStreamReader, target);
 } catch (IOException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 通过 InputStream 将 JSON 反序列化为指定类型的对象。
 *
 * @param inputStream 输入流
 * @param target 目标类型
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(InputStream inputStream, Class<T> target) {
 try {
 return getMapper().readValue(inputStream, target);
 } catch (IOException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 将对象序列化为 JSON 字符串，并排除指定的字段。
 *
 * @param object 待序列化的对象
 * @param ignores 需要忽略的字段名数组
 * @return JSON 字符串
 */
 public static String toJson(Object object, String... ignores) {
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

 /**
 * 将对象序列化为格式美观（带缩进）的 JSON 字符串。
 *
 * @param object 待序列化的对象
 * @return 美化后的 JSON 字符串
 */
 public static String prettyFormat(Object object) {
 try {
 return getPrettyFormatMapper().writeValueAsString(object);
 } catch (JsonProcessingException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 别名方法：将对象序列化为格式美观的 JSON 字符串。
 *
 * @param obj 待序列化的对象
 * @return 美化后的 JSON 字符串
 */
 public static String toPrettyJson(Object obj) {
 return prettyFormat(obj);
 }

 /**
 * 将对象序列化为 JSON 字节数组。
 *
 * @param object 待序列化的对象
 * @return JSON 字节数组
 */
 public static byte[] toJsonByte(Object object) {
 try {
 return getMapper().writeValueAsBytes(object);
 } catch (JsonProcessingException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 检查给定对象是否为有效的 JSON 字符串。
 * 仅当对象为 String 类型且以 [ 或 { 开头，以 ] 或 } 结尾时返回 true。
 *
 * @param ext 待检查的对象
 * @return 是否为 JSON 字符串
 */
 public static boolean isJson(Object ext) {
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

 /**
 * 将 JSON 字符串转换为 List 对象。
 *
 * @param string JSON 字符串
 * @return List 对象
 */
 public static List<?> toList(String string) {
 try {
 // 注意：此处使用了 getPrettyFormatMapper，通常应使用标准 getMapper，但保留原逻辑
 return getPrettyFormatMapper().readValue(string, JsonArray.class);
 } catch (JsonProcessingException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 将对象序列化为 JSON 字节数组（别名方法）。
 *
 * @param object 待序列化的对象
 * @return JSON 字节数组
 */
 public static byte[] toJSONBytes(Object object) {
 return toJsonByte(object);
 }

 /**
 * 将对象序列化为 JSON 字符串（别名方法）。
 *
 * @param object 待序列化的对象
 * @return JSON 字符串
 */
 public static String toJSONString(Object object) {
 return toJson(object);
 }

 /**
 * 验证给定的字符串是否为合法的 JSON 格式。
 *
 * @param jsonStr JSON 字符串
 * @return 是否合法
 */
 public static boolean validate(String jsonStr) {
 try {
 getMapper().readTree(jsonStr);
 } catch (Exception e) {
 return false;
 }
 return true;
 }

 /**
 * 将 JSON 字符串转换为 Map<String, Object>。
 *
 * @param string JSON 字符串
 * @return Map 对象
 */
 public static Map<String, Object> fromJson(String string) {
 try {
 return getMapper().readerForMapOf(Object.class).readValue(string);
 } catch (JsonProcessingException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 将 JSON 字符串根据 TypeReference 反序列化为指定泛型类型的对象。
 *
 * @param stringValue JSON 字符串
 * @param typeReference 类型引用
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(String stringValue, TypeReference<T> typeReference) {
 try {
 return getMapper().readValue(stringValue, typeReference);
 } catch (JsonProcessingException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 从 Reader 读取 JSON 并反序列化为指定类型的对象。
 * 适用于从文件、字符串流等 Reader 中读取 JSON 数据。
 *
 * @param reader JSON Reader
 * @param target 目标类型
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(Reader reader, Class<T> target) {
 try {
 return getMapper().readValue(reader, target);
 } catch (IOException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 将对象序列化为 JSON 并写入 Writer。
 * 适用于直接写入文件、网络流等场景。
 *
 * @param object 待序列化的对象
 * @param writer 输出 Writer
 */
 public static void toJson(Object object, Writer writer) {
 try {
 getMapper().writeValue(writer, object);
 } catch (IOException e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 从 InputStream 读取 JSON 并根据 TypeReference 反序列化为指定泛型类型的对象。
 *
 * @param stream JSON 输入流
 * @param typeReference 类型引用
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(InputStream stream, TypeReference<T> typeReference) {
 try (stream) {
 return getMapper().readValue(stream, typeReference);
 } catch (Exception e) {
 throw new RuntimeException(e);
 }
 }

 /**
 * 从 Reader 读取 JSON 并根据 TypeReference 反序列化为指定泛型类型的对象。
 *
 * @param reader JSON Reader
 * @param typeReference 类型引用
 * @param <T> 泛型类型
 * @return 目标对象
 */
 public static <T> T fromJson(Reader reader, TypeReference<T> typeReference) {
 try {
 return getMapper().readValue(reader, typeReference);
 } catch (IOException e) {
 throw new RuntimeException(e);
 }
 }
}
