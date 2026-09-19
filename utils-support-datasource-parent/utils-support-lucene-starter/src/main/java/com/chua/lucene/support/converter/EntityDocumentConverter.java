package com.chua.lucene.support.converter;

import com.chua.lucene.support.engine.LuceneFields;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.document.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 实体对象与 Lucene 文档 之间的转换器。
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
     * 将实体对象转换为 Lucene 文档。
     *
     * @param entity 实体对象
     * @return Lucene 文档
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
                    addFieldToDocument(doc, fieldName, value);
                }
            }
            cls = cls.getSuperclass();
        }
        return doc;
    }

    /**
     * 将 Lucene 文档 转换为实体对象。
     *
     * @param doc        Lucene 文档
     * @param entityClass 实体类
     * @param <T>        实体类型
     * @return 实体对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T toEntity(Document doc, Class<T> entityClass) {
        if (doc == null || entityClass == null) {
            return null;
        }
 // 使用 ReflectUtils 无参构造反射实例化，避免 方法处理 对部分类的访问限制
        T entity = ReflectUtils.instantiate(entityClass);
        if (entity == null) {
            return null;
        }
        for (Field field : entityClass.getDeclaredFields()) {
            String fieldName = field.getName();
            String valueStr = extractStoredValue(doc, fieldName);
            if (valueStr != null) {
                setFieldValue(entity, fieldName, valueStr, field.getType());
            }
        }
        return entity;
    }

    /**
     * 从 文档 中提取指定字段的首个已存储值。
     * <p>跳过仅索引不存储的 Point 字段（其 stringValue 为 空 且二进制值是编码后的点值），
     * 取第一个 存储() 且值非空的字段表示。</p>
     *
     * @param doc       Lucene 文档
     * @param fieldName 字段名
     * @return 字段的字符串表示，不存在时返回 null
     */
    private static String extractStoredValue(Document doc, String fieldName) {
        for (IndexableField f : doc.getFields(fieldName)) {
            if (!f.fieldType().stored()) {
                continue;
            }
            String s = f.stringValue();
            if (s != null) {
                return s;
            }
            Number n = f.numericValue();
            if (n != null) {
                return n.toString();
            }
            org.apache.lucene.util.BytesRef b = f.binaryValue();
            if (b != null) {
                return b.utf8ToString();
            }
        }
        return null;
    }

    /**
     * 向 文档 添加字段。
     * <p>LuceneEngine 的更新/同步写入路径同样复用此方法，保证索引形态一致。</p>
     *
     * @param doc       Lucene 文档
     * @param fieldName 字段名
     * @param value     字段值，null 直接跳过
     */
    public static void addFieldToDocument(Document doc, String fieldName, Object value) {
        if (value == null) {
            return;
        }
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
            doc.add(new StringField(fieldName, b ? "true" : "false",
                    org.apache.lucene.document.Field.Store.YES));
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
            doc.add(new StringField(fieldName, lt.toString(),
                    org.apache.lucene.document.Field.Store.YES));
        } else {
            // 其他类型统一转为字符串存储
            doc.add(new StringField(fieldName, String.valueOf(value), org.apache.lucene.document.Field.Store.YES));
        }
    }

    /**
     * 设置字段值。
     *
     * @param entity   实体对象
     * @param fieldName 字段名
     * @param valueStr 字符串值
     * @param fieldType 字段类型
     */
    private static void setFieldValue(Object entity, String fieldName, String valueStr, Class<?> fieldType) {
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(valueStr, fieldType);
        if (converted != null) {
            ReflectUtils.setField(entity, fieldName, converted);
        }
    }
}
