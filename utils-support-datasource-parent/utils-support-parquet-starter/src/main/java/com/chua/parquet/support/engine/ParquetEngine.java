package com.chua.parquet.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.reflection.ReflectUtils;
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
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.SeekableInputStream;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
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
 * 查询读取真实文件后条件过滤；UPDATE/DELETE 以"读-改-写回文件"实现并返回真实行数。
 * 通过 parquet 的 {@link OutputFile}/{@link InputFile} 抽象直连本地文件，
 * 不引入任何 Hadoop 运行时依赖。SPI 键 {@code "parquet"}；
 * 使用前需 {@code addDataSource(name, baseDir)} 指定目录。
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
     * 注册数据源：传入 Parquet 文件基础目录。
     *
     * @param name       数据源名称
     * @param dataSource 目录路径字符串或 File
     * @param <T>        底层类型
     * @return this
     */
    @Override
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        Object source = dataSource.getSource();
        if (source instanceof String s) {
            setBaseDir(s);
        } else if (source instanceof File f) {
            setBaseDir(f.getAbsolutePath());
        } else {
            throw new IllegalArgumentException("ParquetEngine 仅支持目录路径(String)或 File");
        }
        return this;
    }

    /**
     * 便捷注册数据源目录。
     *
     * @param name   数据源名称
     * @param srcDir Parquet 文件基础目录
     * @return this
     */
    public ParquetEngine addDataSource(String name, String srcDir) {
        setBaseDir(srcDir);
        return this;
    }

    /**
     * 初始化并校验基础目录。
     */
    private void setBaseDir(String dirPath) {
        baseDir = dirPath;
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 Parquet 目录: " + dirPath);
        }
    }

    // ==================== 写入（真实落盘） ====================

    /**
     * 将实体列表写入 Parquet 文件（覆盖写），即真实持久化。
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        try {
            Class<?> clazz = null;
            for (Object o : data) {
                if (o != null) {
                    clazz = o.getClass();
                    break;
                }
            }
            if (clazz == null) {
                throw new IllegalArgumentException("空列表无法推断实体类型");
            }
            writeAll(name, clazz, data);
            super.store(name, data);  /* 同时写入内存缓存确保 Lambda 查询可见 */
            return this;
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 写入失败: " + name, e);
        }
    }

    // ==================== 查询（真实文件 + 条件过滤） ====================

    /**
     * 读取真实文件后按 WHERE 过滤（排序/分页由父类完成）。
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        List<GenericRecord> records = readAll(getTableName(entityClass));
        List<T> out = new ArrayList<>(records.size());
        Map<String, Field> fields = fieldsOf(entityClass);
        for (GenericRecord rec : records) {
            try {
                T inst = ReflectUtils.instantiate(entityClass);
                for (Schema.Field sf : rec.getSchema().getFields()) {
                    Field f = fields.get(sf.name());
                    Object v = rec.get(sf.name());
                    if (f == null || v == null) {
                        continue;
                    }
                    f.setAccessible(true);
                    f.set(inst, convert(v, f.getType()));
                }
                out.add(inst);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Parquet 行映射失败: " + entityClass.getName(), e);
            }
        }
        if (where == null || where.trim().isEmpty()) {
            return out;
        }
        var predicate = new MemoryWhereParser().parse(where.trim(),
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
            List<T> whole = doRead(ec);
            if (whole.isEmpty()) {
                return 0;
            }
            Map<String, Field> fields = fieldsOf(ec);
            String setClause = sql.setClause();
            List<Object> params = sql.params() == null ? List.of() : sql.params();
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
            var hit = new MemoryWhereParser().parse(
                    normalizeColumns(sql.whereClause(), ec), new ArrayList<>(whereParams));
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
     * 读取整表实体列表（文件不存在时返回空表）。
     */
    private <T> List<T> doRead(Class<T> entityClass) {
        return executeNewQuery("", new Object[0], entityClass, 0, 0);
    }
    // ==================== Parquet 本地 IO ====================

    /**
     * 覆盖写入整表数据到 Parquet 文件（先写临时文件再原子改名）。
     */
    private <T> void writeAll(String table, Class<?> entityClass, List<?> data) throws IOException {
        Schema schema = schemaFor(entityClass);
        File target = new File(baseDir, table + ".parquet");
        File tmp = new File(baseDir, table + ".writing");
        ParquetWriter<GenericRecord> writer = AvroParquetWriter
                .<GenericRecord>builder(localOutput(tmp))
                .withSchema(schema)
                .build();
        Map<String, Field> fields = fieldsOf(entityClass);
        try {
            for (Object item : data) {
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
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖旧文件: " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("临时文件重命名失败: " + tmp);
        }
    }

    /**
     * 读取整表 Parquet 文件（不存在返回空表）。
     */
    private List<GenericRecord> readAll(String table) {
        File file = new File(baseDir, table + ".parquet");
        List<GenericRecord> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        try (ParquetReader<GenericRecord> reader =
                     AvroParquetReader.<GenericRecord>builder(localInput(file)).build()) {
            GenericRecord rec;
            while ((rec = reader.read()) != null) {
                out.add(rec);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 读取失败: " + file, e);
        }
        return out;
    }

    /**
     * 本地文件 OutputFile 实现。
     */
    private static OutputFile localOutput(File file) {
        return new OutputFile() {
            @Override
            public PositionOutputStream create(long blockSize) throws IOException {
                return stream(file, false);
            }

            @Override
            public PositionOutputStream createOrOverwrite(long blockSize) throws IOException {
                return stream(file, true);
            }

            @Override
            public boolean supportsBlockSize() {
                return false;
            }

            @Override
            public long defaultBlockSize() {
                return 0;
            }
        };
    }

    /**
     * 本地文件输入流包装。
     */
    private static PositionOutputStream stream(File file, boolean overwrite) throws IOException {
        if (file.exists() && !overwrite) {
            throw new IOException("文件已存在: " + file);
        }
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        return new PositionOutputStream() {
            @Override
            public long getPos() throws IOException {
                return raf.getFilePointer();
            }

            @Override
            public void write(int b) throws IOException {
                raf.write(b);
            }

            @Override
            public void write(byte[] b) throws IOException {
                raf.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                raf.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                raf.getFD().sync();
            }

            @Override
            public void close() throws IOException {
                raf.close();
            }
        };
    }

    /**
     * 本地文件 InputFile 实现。
     */
    private static InputFile localInput(File file) {
        return new InputFile() {
            @Override
            public long getLength() throws IOException {
                return file.length();
            }

            @Override
            public SeekableInputStream newStream() throws IOException {
                return new SeekableInputStream() {
                    private final RandomAccessFile raf = new RandomAccessFile(file, "r");

                    @Override
      public long getPos() throws IOException {
                        return raf.getFilePointer();
                    }

                    @Override
                    public void seek(long pos) throws IOException {
                        raf.seek(pos);
                    }

                    @Override
                    public void readFully(byte[] b) throws IOException {
                        raf.readFully(b);
                    }

                    @Override
                    public void readFully(byte[] b, int off, int len) throws IOException {
                        raf.readFully(b, off, len);
                    }

                    @Override
                    public int read(java.nio.ByteBuffer bb) throws IOException {
                        byte[] buf = new byte[bb.remaining()];
                        int n = raf.read(buf);
                        if (n > 0) {
                            bb.put(buf, 0, n);
                        }
                        return n;
                    }

                    @Override
                    public void readFully(java.nio.ByteBuffer bb) throws IOException {
                        byte[] buf = new byte[bb.remaining()];
                        raf.readFully(buf);
                        bb.put(buf);
                    }

                    @Override
                    public int read() throws IOException {
                        return raf.read();
                    }

                    @Override
                    public int read(byte[] b) throws IOException {
                        return raf.read(b);
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        return raf.read(b, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        raf.close();
                    }
                };
            }
        };
    }

    // ==================== 元信息与工具 ====================

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
     * Avro 取值按目标类型转换（Utf8/数值/布尔）。
     */
    private static Object convert(Object v, Class<?> type) {
        if (type == String.class) {
            return v.toString();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        long asLong;
        double asDouble;
        if (v instanceof Number num) {
            asLong = num.longValue();
            asDouble = num.doubleValue();
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
