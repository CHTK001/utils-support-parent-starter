package com.chua.common.support.lang.json;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
 * JSONPath SPI 接口，提供声明式的 JSON 路径查询与操作能力。
 *
 * <p>本接口采用<b>双层设计</b>，兼顾一次性调用和链式操作两种场景：</p>
 *
 * <p><b>1. 一次性调用（便捷方法，直接传入 JSON 字符串）：</b></p>
 * <pre>{@code
 * JsonPath jp = JsonPath.getInstance();
 *
 * // 读取值
 * String title = jp.read(json, "$.store.book[0].title");
 * double price = jp.read(json, "$.store.book[0].price", double.class);
 *
 * // 判断存在
 * boolean exists = jp.isExist(json, "$.store.book[0]");
 *
 * // 修改
 * String result = jp.set(json, "$.store.book[0].price", 35.0);
 * result = jp.delete(result, "$.store.book[1]");
 * }</pre>
 *
 * <p><b>2. 链式操作（解析一次 JSON，多次操作后输出结果）：</b></p>
 * <pre>{@code
 * String result = JsonPath.getInstance()
 * .parse(json)
 * .set("$.store.book[0].price", 35.0)
 * .delete("$.store.temp")
 * .add("$.store.book", Map.of("title", "Python", "price", 39.9))
 * .toJson();
 * }</pre>
 *
 * <p>实现类通过 SPI 机制发现与加载，详见 {@link com.chua.common.support.spi.ServiceProvider}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see ServiceProvider
 */
@Spi
public interface JsonPath {

 // ==================== 一次性方法 ====================

 /**
 * 从 JSON 字符串中读取 JSONPath 表达式的值。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式，如 {@code "$.store.book[0].title"}
 * @param <T> 返回值类型
 * @return JSONPath 指向的值，路径不存在返回 null
 */
 <T> T read(String json, String jsonPath);

 /**
 * 从 JSON 字符串中读取指定类型的值。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式
 * @param type 目标类型
 * @param <T> 返回值类型
 * @return 解析后的值，路径不存在返回 null
 */
 <T> T read(String json, String jsonPath, Class<T> type);

 /**
 * 在 JSON 中设置指定路径的值。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式
 * @param value 要设置的新值
 * @return 修改后的 JSON 字符串
 */
 String set(String json, String jsonPath, Object value);

 /**
 * 在 JSON 中删除指定路径的值。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式
 * @return 删除后的 JSON 字符串
 */
 String delete(String json, String jsonPath);

 /**
 * 在 JSON 数组末尾追加元素。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式，应指向数组
 * @param value 要添加的元素
 * @return 添加后的 JSON 字符串
 */
 String add(String json, String jsonPath, Object value);

 /**
 * 在 JSON 对象中设置键值对（相当于 Map.put）。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式，应指向对象
 * @param key 键名
 * @param value 键值
 * @return 设置后的 JSON 字符串
 */
 String put(String json, String jsonPath, String key, Object value);

 /**
 * 判断 JSONPath 路径在 JSON 中是否存在。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式
 * @return 路径存在返回 true
 */
 boolean isExist(String json, String jsonPath);

 /**
 * 获取 JSONPath 路径对应的值长度（数组元素个数或字符串长度）。
 *
 * @param json JSON 字符串
 * @param jsonPath JSONPath 表达式
 * @return 长度，路径不存在返回 0
 */
 int length(String json, String jsonPath);

 // ==================== 链式方法 ====================

 /**
 * 解析 JSON 字符串，进入链式操作模式。
 *
 * <p>调用此方法后，后续的 {@link #read(String)}、{@link #set(String, Object)}、
 * {@link #delete(String)}、{@link #add(String, Object)}、{@link #put(String, String, Object)}
 * 等操作将作用于已解析的内部文档上，最后通过 {@link #toJson()} 输出结果。</p>
 *
 * @param json JSON 字符串
 * @return 当前实例（链式调用）
 */
 JsonPath parse(String json);

 /**
 * 在链式模式下读取当前文档中 JSONPath 表达式的值。
 *
 * @param jsonPath JSONPath 表达式
 * @param <T> 返回值类型
 * @return JSONPath 指向的值
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 <T> T read(String jsonPath);

 /**
 * 在链式模式下读取当前文档中指定类型的值。
 *
 * @param jsonPath JSONPath 表达式
 * @param type 目标类型
 * @param <T> 返回值类型
 * @return 解析后的值
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 <T> T read(String jsonPath, Class<T> type);

 /**
 * 在链式模式下设置当前文档中指定路径的值。
 *
 * @param jsonPath JSONPath 表达式
 * @param value 要设置的新值
 * @return 当前实例（链式调用）
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 JsonPath set(String jsonPath, Object value);

 /**
 * 在链式模式下删除当前文档中指定路径的值。
 *
 * @param jsonPath JSONPath 表达式
 * @return 当前实例（链式调用）
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 JsonPath delete(String jsonPath);

 /**
 * 在链式模式下向当前文档的数组末尾追加元素。
 *
 * @param jsonPath JSONPath 表达式，应指向数组
 * @param value 要添加的元素
 * @return 当前实例（链式调用）
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 JsonPath add(String jsonPath, Object value);

 /**
 * 在链式模式下向当前文档的对象设置键值对。
 *
 * @param jsonPath JSONPath 表达式，应指向对象
 * @param key 键名
 * @param value 键值
 * @return 当前实例（链式调用）
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 JsonPath put(String jsonPath, String key, Object value);

 /**
 * 在链式模式下判断当前文档中 JSONPath 路径是否存在。
 *
 * @param jsonPath JSONPath 表达式
 * @return 路径存在返回 true
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 boolean isExist(String jsonPath);

 /**
 * 在链式模式下获取当前文档中 JSONPath 路径对应的长度。
 *
 * @param jsonPath JSONPath 表达式
 * @return 长度
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 int length(String jsonPath);

 /**
 * 在链式模式下将当前文档序列化为 JSON 字符串。
 *
 * @return JSON 字符串
 * @throws IllegalStateException 如果未先调用 {@link #parse(String)}
 */
 String toJson();

 // ==================== 工厂方法 ====================

 /**
 * 获取默认的 JSONPath SPI 实现实例。
 *
 * <p>通过 {@link ServiceProvider} SPI 机制自动发现并加载默认实现。
 * 如果未找到 SPI 实现，则尝试按名称 "json" 查找。</p>
 *
 * @return JsonPath 实例
 */
 static JsonPath getInstance() {
 ServiceProvider<JsonPath> provider = ServiceProvider.of(JsonPath.class);
 JsonPath instance = provider.getDefault();
 if (instance == null) {
 instance = provider.getExtension("json");
 }
 if (instance == null) {
 throw new IllegalStateException("未找到 JsonPath SPI 实现，请添加 utils-support-json-starter 依赖");
 }
 return instance;
 }
}
