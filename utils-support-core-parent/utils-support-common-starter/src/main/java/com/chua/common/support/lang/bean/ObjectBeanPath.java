package com.chua.common.support.lang.bean;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.utils.StringUtils;

import java.util.List;
import java.util.Map;

/**
* 基于 Java 对象反射的 BeanPath 实现，支持链式配置忽略大小写和命名风格转换。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("object")
public class ObjectBeanPath implements BeanPath {

    /**
    * 是否忽略大小写匹配属性名
     */
    private boolean ignoreCase;

    /**
    * 命名风格，用于自动转换属性名
     */
    private NamingStyle namingStyle = NamingStyle.RAW;

    @Override
    /** IgnoreCase */
    public ObjectBeanPath ignoreCase(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
        return this;
    }

    @Override
    /** NamingStyle */
    public ObjectBeanPath namingStyle(NamingStyle style) {
        this.namingStyle = style != null ? style : NamingStyle.RAW;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 获取Value */
    public <T> T getValue(Object source, String path) {
        if (source == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        return (T) resolve(source, parts, 0, parts.length);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 设置Value */
    public void setValue(Object source, String path, Object value) {
        if (source == null || path == null || path.isEmpty()) {
            return;
        }
        String[] parts = path.split("\\.");
        Object target = resolve(source, parts, 0, parts.length - 1);
        if (target == null) {
            return;
        }
        String last = parts[parts.length - 1];
        int idx = extractIndex(last);
        String rawProp = idx >= 0 ? last.substring(0, last.indexOf('[')) : last;
        String prop = normalizeProp(rawProp);

        if (target instanceof Map) {
            if (ignoreCase) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) target).entrySet()) {
                    if (e.getKey().equalsIgnoreCase(prop)) {
                        e.setValue(value);
                        return;
                    }
                }
            }
            ((Map<String, Object>) target).put(prop, value);
        } else if (idx >= 0) {
            List<Object> list = (List<Object>) ClassUtils.getFieldValue(prop, target);
            if (list != null && idx < list.size()) {
                list.set(idx, value);
            }
        } else {
            ClassUtils.setFieldValue(prop, value, target);
        }
    }

    @Override
    /** 是否存在 */
    public boolean exists(Object source, String path) {
        return getValue(source, path) != null;
    }

    @SuppressWarnings("unchecked")
    /** 解析 */
    private Object resolve(Object source, String[] parts, int start, int end) {
        Object current = source;
        for (int i = start; i < end; i++) {
            if (current == null) {
                return null;
            }
            String part = parts[i];
            int idx = extractIndex(part);
            String rawProp = idx >= 0 ? part.substring(0, part.indexOf('[')) : part;
            String prop = normalizeProp(rawProp);
            String finalProp = prop;

            if (current instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) current;
                if (ignoreCase) {
                    current = map.entrySet().stream()
                            .filter(e -> e.getKey().equalsIgnoreCase(finalProp))
                            .findFirst().map(Map.Entry::getValue).orElse(null);
                } else {
                    current = map.get(prop);
                }
            } else {
                current = resolveField(current, prop);
            }
            if (idx >= 0 && current instanceof List) {
                List<Object> list = (List<Object>) current;
                current = idx < list.size() ? list.get(idx) : null;
            }
        }
        return current;
    }

    /** 解析Field */
    private Object resolveField(Object bean, String prop) {
        try {
            return ClassUtils.getFieldValue(prop, bean);
        } catch (Exception e) {
            return null;
        }
    }

    /** NormalizeProp */
    private String normalizeProp(String prop) {
        if (StringUtils.isEmpty(prop) || namingStyle == NamingStyle.RAW) {
            return prop;
        }
        String camel = namingStyle.toCamel(prop);
        return ignoreCase ? camel.toLowerCase() : camel;
    }

    /** ExtractIndex */
    private static int extractIndex(String part) {
        int start = part.indexOf('[');
        if (start < 0) {
            return -1;
        }
        int end = part.indexOf(']', start);
        if (end < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(part.substring(start + 1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}