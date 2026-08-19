package com.chua.common.support.lang.json;

import com.chua.common.support.converter.Converter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON 节点封装类，提供统一的 JSON 数据树遍历与类型安全取值 API。
 *
 * <p>JsonNode 是对任意 JSON 值（对象、数组、标量、null）的不可变包装，
 * 支持链式导航、路径查询和类型安全转换。</p>
 *
 * <h3>核心能力：</h3>
 * <ul>
 *   <li><b>节点导航</b> — {@link #get(String)}、{@link #get(int)} 按键名或索引访问子节点</li>
 *   <li><b>路径查询</b> — {@link #path(String)} 通过 JSONPath 表达式定位节点</li>
 *   <li><b>类型取值</b> — {@link #toStringValue()}、{@link #toIntValue()}、{@link #toLongValue()} 等，
 *       内部通过 {@link Converter} 实现自动类型转换</li>
 *   <li><b>类型判断</b> — {@link #isObject()}、{@link #isArray()}、{@link #isValueNode()} 等</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * JsonNode root = Json.parse("{\"name\":\"Alice\",\"age\":30,\"scores\":[90,85,92]}");
 *
 * String name  = root.get("name").toStringValue();          // "Alice"
 * int    age   = root.get("age").toIntValue();              // 30
 * int    score = root.get("scores").get(0).toIntValue();   // 90
 *
 * // JSONPath 查询
 * JsonNode node = root.path("$.scores[1]");                 // 85
 * }</pre>
 *
 * @author CH
 * @see Json#parse(String)
 * @see Converter
 * @since 4.0
 */
public class JsonNode {

    /**
     * 空值/缺失节点的单例，表示路径不存在或值为 null。
     */
    static final JsonNode MISSING = new JsonNode(null, true);

    /**
     * 节点持有的原始值，可能是 Map、List、String、Number、Boolean 或 null。
     */
    private final Object value;

    /**
     * 是否为"缺失"节点（路径不存在），与值为 null 的节点区分。
     */
    private final boolean missing;

    /**
     * 父级节点引用，用于链式构建时通过 {@link #end()} 返回父级。
     * 仅在 {@link #putObject(String)} 和 {@link #putArray(String)} 创建的子节点上设置，
     * 根节点和解析产生的节点此字段为 null。
     */
    private final JsonNode parent;

    // ==================== 构造方法 ====================

    /**
     * 使用原始值构造 JsonNode（无父级引用）。
     *
     * <p>供各 {@link JsonProvider} 实现（Jackson / Gson / Fory 等）跨包构造节点使用。</p>
     *
     * @param value 原始 JSON 值
     */
    public JsonNode(Object value) {
        this.value = value;
        this.missing = false;
        this.parent = null;
    }

    /**
     * 内部构造方法，可指定缺失标记。
     *
     * @param value   原始值
     * @param missing 是否为缺失节点
     */
    private JsonNode(Object value, boolean missing) {
        this.value = value;
        this.missing = missing;
        this.parent = null;
    }

    /**
     * 链式构建用构造方法，指定父级节点。
     *
     * @param value  原始值
     * @param parent 父级 JsonNode
     */
    private JsonNode(Object value, JsonNode parent) {
        this.value = value;
        this.missing = false;
        this.parent = parent;
    }

    // ==================== 静态工厂 ====================

    /**
     * 将原始值包装为 JsonNode（走当前 {@link JsonProvider} SPI 节点工厂）。
     * <ul>
     *   <li>如果值为 null，返回 {@link #MISSING}</li>
     *   <li>如果值已经是 JsonNode，直接返回</li>
     *   <li>否则通过 {@link Json#createJsonNode(Object)} 创建节点，类型随当前实现切换</li>
     * </ul>
     *
     * @param value 原始值
     * @return JsonNode 实例
     */
    public static JsonNode valueOf(Object value) {
        if (value == null) {
            return MISSING;
        }
        if (value instanceof JsonNode) {
            return (JsonNode) value;
        }
        return Json.createJsonNode(value);
    }

    // ==================== 节点导航 ====================

    /**
     * 按键名获取子节点（适用于 JSON 对象）。
     *
     * <p>如果当前节点不是对象类型，或键名不存在，返回 {@link #MISSING}。</p>
     *
     * @param key 键名
     * @return 子节点
     */
    public JsonNode get(String key) {
        if (missing || value == null) {
            return MISSING;
        }
        if (value instanceof Map) {
            Object child = ((Map<?, ?>) value).get(key);
            return valueOf(child);
        }
        return MISSING;
    }

    /**
     * 按索引获取子节点（适用于 JSON 数组）。
     *
     * <p>如果当前节点不是数组类型，或索引越界，返回 {@link #MISSING}。</p>
     *
     * @param index 索引（从 0 开始）
     * @return 子节点
     */
    public JsonNode get(int index) {
        if (missing || value == null) {
            return MISSING;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (index >= 0 && index < list.size()) {
                return valueOf(list.get(index));
            }
        }
        return MISSING;
    }

    /**
     * 通过 JSONPath 表达式获取子节点。
     *
     * <p>委托给 {@link JsonPath} SPI 实现进行路径查询，查询结果自动包装为 JsonNode。</p>
     *
     * @param jsonPath JSONPath 表达式，如 {@code "$.store.book[0].title"}
     * @return 匹配的子节点，路径不存在返回 {@link #MISSING}
     */
    public JsonNode path(String jsonPath) {
        if (missing || value == null) {
            return MISSING;
        }
        try {
            String jsonStr;
            if (value instanceof String) {
                jsonStr = (String) value;
            } else {
                jsonStr = Json.toJson(value);
            }
            Object result = JsonPath.getInstance().read(jsonStr, jsonPath);
            return valueOf(result);
        } catch (Exception e) {
            return MISSING;
        }
    }

    // ==================== 类型判断 ====================

    /**
     * 判断当前节点是否为缺失节点（路径不存在）。
     *
     * @return 缺失返回 true
     */
    public boolean isMissingValue() {
        return missing;
    }

    /**
     * 判断当前节点的值是否为 null。
     *
     * <p>注意：值为 null 与节点缺失是不同的状态。
     * JSON 中的 {@code {"key": null}} 会产生一个 isNull()=true 但 isMissingValue()=false 的节点。</p>
     *
     * @return 值为 null 返回 true
     */
    public boolean isNull() {
        return !missing && value == null;
    }

    /**
     * 判断当前节点是否为 JSON 对象（Map）。
     *
     * @return 是对象返回 true
     */
    public boolean isObject() {
        return !missing && value instanceof Map;
    }

    /**
     * 判断当前节点是否为 JSON 数组（List）。
     *
     * @return 是数组返回 true
     */
    public boolean isArray() {
        return !missing && value instanceof List;
    }

    /**
     * 判断当前节点是否为容器节点（对象或数组）。
     *
     * @return 是容器返回 true
     */
    public boolean isContainerNode() {
        return isObject() || isArray();
    }

    /**
     * 判断当前节点是否为值节点（非容器、非缺失、非 null）。
     *
     * @return 是值节点返回 true
     */
    public boolean isValueNode() {
        return !missing && value != null && !isContainerNode();
    }

    /**
     * 判断当前节点是否为字符串类型。
     *
     * @return 是字符串返回 true
     */
    public boolean isString() {
        return !missing && value instanceof String;
    }

    /**
     * 判断当前节点是否为数字类型。
     *
     * @return 是数字返回 true
     */
    public boolean isNumber() {
        return !missing && value instanceof Number;
    }

    /**
     * 判断当前节点是否为布尔类型。
     *
     * @return 是布尔值返回 true
     */
    public boolean isBoolean() {
        return !missing && value instanceof Boolean;
    }

    // ==================== 类型取值（基础） ====================

    /**
     * 获取节点的原始值。
     *
     * @return 原始值，缺失或 null 返回 null
     */
    public Object getValue() {
        return missing ? null : value;
    }

    /**
     * 将节点值转换为 String。
     *
     * @return 字符串值，缺失或 null 返回 null
     */
    public String toStringValue() {
        if (missing || value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return Converter.convertIfNecessary(value, String.class);
    }

    /**
     * 将节点值转换为 String，带默认值。
     *
     * @param defaultValue 默认值
     * @return 字符串值
     */
    public String toStringValue(String defaultValue) {
        String result = toStringValue();
        return result != null ? result : defaultValue;
    }

    /**
     * 将节点值转换为 int。
     *
     * @return int 值，缺失或转换失败返回 0
     */
    public int toIntValue() {
        if (missing || value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        Integer result = Converter.convertIfNecessary(value, Integer.class);
        return result != null ? result : 0;
    }

    /**
     * 将节点值转换为 int，带默认值。
     *
     * @param defaultValue 默认值
     * @return int 值
     */
    public int toIntValue(int defaultValue) {
        if (missing || value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        Integer result = Converter.convertIfNecessary(value, Integer.class);
        return result != null ? result : defaultValue;
    }

    /**
     * 将节点值转换为 long。
     *
     * @return long 值，缺失或转换失败返回 0L
     */
    public long toLongValue() {
        if (missing || value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        Long result = Converter.convertIfNecessary(value, Long.class);
        return result != null ? result : 0L;
    }

    /**
     * 将节点值转换为 long，带默认值。
     *
     * @param defaultValue 默认值
     * @return long 值
     */
    public long toLongValue(long defaultValue) {
        if (missing || value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        Long result = Converter.convertIfNecessary(value, Long.class);
        return result != null ? result : defaultValue;
    }

    /**
     * 将节点值转换为 double。
     *
     * @return double 值，缺失或转换失败返回 0.0
     */
    public double toDoubleValue() {
        if (missing || value == null) {
            return 0.0;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        Double result = Converter.convertIfNecessary(value, Double.class);
        return result != null ? result : 0.0;
    }

    /**
     * 将节点值转换为 double，带默认值。
     *
     * @param defaultValue 默认值
     * @return double 值
     */
    public double toDoubleValue(double defaultValue) {
        if (missing || value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        Double result = Converter.convertIfNecessary(value, Double.class);
        return result != null ? result : defaultValue;
    }

    /**
     * 将节点值转换为 float。
     *
     * @return float 值，缺失或转换失败返回 0.0f
     */
    public float toFloatValue() {
        if (missing || value == null) {
            return 0.0f;
        }
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        Float result = Converter.convertIfNecessary(value, Float.class);
        return result != null ? result : 0.0f;
    }

    /**
     * 将节点值转换为 float，带默认值。
     *
     * @param defaultValue 默认值
     * @return float 值
     */
    public float toFloatValue(float defaultValue) {
        if (missing || value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        Float result = Converter.convertIfNecessary(value, Float.class);
        return result != null ? result : defaultValue;
    }

    /**
     * 将节点值转换为 boolean。
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>Boolean 值直接返回</li>
     *   <li>字符串 "true"（不区分大小写）返回 true</li>
     *   <li>数字非零返回 true</li>
     * </ul>
     *
     * @return boolean 值，缺失或转换失败返回 false
     */
    public boolean toBooleanValue() {
        if (missing || value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        Boolean result = Converter.convertIfNecessary(value, Boolean.class);
        return result != null ? result : false;
    }

    /**
     * 将节点值转换为 boolean，带默认值。
     *
     * @param defaultValue 默认值
     * @return boolean 值
     */
    public boolean toBooleanValue(boolean defaultValue) {
        if (missing || value == null) {
            return defaultValue;
        }
        return toBooleanValue();
    }

    /**
     * 将节点值转换为 BigDecimal。
     *
     * @return BigDecimal 值，缺失或转换失败返回 null
     */
    public BigDecimal toBigDecimal() {
        if (missing || value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return Converter.convertIfNecessary(value, BigDecimal.class);
    }

    /**
     * 将节点值转换为 BigInteger。
     *
     * @return BigInteger 值，缺失或转换失败返回 null
     */
    public BigInteger toBigInteger() {
        if (missing || value == null) {
            return null;
        }
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        return Converter.convertIfNecessary(value, BigInteger.class);
    }

    // ==================== 类型取值（泛型） ====================

    /**
     * 将节点值转换为指定类型，通过 {@link Converter} 进行类型转换。
     *
     * @param type 目标类型
     * @param <T>  泛型类型
     * @return 转换后的值，缺失或转换失败返回 null
     */
    public <T> T toValue(Class<T> type) {
        if (missing || value == null) {
            return null;
        }
        if (type.isAssignableFrom(value.getClass())) {
            return type.cast(value);
        }
        return Converter.convertIfNecessary(value, type);
    }

    /**
     * 将节点值转换为指定类型，带默认值。
     *
     * @param type         目标类型
     * @param defaultValue 默认值
     * @param <T>          泛型类型
     * @return 转换后的值
     */
    public <T> T toValue(Class<T> type, T defaultValue) {
        T result = toValue(type);
        return result != null ? result : defaultValue;
    }

    // ==================== 容器取值 ====================

    /**
     * 将节点值作为 JsonObject 返回。
     *
     * @return JsonObject，非对象类型返回空 JsonObject
     */
    public JsonObject toJsonObject() {
        if (missing || value == null) {
            return JsonObject.empty();
        }
        if (value instanceof JsonObject) {
            return (JsonObject) value;
        }
        if (value instanceof Map) {
            return Json.createJsonObject((Map) value);
        }
        return JsonObject.empty();
    }

    /**
     * 将节点值作为 JsonArray 返回。
     *
     * @return JsonArray，非数组类型返回空 JsonArray
     */
    public JsonArray toJsonArray() {
        if (missing || value == null) {
            return JsonArray.empty();
        }
        if (value instanceof JsonArray) {
            return (JsonArray) value;
        }
        if (value instanceof Collection) {
            return Json.createJsonArray((Collection) value);
        }
        return JsonArray.empty();
    }

    /**
     * 获取数组节点的元素个数。
     *
     * @return 元素个数，非数组返回 0
     */
    public int size() {
        if (missing || value == null) {
            return 0;
        }
        if (value instanceof List) {
            return ((List<?>) value).size();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).size();
        }
        return 0;
    }

    // ==================== 链式构建 ====================

    /**
     * 向当前对象节点中添加键值对，支持链式调用。
     *
     * <p>仅当当前节点为 JSON 对象（{@link #isObject()} 返回 true）时生效，
     * 否则直接返回自身不做任何修改。</p>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * JsonNode node = Json.build()
     *     .put("name", "Alice")
     *     .put("age", 30)
     *     .put("active", true);
     * String json = node.toString(); // {"name":"Alice","age":30,"active":true}
     * }</pre>
     *
     * @param key   键名
     * @param value 值（可以为 null、String、Number、Boolean、Map、List 等）
     * @return 当前 JsonNode 实例，支持链式调用
     */
    @SuppressWarnings("unchecked")
    public JsonNode put(String key, Object value) {
        if (this.value instanceof Map) {
            ((Map<String, Object>) this.value).put(key, value);
        }
        return this;
    }

    /**
     * 条件性向对象节点添加键值对，仅当条件为 true 时执行添加操作。
     *
     * @param condition 执行条件
     * @param key       键名
     * @param value     值
     * @return 当前 JsonNode 实例
     */
    public JsonNode put(boolean condition, String key, Object value) {
        if (condition) {
            put(key, value);
        }
        return this;
    }

    /**
     * 批量向对象节点添加键值对。
     *
     * @param map 键值对集合
     * @return 当前 JsonNode 实例
     */
    @SuppressWarnings("unchecked")
    public JsonNode putAll(Map<String, ?> map) {
        if (this.value instanceof Map && map != null) {
            ((Map<String, Object>) this.value).putAll(map);
        }
        return this;
    }

    /**
     * 向对象节点添加一个数组属性，并直接填充元素，支持链式调用。
     *
     * <p>等价于 {@code put(key, Json.buildArray().add(elements...))}，但更简洁。</p>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * JsonNode node = Json.build()
     *     .put("name", "Alice")
     *     .putArray("scores", 90, 85, 92);
     * // {"name":"Alice","scores":[90,85,92]}
     * }</pre>
     *
     * @param key      键名
     * @param elements 数组元素
     * @return 当前 JsonNode 实例，支持链式调用
     */
    @SuppressWarnings("unchecked")
    public JsonNode putArray(String key, Object... elements) {
        if (this.value instanceof Map) {
            JsonArray array = Json.createJsonArray();
            if (elements != null) {
                for (Object e : elements) {
                    array.add(e);
                }
            }
            ((Map<String, Object>) this.value).put(key, array);
        }
        return this;
    }

    /**
     * 进入嵌套对象构建，在当前对象节点下添加一个子对象属性，并返回子对象的 JsonNode。
     *
     * <p>返回的 JsonNode 包装新创建的嵌套 JsonObject，可在其上继续调用 {@code put} 等方法；
     * 构建完嵌套对象后，通过 {@link #end()} 返回父级继续链式调用。</p>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * JsonNode node = Json.build()
     *     .put("name", "Alice")
     *     .startObject("address")
     *         .put("city", "Beijing")
     *         .put("zip", "100000")
     *         .endObject()
     *     .put("age", 30);
     * // {"name":"Alice","address":{"city":"Beijing","zip":"100000"},"age":30}
     * }</pre>
     *
     * @param key 键名
     * @return 嵌套对象的 JsonNode，支持继续链式构建
     */
    @SuppressWarnings("unchecked")
    public JsonNode startObject(String key) {
        if (this.value instanceof Map) {
            JsonObject obj = Json.createJsonObject();
            ((Map<String, Object>) this.value).put(key, obj);
            return new JsonNode(obj, this);
        }
        return this;
    }

    /**
     * 进入嵌套数组构建，在当前对象节点下添加一个子数组属性，并返回子数组的 JsonNode。
     *
     * <p>返回的 JsonNode 包装新创建的嵌套 JsonArray，可在其上继续调用 {@code add} 等方法；
     * 构建完嵌套数组后，通过 {@link #end()} 返回父级继续链式调用。</p>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * JsonNode node = Json.build()
     *     .put("name", "Alice")
     *     .startArray("scores")
     *         .add(90).add(85).add(92)
     *         .endArray()
     *     .put("age", 30);
     * // {"name":"Alice","scores":[90,85,92],"age":30}
     * }</pre>
     *
     * @param key 键名
     * @return 嵌套数组的 JsonNode，支持继续链式构建
     */
    @SuppressWarnings("unchecked")
    public JsonNode startArray(String key) {
        if (this.value instanceof Map) {
            JsonArray array = Json.createJsonArray();
            ((Map<String, Object>) this.value).put(key, array);
            return new JsonNode(array, this);
        }
        return this;
    }

    /**
     * 结束当前嵌套构建，返回父级节点。
     *
     * <p>用于在 {@link #startObject(String)} 或 {@link #startArray(String)} 之后，
     * 将构建上下文切回父级节点继续链式调用。也可使用语义更明确的
     * {@link #endObject()} 或 {@link #endArray()}。</p>
     *
     * @return 父级 JsonNode，若无父级则返回自身
     * @see #startObject(String)
     * @see #startArray(String)
     * @see #endObject()
     * @see #endArray()
     */
    public JsonNode end() {
        return parent != null ? parent : this;
    }

    /**
     * 结束嵌套对象构建，返回父级节点。
     *
     * <p>语义等同于 {@link #end()}，但更具可读性，明确表示结束的是一个对象层级。</p>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * JsonNode node = Json.build()
     *     .put("name", "Alice")
     *     .startObject("address")
     *         .put("city", "Beijing")
     *         .put("zip", "100000")
     *         .endObject()
     *     .put("age", 30);
     * // {"name":"Alice","address":{"city":"Beijing","zip":"100000"},"age":30}
     * }</pre>
     *
     * @return 父级 JsonNode，若无父级则返回自身
     * @see #startObject(String)
     */
    public JsonNode endObject() {
        return end();
    }

    /**
     * 结束嵌套数组构建，返回父级节点。
     *
     * <p>语义等同于 {@link #end()}，但更具可读性，明确表示结束的是一个数组层级。</p>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * JsonNode node = Json.build()
     *     .put("name", "Alice")
     *     .startArray("scores")
     *         .add(90).add(85).add(92)
     *         .endArray()
     *     .put("age", 30);
     * // {"name":"Alice","scores":[90,85,92],"age":30}
     * }</pre>
     *
     * @return 父级 JsonNode，若无父级则返回自身
     * @see #startArray(String)
     */
    public JsonNode endArray() {
        return end();
    }

    /**
     * 向当前数组节点中添加元素，支持链式调用。
     *
     * <p>仅当当前节点为 JSON 数组（{@link #isArray()} 返回 true）时生效，
     * 否则直接返回自身不做任何修改。</p>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * JsonNode arr = Json.buildArray()
     *     .add("apple")
     *     .add(42)
     *     .add(true);
     * String json = arr.toString(); // ["apple",42,true]
     * }</pre>
     *
     * @param element 元素值
     * @return 当前 JsonNode 实例，支持链式调用
     */
    @SuppressWarnings("unchecked")
    public JsonNode add(Object element) {
        if (this.value instanceof List) {
            ((List<Object>) this.value).add(element);
        }
        return this;
    }

    /**
     * 条件性向数组节点添加元素，仅当条件为 true 时执行添加操作。
     *
     * @param condition 执行条件
     * @param element   元素值
     * @return 当前 JsonNode 实例
     */
    public JsonNode add(boolean condition, Object element) {
        if (condition) {
            add(element);
        }
        return this;
    }

    /**
     * 向数组节点指定位置插入元素。
     *
     * @param index   插入位置
     * @param element 元素值
     * @return 当前 JsonNode 实例
     */
    @SuppressWarnings("unchecked")
    public JsonNode add(int index, Object element) {
        if (this.value instanceof List) {
            ((List<Object>) this.value).add(index, element);
        }
        return this;
    }

    /**
     * 批量向数组节点添加元素。
     *
     * @param elements 元素集合
     * @return 当前 JsonNode 实例
     */
    @SuppressWarnings("unchecked")
    public JsonNode addAll(Collection<?> elements) {
        if (this.value instanceof List && elements != null) {
            ((List<Object>) this.value).addAll(elements);
        }
        return this;
    }

    /**
     * 从对象节点中移除指定键。
     *
     * @param key 键名
     * @return 当前 JsonNode 实例
     */
    public JsonNode remove(String key) {
        if (this.value instanceof Map) {
            ((Map<?, ?>) this.value).remove(key);
        }
        return this;
    }

    /**
     * 从数组节点中移除指定位置的元素。
     *
     * @param index 索引
     * @return 当前 JsonNode 实例
     */
    public JsonNode remove(int index) {
        if (this.value instanceof List) {
            List<?> list = (List<?>) this.value;
            if (index >= 0 && index < list.size()) {
                list.remove(index);
            }
        }
        return this;
    }

    /**
     * 条件性从对象节点移除指定键。
     *
     * @param condition 执行条件
     * @param key       键名
     * @return 当前 JsonNode 实例
     */
    public JsonNode remove(boolean condition, String key) {
        if (condition) {
            remove(key);
        }
        return this;
    }

    // ==================== 对象方法 ====================

    @Override
    /** ToString */
    public String toString() {
        if (missing) {
            return "MISSING";
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return (String) value;
        }
        return Json.toJson(value);
    }

    @Override
    /** 判断相等 */
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        JsonNode jsonNode = (JsonNode) o;
        if (missing != jsonNode.missing) {
            return false;
        }
        if (value == null) {
            return jsonNode.value == null;
        }
        return value.equals(jsonNode.value);
    }

    @Override
    /** HashCode */
    public int hashCode() {
        int result = missing ? 1 : 0;
        result = 31 * result + (value != null ? value.hashCode() : 0);
        return result;
    }
}