package com.chua.common.support.collection;


import com.chua.common.support.matcher.PathMatcher;
import com.chua.common.support.utils.BeanUtils;
import com.chua.common.support.utils.CollectionUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_ASTERISK;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_QUESTION;


/**
 * 扁平化 HashMap 实现，支持将嵌套 Map 结构展平为单层键值对存储。
 * <p>
 * 功能特性：
 * <ul>
 *   <li><b>展平</b>：通过 {@link LevelsClose} 将多级嵌套结构转换为 "a.b.c" 形式的扁平键</li>
 *   <li><b>展开</b>：通过 {@link LevelsOpen} 将扁平键恢复为嵌套结构</li>
 *   <li><b>通配符匹配</b>：支持 * 和 ? 通配符查找值</li>
 *   <li><b>实体对象导入</b>：通过 {@link com.chua.common.support.utils.BeanUtils} 将 Java 对象属性展平为键值对</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public class FlatHashMap implements FlatMap {

    /** 路径匹配器 */
    private static final PathMatcher MATCHER = PathMatcher.INSTANCE;
    /** 关闭层级 */
    private transient final LevelsClose levelsClose = new LevelsClose();
    /** 打开层级 */
    private transient final LevelsOpen levelsOpen = new LevelsOpen();
    /** 扁平映射 */
    private final transient Map<String, Object> flatMap;

    /**
     * 构造一个空的 FlatHashMap 实例，使用默认的 HashMap 作为底层存储。
     */
    protected FlatHashMap() {
        this.flatMap = new HashMap<>();
    }

    /**
     * 根据给定的源 Map 构造 FlatHashMap 实例，自动将嵌套结构展平。
     *
     * @param source 源 Map，包含可能的嵌套结构
     */
    protected FlatHashMap(Map<String, Object> source) {
        this.flatMap = levelsClose.apply(source);
    }

    @Override
    /** Clear */
    public void clear() {
        flatMap.clear();
    }

    @Override
    /** ContainsKey */
    public boolean containsKey(Object key) {
        return flatMap.containsKey(key);
    }

    @Override
    /** ContainsValue */
    public boolean containsValue(Object value) {
        return flatMap.containsValue(value);
    }

    @Override
    /** Entry设置 */
    public Set<Entry<String, Object>> entrySet() {
        return flatMap.entrySet();
    }

    @Override
    /** 获取 */
    public Object get(Object key) {
        return flatMap.get(key);
    }

    @Override
    /** 是否Empty */
    public boolean isEmpty() {
        return flatMap.isEmpty();
    }

    @Override
    /** Key设置 */
    public Set<String> keySet() {
        return flatMap.keySet();
    }

    @Override
    /** Put */
    public Object put(String key, Object value) {
        return flatMap.put(key, value);
    }

    @Override
    /** PutAll */
    public void putAll(Map<? extends String, ?> m) {
        this.flatMap.putAll(levelsClose.apply((Map<String, Object>) m));
    }

    @Override
    /** Put */
    public void put(Object entity) {
        this.flatMap.putAll(BeanUtils.objectToMap(entity));
    }

    @Override
    /** Wildcard */
    public List<Object> wildcard(String key) {
        Map<String, Object> values = new HashMap<>(flatMap.size());
        for (Entry<String, Object> entry : flatMap.entrySet()) {
            String entryKey = entry.getKey();
            if (isMatch(key, entryKey)) {
                values.put(entryKey, entry.getValue());
            }
        }

        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> apply = levelsOpen.apply(values);
        Object simpleValue = simpleValue(apply);

        if (simpleValue instanceof List) {
            return (List<Object>) simpleValue;
        }
        return Collections.singletonList(simpleValue);
    }

    @Override
    /** 移除 */
    public Object remove(Object key) {
        return flatMap.remove(key);
    }

    @Override
    /** 获取大小 */
    public int size() {
        return flatMap.size();
    }

    @Override
    /** Values */
    public Collection<Object> values() {
        return flatMap.values();
    }

    /**
     * 创建一个空的 FlatMap 实例。
     *
     * @return 空的 FlatMap 实例
     */
    public static FlatMap create() {
        return create(Collections.emptyMap());
    }

    /**
     * 根据现有 Map 创建 FlatMap 实例（自动执行展平操作）。
     *
     * @param source 源 Map，其中可能包含嵌套的 Map 或 List 结构
     * @return 展平后的 FlatMap 实例
     */
    public static FlatMap create(Map<String, Object> source) {
        return new FlatHashMap(source);
    }

    /**
     * 判断给定的 entryKey 是否匹配指定的通配符模式。
     * <p>
     * 如果 key 包含 * 或 ? 通配符，则使用 {@link PathMatcher} 进行模式匹配；
     * 否则作为前缀匹配处理（即判断 entryKey 是否以 key 开头）。
     * </p>
     *
     * @param key      通配符模式或前缀字符串
     * @param entryKey 待匹配的键
     * @return 如果匹配返回 true，否则返回 false
     */
    private boolean isMatch(String key, String entryKey) {
        if (key.contains(SYMBOL_QUESTION) || key.contains(SYMBOL_ASTERISK)) {
            return MATCHER.match(key, entryKey);
        }
        return entryKey.startsWith(key);
    }

    /**
     * 从展开后的 Map 中递归提取简化的值。
     * <p>
     * 如果 Map 中仅包含一个条目且该条目的值为 Map 类型，则递归提取内层 Map 的值；
     * 如果仅包含一个条目且值为非 Map 类型，则直接返回该值；
     * 如果包含多个条目，则直接返回整个 Map。
     * </p>
     *
     * @param apply 展开后的嵌套 Map
     * @return 简化后的值，可能为 Map、Object 或 List
     */
    private Object simpleValue(Map<String, Object> apply) {
        Set<String> keySet = apply.keySet();
        if (keySet.size() == 1) {
            String firstKey = CollectionUtils.find(keySet, 0);
            Object o = apply.get(firstKey);
            if (o instanceof Map) {
                apply = (Map<String, Object>) o;
                return simpleValue(apply);
            } else {
                return o;
            }
        }
        return apply;
    }
}
