package com.chua.common.support.lang.json;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.function.SafeConsumer;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.function.Consumer;


/**
 * 表示一个 JSON 数组的封装类，继承自 LinkedList。
 * 该类提供了对 JSON 数据结构的便捷操作，包括元素的添加、获取以及类型转换。
 * 它支持将内部存储的 Map 自动转换为 JsonObject，Collection 自动转换为 JsonArray。
 *
 * @author CH
 * @since 4.0.0.42
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JsonArray extends LinkedList<Object> {

 /**
  * 一个单例的空 JsonArray 实例，用于节省内存。
  * 注意：此实例为不可变空集合的替代方案（实际上是一个共享的空实例）。
  */
 private static final JsonArray INSTANCE = new JsonArray();

 /**
  * 获取单例的空 JsonArray 实例。
  *
  * @return 空的 JsonArray 实例
  */
 public static JsonArray getInstance() {
 return INSTANCE;
 }

 /**
  * 默认构造函数，创建一个新的空 JsonArray。
  */
 public JsonArray() {
 }

 /**
  * 拷贝构造方法，从另一个 JsonArray 初始化当前数组。
  *
  * @param array 源 JsonArray
  */
 public JsonArray(JsonArray array) {
 addAll(array);
 }

 /**
  * 从 JsonObject 构造 JsonArray，将对象作为单个元素添加。
  *
  * @param array 源 JsonObject
  */
 public JsonArray(JsonObject array) {
 add(array);
 }

 /**
  * 从 Map 构造 JsonArray，先将 Map 包装成 JsonObject，再将其作为单个元素添加。
  *
  * @param array 源 Map
  */
 public JsonArray(Map array) {
 this(new JsonObject(array));
 }

 /**
  * 从 Collection 构造 JsonArray，将集合中的所有元素添加到当前数组中。
  *
  * @param collection 源 Collection
  */
 public JsonArray(Collection collection) {
 addAll(collection);
 }

 /**
  * 从 Iterable 构造 JsonArray，遍历并添加所有元素。
  *
  * @param collection 源 Iterable
  */
 public JsonArray(Iterable collection) {
 if (null != collection) {
 collection.forEach((SafeConsumer<Object>) this::add);
 }
 }

 /**
  * 获取一个静态的空 JsonArray 实例（与 getInstance 类似）。
  *
  * @return 空的 JsonArray 实例
  */
 public static JsonArray empty() {
 return INSTANCE;
 }    /**
     * 创建一个全新的空 JsonArray 实例（走当前 {@link JsonProvider} SPI 节点工厂）。
     *
     * <p>节点类型随当前实现切换：默认返回通用 {@link JsonArray}，
     * 切换 Gson / Fory 实现后返回其各自的节点子类。</p>
     *
     * @return 新的空 JsonArray 实例
     */
    public static JsonArray of() {
        return Json.createJsonArray();
    }

 /**
  * 解析 JSON 字符串并返回对应的 JsonArray 对象。
  *
  * @param json JSON 格式的字符串
  * @return 解析后的 JsonArray
  */
 public static JsonArray parse(String json) {
 return Json.getJsonArray(json);
 }    /**
     * 获取指定索引处的元素，并将其包装为 JsonObject 返回（走当前 SPI 节点工厂）。
     *
     * @param i 元素索引
     * @return 对应索引处的 JsonObject
     */
    public JsonObject getJsonObject(int i) {
        Map raw = getJSONObject(i);
        return Json.createJsonObject(raw);
    }

 /**
  * 获取指定索引处的原始 Map 对象（内部实现方法）。
  *
  * @param i 元素索引
  * @return 对应索引处的 Map
  */
 private Map getJSONObject(int i) {
 return (Map) get(i);
 }    /**
     * 获取指定索引处的元素，并将其包装为 JsonArray 返回（走当前 SPI 节点工厂）。
     *
     * @param i 元素索引
     * @return 对应索引处的 JsonArray
     */
    public JsonArray getJsonArray(int i) {
        return Json.createJsonArray(getJSONArray(i));
    }

 /**
  * 获取指定索引处的原始 Collection 对象（内部实现方法）。
  *
  * @param i 元素索引
  * @return 对应索引处的 Collection
  */
 private Collection<Object> getJSONArray(int i) {
 return (Collection<Object>) super.get(i);
 }

 /**
  * 重写 forEach 方法，在遍历时自动将内部存储的 Map 转换为 JsonObject，
  * 将 Collection 转换为 JsonArray，以便使用者直接获得强类型的对象。
  *
  * @param action 要执行的操作
  */    @Override
    /** ForEach */
    public void forEach(Consumer<? super Object> action) {
        super.forEach(new SafeConsumer<Object>() {
            @Override
            /** SafeAccept */
            public void safeAccept(Object o) throws Throwable {
                if (o instanceof Map) {
                    action.accept(Json.createJsonObject((Map) o));
                    return;
                }

                if (o instanceof Collection) {
                    action.accept(Json.createJsonArray((Collection) o));
                    return;
                }

                action.accept(o);
            }
        });
    }

 /**
  * 以指定类型消费数组中的每个元素，并在消费前尝试进行类型转换。
  *
  * @param consumer 消费器
  * @param type 目标类型
  * @param <E> 泛型类型
  */
 public <E> void forEach(Consumer<E> consumer, Class<E> type) {
 forEach(o -> {
 consumer.accept(Converter.convertIfNecessary(o, type));
 });
 }

 /**
  * Fluent API 风格的方法，添加元素后返回自身，便于链式调用。
  *
  * @param item 要添加的元素
  * @return 当前 JsonArray 实例
  */
 public JsonArray fluent(Object item) {
 add(item);
 return this;
 }

 /**
  * 将当前 JsonArray 序列化为 JSON 字符串。
  *
  * @return JSON 字符串表示
  */
 public String toJSONString() {
 return Json.toJson(this);
 }
}
