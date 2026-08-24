package com.chua.parquet.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parquet 列式存储引擎实现（真实文件读写）。
 * <p>
 * 数据以 {@code <baseDir>/<table>.parquet} 真实落盘（Avro schema 按实体字段推断）；
 * 查询读取真实文件后进行条件过滤，UPDATE/DELETE 以"读-改-写回文件"实现并返回真实行数。
 * 通过 SPI 注册为 {@code "parquet"}，使用前需 {@code addDataSource(name, baseDir)} 指定目录。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("parquet")
public class ParquetEngine extends AbstractEngine {

    /**
     * 实体字段映射缓存：类 -> (snake_case 列名 -> Field)
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 支持的简单类型集合
     */
    private static final List<Class<?>> SUPPORTED = Arrays.asList(
            String.class, boolean.class, Boolean.class,
            int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class);

    /**
     * 基础目录；addDataSource 后赋值
     */
    private volatile String baseDir;

    /**
     * 注册数据源：此处传入 Parquet 文件的基础目录。
     *
     * @param name       数据源名称
     * @param dataSource 目录路径字符串或 File
     * @param <T>        底层类型
     * @return this
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        Object source = dataSource.getSource();
        if (source instanceof String s) {
            this.baseDir = s;
        } else if (source instanceof File f) {
            this.baseDir = f.getAbsolutePath();
        } else {
            throw new IllegalArgumentException("ParquetEngine 仅支持目录路径(String)或 File");
        }
        File dir = new File(baseDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 Parquet 目录: " + baseDir);
        }
        return super.addDataSource(name, dataSource);
    }

    /**
     * 便捷注册数据源目录。
     *
     * @param name   数据源名称
     * @param srcDir Parquet 文件基础目录
     * @return this
     */
    public ParquetEngine addDataSource(String name, String srcDir) {
        baseDir = srcDir;
        File dir = new File(srcDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 Parquet 目录: " + srcDir);
        }
        return this;
    }

    // ==================== 写入（真实落盘） ====================

    /**
     * 将实体列表写入 Parquet 文件（覆盖写），即真实持久化。
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        try {
            writeAll(name, entityClassOf(data), data);
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 写入失败: " + name, e);
        }
        return this;
    }

    /**
     * 推断列表元素类型。
     */
    @SuppressWarnings("unchecked")
    private static <T> Class<T> entityClassOf(List<T> data) {
        for (T t : data) {
            if (t != null) {
                return (Class<T>) t.getClass();
            }
        }
        throw new IllegalArgumentException("空列表无法推断实体类型");
    }

    /**
     * 读取真实文件 + 内存条件过滤（数据来源为磁盘 Parquet 文件）。
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass) {
        List<GenericRecord> records = readAll(getTableName(entityClass));
        List<T> out = new ArrayList<>(records.size());
        Map<String, Field> fields = fieldsOf(entityClass);
        for (GenericRecord rec : records) {
            T inst;
            try {
                inst = entityClass.getDeclaredConstructor().newInstance();
                for (int i = 0; i < rec.getSchema().getFields().size(); i++) {
                    Schema.Field sf = rec.getSchema().getFields().get(i);
                    Field f = fields.get(sf.name());
                    Object v = rec.get(sf.name());
                    if (f == null || v == null) {
                        continue;
                    }
                    f.setAccessible(true);
                    f.set(inst, convert(v, f.getType()));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Parquet 行映射失败: " + entityClass.getName(), e);
            }
            out.add(inst);
        }
        if (where == null || where.trim().isEmpty()) {
            return out;
        }
        MemoryWhereParser parser = new MemoryWhereParser();
        var predicate = parser.parse(where.trim(),
                params == null ? List.of() : Arrays.asList(params));
        return out.stream().filter(predicate).toList();
    }

    // ==================== 更新/删除（读-改-写回真实文件） ====================

    /**
     * 读出真实文件数据、按条件更新字段后整体写回，返回真实更新行数。
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        try {
            Class<T> ec = sql.entityClass();
            String normWhere = normalizeColumns(sql.whereClause(), ec);
            List<T> whole = doRead(ec);
            if (whole.isEmpty()) {
                return 0;
            }
            // 解析 SET 子句："col1 = ?, col2 = ?"（SET 参数在前，WHERE 参数在后）
            Map<String, Field> fields = fieldsOf(ec);
            String setClause = sql.setClause();
            List<Object> params = sql.params();
            int setCount = setClause == null || setClause.isEmpty()
                    ? 0 : setClause.split(", ").length;
            Map<Field, Object> sets = new java.util.LinkedHashMap<>();
            for (int i = 0; i < setCount && i < params.size(); i++) {
                String part = setClause.split(", ")[i];
                int eq = part.indexOf(" = ");
                String col = eq > 0 ? part.substring(0, eq).trim() : part.trim();
                Field f = fields.get(col);
                if (f != null) {
                    f.setAccessible(true);
                    sets.put(f, convert(params.get(i), f.getType()));
                }
            }
            List<Object> whereParams = params.size() > setCount
                    ? params.subList(setCount, params.size()) : List.of();
            var hit = new MemoryWhereParser().parse(normWhere, new ArrayList<>(whereParams));
            int affected = 0;
            for (T row : whole) {
                if (!hit.test(row)) {
                    continue;
                }
                affected++;
                for (Map.Entry<Field, Object> e : sets.entrySet()) {
                    e.getKey().set(row, e.getValue());
                }
            }
            writeAll(getTableName(ec), ec, whole);
            return affected;
        } catch (IOException | IllegalAccessException e) {
            throw new IllegalStateException("Parquet 更新写回失败", e);
        }
    }

    /**
     * 读出真实文件数据、删除命中行后写回，返回真实删除行数。
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        try {
            Class<T> ec = sql.entityClass();
            List<T> whole = doRead(ec);
            if (whole.isEmpty()) {
                return 0;
            }
            var hit = new MemoryWhereParser().parse(
                    normalizeColumns(sql.whereClause(), ec),
                    sql.params() == null ? List.of() : sql.params());
            List<T> keep = new ArrayList<>();
            int removed = 0;
            for (T row : whole) {
                if (hit.test(row)) {
                    removed++;
                } else {
                    keep.add(row);
                }
            }
            writeAll(getTableName(ec), ec, keep);
            return removed;
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 删除写回失败", e);
        }
    }

    /**
     * 归一化表达式中的列名（驼峰/去下划线变体 -> 真实列名）。
     */
    private String normalizeColumns(String expr, Class<?> entityClass) {
        if (expr == null || expr.isEmpty()) {
            return expr;
        }
        Map<String, Field> fields = fieldsOf(entityClass);
        List<String[]> rules = new ArrayList<>();
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            String canonical = e.getKey();
            addRule(rules, e.getValue().getName(), canonical);
            addRule(rules, e.getValue().getName().toLowerCase(), canonical);
            addRule(rules, canonical.replace("_", ""), canonical);
        }
        rules.sort((x, y) -> y[0].length() - x[0].length());
        String out = expr;
        for (String[] rule : rules) {
            out = out.replaceAll("(?<![\\w])" + java.util.regex.Pattern.quote(rule[0]) + "(?![\\w])",
                    java.util.regex.Matcher.quoteReplacement(rule[1]));
        }
        return out;
    }

    /**
     * 追加别名规则（去重、忽略同名词）。
     */
    private void addRule(List<String[]> rules, String variant, String canonical) {
        if (variant != null && !variant.isEmpty() && !variant.equals(canonical)
                && rules.stream().noneMatch(r -> r[0].equals(variant))) {
            rules.add(new String[]{variant, canonical});
        }
    }

    /**
     * 读取整表实体列表（文件不存在时返回空表）。
     */
    private <T> List<T> doRead(Class<T> entityClass) {
        return executeNewQuery("", new Object[0], entityClass);
    }

    // ==================== Parquet IO ====================

    /**
     * 覆盖写入整表数据到 Parquet 文件。
     */
    private <T> void writeAll(String table, Class<T> entityClass, List<T> data) throws IOException {
        Schema schema = schemaFor(entityClass);
        Path path = filePath(table);
        File tmp = new File(new File(baseDir), table + ".writing");
        ParquetWriter<GenericRecord> writer;
        try {
            writer = ugi().doAs((java.security.PrivilegedExceptionAction<ParquetWriter<GenericRecord>>)
                    () -> AvroParquetWriter
                            .<GenericRecord>builder(new Path(tmp.getAbsolutePath()))
                            .withSchema(schema)
                            .withWriteMode(org.apache.parquet.hadoop.ParquetFileWriter.Mode.OVERWRITE)
                            .build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("构建 ParquetWriter 被中断", e);
        }
        try {
            Map<String, Field> fields = fieldsOf(entityClass);
            for (T item : data) {
                GenericRecord rec = new GenericData.Record(schema);
                for (Schema.Field sf : schema.getFields()) {
                    Field f = fields.get(sf.name());
                    f.setAccessible(true);
                    Object v = f.get(item);
                    rec.put(sf.name(), v instanceof String s ? s : v);
                }
                writer.write(rec);
            }
        } catch (IllegalAccessException e) {
            throw new IOException("字段读取失败", e);
        } finally {
            writer.close();
        }
        File target = new File(baseDir, table + ".parquet");
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖旧文件: " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("临时文件重命名失败: " + tmp);
        }
    }

    /**
     * 读取整表 Parquet 文件。
     */
    private List<GenericRecord> readAll(String table) {
        File file = new File(baseDir, table + ".parquet");
        List<GenericRecord> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        try {
            ParquetReader<GenericRecord> reader = ugi().doAs(
                    (java.security.PrivilegedExceptionAction<ParquetReader<GenericRecord>>)
                            () -> AvroParquetReader
                                    .<GenericRecord>builder(new Path(file.getAbsolutePath())).build());
            GenericRecord rec;
            while ((rec = reader.read()) != null) {
                out.add(rec);
            }
            reader.close();
        } catch (Exception e) {
            throw new IllegalStateException("Parquet 读取失败: " + file, e);
        }
        return out;
    }

    /**
     * Hadoop UGI：JDK 移除 SecurityManager 后 getSubject 不再可用，
     * 预先创建远程用户并以 doAs 执行文件系统操作。
     */
    private static UserGroupInformation ugi() {
        return UserGroupInformation.createRemoteUser(
                System.getProperty("user.name", "parquet"));
    }

    /**
     * 表对应文件路径。
     */
    private Path filePath(String table) {
        return new Path(new File(baseDir, table + ".parquet").getAbsolutePath());
    }

    /**
     * 按实体受支持字段构建 Avro Schema（忽略不支持类型字段）。
     */
    private Schema schemaFor(Class<?> clazz) {
        List<Schema.Field> fields = new ArrayList<>();
        for (Map.Entry<String, Field> e : fieldsOf(clazz).entrySet()) {
            Field f = e.getValue();
            if (!SUPPORTED.contains(f.getType())) {
                continue;
            }
            Schema.Type t;
            if (f.getType() == String.class) {
                t = Schema.Type.STRING;
            } else if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                t = Schema.Type.BOOLEAN;
            } else if (f.getType() == float.class || f.getType() == Float.class) {
                t = Schema.Type.FLOAT;
            } else if (f.getType() == double.class || f.getType() == Double.class) {
                t = Schema.Type.DOUBLE;
            } else {
                t = Schema.Type.LONG;
            }
            fields.add(new Schema.Field(e.getKey(), Schema.create(t), null, (Object) null));
        }
        if (fields.isEmpty()) {
            throw new IllegalStateException("实体无可映射列: " + clazz.getName());
        }
        Schema schema = Schema.createRecord(clazz.getSimpleName(), null, null, false);
        schema.setFields(fields);
        return schema;
    }

    /**
     * 实体字段 -> snake_case 列名映射缓存。
     */
    private static Map<String, Field> fieldsOf(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new ConcurrentHashMap<>();
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    map.putIfAbsent(camelToSnake(f.getName()), f);
                }
            }
            return map;
        });
    }

    /**
     * 驼峰转 snake_case。
     */
    private static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                sb.append('_').append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Avro 取值按目标类型转换（Utf8/数值/布尔）。
     */
    private static Object convert(Object v, Class<?> type) {
        if (type == String.class) {
            return v.toString();
        }
        long asLong = Long.MIN_VALUE;
        double asDouble = Double.NaN;
        if (v instanceof Number num) {
            asLong = num.longValue();
            asDouble = num.doubleValue();
        } else if (v instanceof Boolean b) {
            return b;
        } else {
            BigDecimal bd = new BigDecimal(v.toString());
            asLong = bd.longValue();
            asDouble = bd.doubleValue();
        }
        if (type == int.class || type == Integer.class) {
            return (int) asLong;
        }
        if (type == long.class || type == Long.class) {
            return asLong;
        }
        if (type == double.class || type == Double.class) {
            return asDouble;
        }
        if (type == float.class || type == Float.class) {
            return (float) asDouble;
        }
        return v.toString();
    }
}
