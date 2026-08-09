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

    private static final GlobalSettingFactory INSTANCE = new GlobalSettingFactory();

    public static volatile String PREFIX = "";

    static final Map<String, Object> CONFIG = new ConcurrentHashMap<>();
    static final Map<String, List<Object>> GROUP = new ConcurrentHashMap<>();
    static final Map<String, Boolean> GROUP_ENABLED = new ConcurrentHashMap<>();

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

    public <T> List<T> get(String group) {
        return (List<T>) GROUP.get(PREFIX + group);
    }

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

    public <T> void register(String group, T t) {
        register(group, t, true);
    }

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

    public static GlobalSettingFactory getInstance() {
        return INSTANCE;
    }

    public Map<String, List<Object>> getAllGroup() {
        return Collections.unmodifiableMap(GROUP);
    }

    public Map<String, Boolean> getAllGroupEnabled() {
        return Collections.unmodifiableMap(GROUP_ENABLED);
    }

    public synchronized <T> void setIfNoChange(String group, String name, Object value) {
        if (CONFIG.containsKey(PREFIX + group + name)) {
            return;
        }
        set(group, name, value);
        CONFIG.put(PREFIX + group + name, CommonConstant.SYMBOL_EMPTY);
    }

    public synchronized <T> void set(String group, Map<String, Object> params) {
        if (MapUtils.isEmpty(params)) {
            return;
        }
        List<T> ts = get(group);
        if (null == ts) {
            return;
        }
        for (T t : ts) {
            params.forEach((name, value) -> FieldStation.of(t).setIgnoreNameValue(name, value));
            if (t instanceof Upgrade<?>) {
                ((Upgrade<?>) t).upgrade(t);
            }
        }
    }

    public synchronized <T> void set(String group, String name, Object value) {
        List<T> ts = get(group);
        if (null == ts) {
            return;
        }
        for (T t : ts) {
            FieldStation.of(t).setIgnoreNameValue(name, value);
            if (t instanceof Upgrade<?>) {
                ((Upgrade<?>) t).upgrade(t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> void set(String group, Class<T> type, String name, Object value) {
        T t = get(group, type);
        if (null == t) {
            return;
        }
        FieldStation.of(t).setIgnoreNameValue(name, value);
        if (t instanceof Upgrade<?>) {
            ((Upgrade<?>) t).upgrade(t);
        }
    }

    public synchronized <T> void setAndPublish(String group, String name, Object value, T bean) {
        set(group, name, value);
    }
}
