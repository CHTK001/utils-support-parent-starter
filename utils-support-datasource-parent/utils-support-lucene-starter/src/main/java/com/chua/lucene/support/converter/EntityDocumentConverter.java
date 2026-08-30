package com.chua.lucene.support.converter;

import com.chua.lucene.support.engine.LuceneFields;
import com.chua.common.support.reflection.ReflectUtils;
import org.apache.lucene.document.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 实体对象与 Lucene Document 之间的转换器。
 *
 * <p>负责将 Java 实体对象序列化为 Lucene {@link Document}，
 * 以及从 {@link Document} 反序列化为实体对象。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class EntityDocumentConverter {

    /**
     * 私有构造。
     */
    private EntityDocumentConverter() {
    }

    /**
     * 反射获取对象字段值。
     *
     * @param entity   实体对象
     * @param fieldName 字段名
     * @return 字段值
     */
    private static Object getFieldValue(Object entity, String fieldName) {
        if (entity == null || fieldName == null) {
            return null;
        }
        return ReflectUtils.getField(entity, fieldName);
    }

    /**
     * 将实体对象转换为 Lucene Document。
     *
     * @param entity 实体对象
     * @return Lucene Document
     */
    @SuppressWarnings("unchecked")
    public static Document toDocument(Object entity) {
        if (entity == null) {
            return new Document();
        }
        Document doc = new Document();
        Class<?> cls = entity.getClass();

        Object idValue = getFieldValue(entity, LuceneFields.ID);
        String idStr = idValue != null ? String.valueOf(idValue) : String.valueOf(System.identityHashCode(entity));
        doc.add(new StringField(LuceneFields.ID, idStr, org.apache.lucene.document.Field.Store.YES));

        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                String fieldName = field.getName();
                if (LuceneFields.ID.equals(fieldName)) {
                    continue;
                }
                Object value = ReflectUtils.getField(entity, fieldName);
                if (value != null) {
                    addField(doc, fieldName, value);
                }
            }
            cls = cls.getSuperclass();
        }
        return doc;
    }

    /**
     * 将 Lucene Document 转换为实体对象。
     *
     * @param doc        Lucene Document
     * @param entityClass 实体类
     * @param <T>        实体类型
     * @return 实体对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T toEntity(Document doc, Class<T> entityClass) {
        if (doc == null || entityClass == null) {
            return null;
        }
        try {
            // 使用无参构造反射实例化，避免 MethodHandle 对部分类的访问限制
            java.lang.reflect.Constructor<T> constructor = entityClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            T entity = constructor.newInstance();
            for (Field field : entityClass.getDeclaredFields()) {
                field.setAccessible(true);
                String fieldName = field.getName();
                String valueStr = doc.getField(fieldName) != null ? doc.getField(fieldName).stringValue() : null;
                if (valueStr != null) {
                    setFieldValue(entity, field, valueStr, field.getType());
                }
            }
            return entity;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 向 Document 添加字段。
     *
     * @param doc       Lucene Document
     * @param fieldName 字段名
     * @param value     字段值
     */
    private static void addField(Document doc, String fieldName, Object value) {
        if (value instanceof String str) {
            doc.add(new StringField(fieldName, str, org.apache.lucene.document.Field.Store.YES));
            doc.add(new TextField(fieldName + "_text", str, org.apache.lucene.document.Field.Store.NO));
        } else if (value instanceof Long l) {
            doc.add(new LongPoint(fieldName, l));
            doc.add(new StoredField(fieldName, l));
        } else if (value instanceof Integer i) {
            doc.add(new IntPoint(fieldName, i));
            doc.add(new StoredField(fieldName, i));
        } else if (value instanceof Short s) {
            doc.add(new IntPoint(fieldName, s));
            doc.add(new StoredField(fieldName, s));
        } else if (value instanceof Byte b) {
            doc.add(new IntPoint(fieldName, b));
            doc.add(new StoredField(fieldName, b));
        } else if (value instanceof Float f) {
            doc.add(new FloatPoint(fieldName, f));
            doc.add(new StoredField(fieldName, f));
        } else if (value instanceof Double d) {
            doc.add(new DoublePoint(fieldName, d));
            doc.add(new StoredField(fieldName, d));
        } else if (value instanceof BigDecimal bd) {
            doc.add(new DoublePoint(fieldName, bd.doubleValue()));
            doc.add(new StoredField(fieldName, bd.toString()));
        } else if (value instanceof Boolean b) {
            doc.add(new StoredField(fieldName, b ? "true" : "false"));
        } else if (value instanceof Date date) {
            doc.add(new LongPoint(fieldName, date.getTime()));
            doc.add(new StoredField(fieldName, String.valueOf(date.getTime())));
        } else if (value instanceof LocalDateTime ldt) {
            doc.add(new LongPoint(fieldName, ldt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()));
            doc.add(new StoredField(fieldName, ldt.toString()));
        } else if (value instanceof LocalDate ld) {
            doc.add(new LongPoint(fieldName, ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()));
            doc.add(new StoredField(fieldName, ld.toString()));
        } else if (value instanceof LocalTime lt) {
            doc.add(new StoredField(fieldName, lt.toString()));
        } else {
            // 其他类型统一转为字符串存储
            doc.add(new StringField(fieldName, String.valueOf(value), org.apache.lucene.document.Field.Store.YES));
        }
    }

    /**
     * 设置字段值。
     *
     * @param entity   实体对象
     * @param field    字段
     * @param valueStr 字符串值
     * @param fieldType 字段类型
     */
    private static void setFieldValue(Object entity, Field field, String valueStr, Class<?> fieldType) {
        try {
            if (fieldType == String.class) {
                field.set(entity, valueStr);
            } else if (fieldType == Long.class || fieldType == long.class) {
                field.set(entity, Long.parseLong(valueStr));
            } else if (fieldType == Integer.class || fieldType == int.class) {
                field.set(entity, Integer.parseInt(valueStr));
            } else if (fieldType == Short.class || fieldType == short.class) {
                field.set(entity, Short.parseShort(valueStr));
            } else if (fieldType == Byte.class || fieldType == byte.class) {
                field.set(entity, Byte.parseByte(valueStr));
            } else if (fieldType == Float.class || fieldType == float.class) {
                field.set(entity, Float.parseFloat(valueStr));
            } else if (fieldType == Double.class || fieldType == double.class) {
                field.set(entity, Double.parseDouble(valueStr));
            } else if (fieldType == Boolean.class || fieldType == boolean.class) {
                field.set(entity, Boolean.parseBoolean(valueStr));
            } else if (fieldType == Date.class) {
                field.set(entity, new Date(Long.parseLong(valueStr)));
            } else if (fieldType == LocalDateTime.class) {
                field.set(entity, LocalDateTime.parse(valueStr));
            } else if (fieldType == LocalDate.class) {
                field.set(entity, LocalDate.parse(valueStr));
            } else if (Number.class.isAssignableFrom(fieldType)) {
                field.set(entity, ((Number) field.get(entity)).getClass()
                        .getMethod("valueOf", String.class)
                        .invoke(null, valueStr));
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
