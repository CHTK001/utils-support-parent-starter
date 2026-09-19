package com.chua.common.support.application;

import com.chua.common.support.constant.CommonConstant;
import com.chua.common.support.function.Upgrade;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.reflection.FieldStation;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局设置工厂。
 * <p>
 * 核心机制：维护一个 group → Bean 列表的内存映射。数据库写入时，通过 {@link #set(String, String, Object)}
 * 将 sys_setting 表的 (group, name, value) 三元组反射映射到 Bean 字段上，
 * 从而实现“一次写入、全局共享”，消费者只需 {@link #get(String, Class)} 获取 Bean 实例即可读取配置。
 * <p>
 * 字段映射规则：Bean 字段名 = sys_setting_name 的 camelCase 形式。
 * 例如 {@code sys_setting_name="CheckCodeOpen"} → Bean 字段 {@code checkCodeOpen}。
 *
 * @author CH
 * @since 2024/8/6
 */
public class GlobalSettingFactory {

    /**
     * 单例实例
    */
    private static final GlobalSettingFactory INSTANCE = new GlobalSettingFactory();

    /**
     * 配置项前缀
    */
    public static volatile String PREFIX = "";

    static final Map<String, Object> CONFIG = new ConcurrentHashMap<>();
    static final Map<String, List<Object>> GROUP = new ConcurrentHashMap<>();
    static final Map<String, Boolean> GROUP_ENABLED = new ConcurrentHashMap<>();

    /**
     * 获取
     * @param type 类型，不允许为 null
     * @return T 对象
     */
    public <T> T get(Class<T> type) {
        for (Map.Entry<String, List<Object>> entry : GROUP.entrySet()) {
            for (Object o : entry.getValue()) {
                if (type.isAssignableFrom(o.getClass())) {
                    return (T) o;
                }
            }
        }
        return null;
    }

    /**
     * 获取
     * @param group 分组，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    public <T> List<T> get(String group) {
        return (List<T>) GROUP.get(PREFIX + group);
    }

    /**
     * 获取
     * @param group 分组，不允许为 null
     * @param name 名称，不允许为 null
     * @return 结果字符串
     */
    public String get(String group, String name) {
        List<Object> ts = GROUP.get(PREFIX + group);
        if (null == ts || ts.isEmpty()) {
            return null;
        }
        for (Object t : ts) {
            if (t == null) {
                continue;
            }
            Object value = FieldStation.of(t).getValue(name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    /**
     * 获取
     * @param group 分组，不允许为 null
     * @param clazz 类，不允许为 null
     * @return T 对象
     */
    public <T> T get(String group, Class<T> clazz) {
        List<T> t = (List<T>) GROUP.get(PREFIX + group);
        if (t == null) {
            T t1 = ClassUtils.forObject(clazz);
            register(group, t1);
            return t1;
        }
        for (T t1 : t) {
            if (null == t1) {
                continue;
            }
            if (clazz.isInstance(t1)) {
                return t1;
            }
        }
        return null;
    }

    /**
     * 注册
     * @param group 分组，不允许为 null
     * @param t 方法入参 t
     */
    public <T> void register(String group, T t) {
        register(group, t, true);
    }

    /**
     * 注册
     * @param group 分组，不允许为 null
     * @param t 方法入参 t
     * @param enabled enabled（布尔开关）
     */
    public <T> void register(String group, T t, boolean enabled) {
        if (null == t) {
            return;
        }
        String groupKey = PREFIX + group;
        List<Object> objects = GROUP.computeIfAbsent(groupKey, k -> new LinkedList<>());
        synchronized (objects) {
            for (Object object : objects) {
                if (object.getClass().isAssignableFrom(t.getClass())) {
                    return;
                }
            }
            objects.add(t);
            GROUP_ENABLED.putIfAbsent(groupKey, enabled);
        }
    }

    /**
     * 获取Instance
     * @return GlobalSetting工厂 对象
     */
    public static GlobalSettingFactory getInstance() {
        return INSTANCE;
    }

    /**
     * 获取All分组
     * @return 结果映射，无数据时为空映射
     */
    public Map<String, List<Object>> getAllGroup() {
        return Collections.unmodifiableMap(GROUP);
    }

    /**
     * 获取All分组Enabled
     * @return 结果映射，无数据时为空映射
     */
    public Map<String, Boolean> getAllGroupEnabled() {
        return Collections.unmodifiableMap(GROUP_ENABLED);
    }

    /**
     * 设置IfNoChange
     * @param group 分组，不允许为 null
     * @param name 名称，不允许为 null
     * @param value 值，不允许为 null
     */
    public synchronized <T> void setIfNoChange(String group, String name, Object value) {
        if (CONFIG.containsKey(PREFIX + group + name)) {
            return;
        }
        set(group, name, value);
        CONFIG.put(PREFIX + group + name, CommonConstant.SYMBOL_EMPTY);
    }

    /**
     * 设置
     * @param group 分组，不允许为 null
     * @param params 参数，不允许为 null
     */
    public synchronized <T> void set(String group, Map<String, Object> params) {
        if (MapUtils.isEmpty(params)) {
            return;
        }
        List<T> ts = get(group);
        if (null == ts) {
            return;
        }
        for (T t : ts) {
            params.forEach((name, value) -> {
                if (value == null) {
                    // 显式 null：同时清空 CONFIG 标记，允许后续重新 set
                    CONFIG.remove(PREFIX + group + name);
                }
                FieldStation.of(t).setIgnoreNameValue(name, value);
            });
            if (t instanceof Upgrade<?>) {
                ((Upgrade) t).upgrade(t);
            }
        }
    }

    /**
     * 通用设置：兼容旧版调用，直接存储原始值
     * @param key 键，不允许为 null
     * @param value 值，不允许为 null
     */
    public synchronized void set(String key, Object value) {
        CONFIG.put(PREFIX + key, value == null ? CommonConstant.SYMBOL_EMPTY : value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    /**
     * 设置
     * @param group 分组，不允许为 null
     * @param name 名称，不允许为 null
     * @param value 值，不允许为 null
     */
    public synchronized <T> void set(String group, String name, Object value) {
        List<T> ts = get(group);
        if (null == ts) {
            return;
        }
        for (T t : ts) {
            if (value == null) {
                CONFIG.remove(PREFIX + group + name);
            }
            FieldStation.of(t).setIgnoreNameValue(name, value);
            if (t instanceof Upgrade<?>) {
                ((Upgrade) t).upgrade(t);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    /**
     * 设置
     * @param group 分组，不允许为 null
     * @param type 类型，不允许为 null
     * @param name 名称，不允许为 null
     * @param value 值，不允许为 null
     */
    public synchronized <T> void set(String group, Class<T> type, String name, Object value) {
        T t = get(group, type);
        if (null == t) {
            return;
        }
        FieldStation.of(t).setIgnoreNameValue(name, value);
        if (t instanceof Upgrade<?>) {
            ((Upgrade) t).upgrade(t);
        }
    }

    /**
     * 设置And发布
     * @param group 分组，不允许为 null
     * @param name 名称，不允许为 null
     * @param value 值，不允许为 null
     * @param bean 方法入参 bean
     */
    public synchronized <T> void setAndPublish(String group, String name, Object value, T bean) {
        set(group, name, value);
    }
}
