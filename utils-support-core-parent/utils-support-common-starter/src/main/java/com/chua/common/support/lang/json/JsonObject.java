package com.chua.common.support.lang.json;


import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.utils.MapUtils;

import java.util.*;
import java.util.function.BiConsumer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;


/**
 * JSON 对象类，继承自 LinkedHashMap，用于存储和操作键值对。
 * 提供了流畅的 API 方法以及便捷的方法来访问嵌套的 JSON 结构（如 JsonObject 和 JsonArray）。
 *
 * @author CH
 */
@NullMarked
@SuppressWarnings("ALL")
public class JsonObject extends LinkedHashMap<String, @Nullable Object> {

    /**
     * 源数据映射，默认指向当前实例。
     */
    protected Map<String, @Nullable Object> source = this;

    /**
     * 静态空对象实例，用于避免频繁创建空的 JsonObject。
     */
    static final JsonObject EMPTY = new JsonObject();

    /**
     * 构造一个空的 JsonObject。
     */
    public JsonObject() {
    }

    /**
     * 通过指定的 Map 构造 JsonObject。
     *
     * @param m 包含初始数据的 Map，如果为 null 则不做任何操作。
     */
    public JsonObject(@Nullable Map m) {
        if (null == m) {
            return;
        }
        putAll(m);
    }

    /**
     * 获取一个空的 JsonObject 单例实例。
     *
     * @return 空的 JsonObject 实例
     */
    public static JsonObject empty() {
        return EMPTY;
    }

    /**
     * 创建一个包含单个键值对的 JsonObject。
     *
     * @param key   键
     * @param value 值
     * @return 包含指定键值对的新 JsonObject
     */
    public static JsonObject of(String key, @Nullable Object value) {
        return JsonObject.create().fluent(key, value);
    }

    /**
     * 创建一个空的 JsonObject。
     *
     * @return 新的空 JsonObject
     */
    public static JsonObject of() {
        return create();
    }

    /**
     * 解析 JSON 字符串并返回对应的 JsonObject。
     *
     * @param json JSON 格式的字符串
     * @return 解析后的 JsonObject
     */
    public static JsonObject parse(@Nullable String json) {
        return Json.getJsonObject(json);
    }

    /**
     * 根据指定的键获取对应的对象值。
     *
     * @param string 键名
     * @return 对应的对象，如果不存在则返回 null
     */
    public @Nullable Object getObject(String string) {
        return get(string);
    }

    /**
     * 添加键值对到当前对象中，支持链式调用。
     *
     * @param key   键
     * @param value 值
     * @return 当前 JsonObject 实例
     */
    public JsonObject fluent(String key, @Nullable Object value) {
        this.put(key, value);
        return this;
    }

    /**
     * 将当前的 JsonObject 转换为标准的 Java Map。
     *
     * @return 转换后的 Map 对象
     */
    public Map<String, @Nullable Object> toMap() {
        return this;
    }

    /**
     * 获取指定名称的子 JsonObject。
     * 该方法会智能处理不同类型的值：
     * - 如果是 Map 或 JsonObject，直接转换或返回。
     * - 如果是 String，尝试解析为 JSON 对象。
     * - 如果值为 null 或无法识别，返回空对象。
     *
     * @param name 子对象的键名
     * @return 对应的 JsonObject，如果不存在或类型不匹配则返回空对象
     */
    public JsonObject getJsonObject(String name) {
        @Nullable Object object = get(name);
        if (null == object) {
            return EMPTY;
        }

        if (object instanceof Map) {
            return new JsonObject((Map) object);
        }
        if (object instanceof JsonObject) {
            return (JsonObject) object;
        }

        if (object instanceof String) {
            return Json.getJsonObject((String) object);
        }
        return EMPTY;
    }

    /**
     * 从指定名称的数组中获取索引位置的 JsonObject。
     * 如果该名称对应的不是数组，或者索引越界，捕获异常并返回空对象。
     *
     * @param index 数组中的索引位置
     * @param name  数组的键名
     * @return 指定索引处的 JsonObject，如果出错则返回空对象
     */
    public JsonObject getJsonObject(int index, String name) {
        // 尝试获取数组并从中取出指定索引的对象
        try {
            return getJsonArray(name).getJsonObject(index);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * 获取指定名称的 JsonArray。
     * 该方法具有强大的容错能力：
     * - 如果值是 Collection，直接包装。
     * - 如果值是字符串且以 '[' 开头，解析为数组。
     * - 如果值是字符串且以 '{' 开头，将其视为包含单个对象的数组。
     * - 其他情况，将值包装在只有一个元素的列表中。
     *
     * @param item 数组的键名
     * @return 对应的 JsonArray
     */
    public JsonArray getJsonArray(String item) {
        @Nullable Object object = Optional.ofNullable(get(item)).orElse(Collections.emptyList());
        if (object instanceof Collection) {
            return new JsonArray((Collection) object);
        }

        if (object instanceof String) {
            if (object.toString().startsWith("[")) {
                return Json.getJsonArray(object.toString());
            }
            if (object.toString().startsWith("{")) {
                return new JsonArray(Collections.singletonList(Json.getJsonObject(object.toString())));
            }
        }

        return new JsonArray(Collections.singletonList(object));
    }

    /**
     * 获取指定类型的值，如果获取失败或为空，则返回默认值。
     *
     * @param name         键名
     * @param defaultValue 默认返回值
     * @param type         目标类型
     * @param <E>          目标类型
     * @return 转换后的值或默认值
     */
    public <E> E getType(String name, E defaultValue, Class<E> type) {
        return Optional.ofNullable(getObject(name, type)).orElse(defaultValue);
    }

    /**
     * 获取指定键的值，并尝试将其转换为指定的类型。
     *
     * @param name 键名
     * @param type 目标类型
     * @param <E>  目标类型
     * @return 转换后的对象
     */
    public <E> @Nullable E getObject(String name, Class<E> type) {
        return Converter.convertIfNecessary(MapUtils.getObject(this, name), type);
    }

    /**
     * 创建一个空的 JsonObject。
     *
     * @return 新的 JsonObject 实例
     */
    public static JsonObject create() {
        return new JsonObject();
    }

    /**
     * 将一个 Java Bean 对象转换为 JsonObject。
     *
     * @param bean 源 Bean 对象
     * @return 转换后的 JsonObject
     */
    public static JsonObject create(@Nullable Object bean) {
        return new JsonObject(BeanUtils.objectToMap(bean));
    }

    /**
     * 将 JsonObject 中的所有值转换为字符串，生成一个新的 Map。
     *
     * @return 键值均为字符串的 Map
     */
    public Map<String, String> toStringMap() {
        return MapUtils.asStringMap(this);
    }

    /**
     * 将 JsonObject 序列化为字节数组（JSON 格式）。
     *
     * @return JSON 格式的字节数组
     */
    public byte[] toByteArray() {
        return Json.toJsonByte(this);
    }

    /**
     * 添加键值对（无条件版本）。
     *
     * @param name 键名
     * @param data 值
     * @return 当前 JsonObject 实例
     */
    public JsonObject fluentPut(String name, @Nullable Object data) {
        this.put(name, data);
        return this;
    }

    /**
     * 添加键值对（无条件版本）。
     *
     * @param map 键值对源 Map
     * @return 当前 JsonObject 实例
     */
    public JsonObject fluentPut(@Nullable Map<? extends Object, ? extends Object> map) {
        if (map == null) {
            return this;
        }
        for (Map.Entry<? extends Object, ? extends Object> entry : map.entrySet()) {
            @Nullable Object key = entry.getKey();
            @Nullable Object value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            this.put(key.toString(), value);
        }
        return this;
    }

    /**
     * 添加键值对（条件版本）。
     * 只有当条件为 true 时才执行添加操作。
     *
     * @param condition 判断条件
     * @param name      键名
     * @param data      值
     * @return 当前 JsonObject 实例
     */
    public JsonObject fluentPut(boolean condition, String name, @Nullable Object data) {
        if (condition) {
            return fluentPut(name, data);
        }
        return this;
    }

    /**
     * 将当前的 JsonObject 序列化为 JSON 字符串。
     *
     * @return JSON 字符串表示
     */
    public String toJSONString() {
        return Json.toJson(this);
    }

    /**
     * 获取名为 names 的 JSONObject（别名方法，功能同 getJsonObject）。
     *
     * @param names 键名
     * @return 对应的 JsonObject
     */
    public JsonObject getJSONObject(String names) {
        return getJsonObject(names);
    }

    /**
     * 将当前的 JsonObject 序列化为字节数组（别名方法，功能同 toByteArray）。
     *
     * @return JSON 格式的字节数组
     */
    public byte[] toJSONBBytes() {
        return toByteArray();
    }

    /**
     * 重写 forEach 方法，在遍历过程中自动将内部的 Map 转换为 JsonObject，
     * 将 Collection 转换为 JsonArray，确保回调函数接收到的都是统一的 JSON 类型对象。
     *
     * @param action 要执行的消费操作
     */
    @Override
    public void forEach(BiConsumer<? super String, ? super @Nullable Object> action) {
        super.forEach(new BiConsumer<String, @Nullable Object>() {
            @Override
            public void accept(String s, @Nullable Object o) {
                if (o instanceof Map) {
                    action.accept(s, new JsonObject((Map) o));
                    return;
                }

                if (o instanceof Collection) {
                    action.accept(s, new JsonArray((Collection) o));
                    return;
                }

                action.accept(s, o);
            }
        });
    }

    /**
     * 将当前的 JsonObject 序列化为 JSON 字符串后，反序列化为指定的 Java 类型对象。
     *
     * @param target 目标类型
     * @param <T>    目标类型
     * @return 转换后的 Java 对象，如果转换失败可能返回 null
     */
    public <T> @Nullable T toJavaType(Class<T> target) {
        return Json.fromJson(this.toJSONString(), target);
    }

    /**
     * 检查当前对象是否包含指定的键。
     *
     * @param name 键名
     * @return 如果包含该键返回 true，否则返回 false
     */
    public boolean hasKey(String name) {
        return this.containsKey(name);
    }

    /**
     * 获取当前对象的 JSON 字符串表示（别名方法，功能同 toJSONString）。
     *
     * @return JSON 字符串
     */
    public String json() {
        return toJSONString();
    }

}
