package com.chua.lucene.support.converter;

import com.chua.lucene.support.engine.LuceneFields;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.document.*;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
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

        // 自由文本检索的聚合字段：把所有可检索文本拼进 CONTENT。
        // LuceneEngine#search(String, Class) 把用户关键词解析到 CONTENT 字段上，
        // 若这里不写 CONTENT，该入口对任何关键词都恒返回 0 条（全文检索形同虚设）。
        StringBuilder content = new StringBuilder();

        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                String fieldName = field.getName();
                if (LuceneFields.ID.equals(fieldName)) {
                    continue;
                }
                Object value = ReflectUtils.getField(entity, fieldName);
                if (value != null) {
                    addFieldToDocument(doc, fieldName, value);
                    appendSearchableText(content, fieldName, value);
                }
            }
            cls = cls.getSuperclass();
        }
        if (!content.isEmpty()) {
            doc.add(new org.apache.lucene.document.TextField(
                    LuceneFields.CONTENT, content.toString(), org.apache.lucene.document.Field.Store.NO));
        }
        return doc;
    }

    /**
     * 把字段值追加到 聚合 文本。
     * <p>只收录字符型与可转字符串的标量：数值、布尔、日期对关键词检索无意义，
     * 全量塞入只会稀释分词权重。</p>
     *
     * @param content 聚合文本缓冲
     * @param fieldName 字段名
     * @param value 字段值
     */
    private static void appendSearchableText(StringBuilder content, String fieldName, Object value) {
        String text = null;
        if (value instanceof CharSequence cs) {
            text = cs.toString();
        } else if (value instanceof Character ch) {
            text = ch.toString();
        } else if (value instanceof Enum<?> en) {
            text = en.name();
        }
        if (text == null || text.isBlank()) {
            return;
        }
        if (!content.isEmpty()) {
            content.append(' ');
        }
        content.append(text);
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
        // record 没有无参构造，组件又是 final，必须走规范构造器回填；
        // 否则 ReflectUtils.instantiate 返回 null，检索命中的文档会被静默丢弃。
        if (entityClass.isRecord()) {
            return toRecordEntity(doc, entityClass);
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
     * 以 规范构造器 把文档还原为 record。
     * <p>缺失组件按类型零值填充（null / 0 / false），与 record 的默认语义一致。</p>
     *
     * @param doc Lucene 文档
     * @param recordClass record 类型
     * @param <T> record 类型
     * @return 还原出的 record 实例
     */
    @SuppressWarnings("unchecked")
    private static <T> T toRecordEntity(Document doc, Class<T> recordClass) {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            paramTypes[i] = rc.getType();
            String valueStr = extractStoredValue(doc, rc.getName());
            args[i] = valueStr == null
                    ? defaultValue(rc.getType())
                    : Converter.convertIfNecessary(valueStr, rc.getType());
        }
        try {
            java.lang.reflect.Constructor<T> ctor = recordClass.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("record 还原失败: " + recordClass.getName(), e);
        }
    }

    /**
     * 取类型的默认零值。
     *
     * @param type 类型
     * @return 引用类型为 null，基本类型为其零值
     */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
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
