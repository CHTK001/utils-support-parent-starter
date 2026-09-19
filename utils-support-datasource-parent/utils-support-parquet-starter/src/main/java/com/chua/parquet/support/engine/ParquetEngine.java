package com.chua.parquet.support.engine;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.OutputFile;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parquet 列式存储引擎实现（真实文件读写）。
 * <p>
 * 数据以 {@code <baseDir>/<表名>.parquet} 真实落盘，Avro 模式按实体字段推断，
 * 所有列均为 {@code ["null", 基础类型]} 可空联合，允许实体字段为 null；
 * 查询读取真实文件后按 WHERE 过滤；更新/删除 以"读-改-写回文件"实现并返回真实行数。
 * 写入先落临时文件再原子改名，避免中途失败破坏既有数据。
 * </p>
 * <p>
 * 文件是本引擎的唯一事实来源：{@code store} 按实体推导出的表名落盘（与查询入口
 * {@link #executeNewQuery} 能拿到的定位键一致），因此父类内存映射只是只读镜像。
 * 本地 IO 直接复用 parquet 自带的 {@link LocalInputFile}/{@link LocalOutputFile}，
 * 不自行维护流生命周期，也不引入 Hadoop 运行时依赖。
 * </p>
 * <p>
 * 可映射的实体字段类型：{@code String}、枚举、{@code boolean}、{@code int}、
 * {@code long}、{@code short}、{@code byte}、{@code float}、{@code double} 及其包装类型、
 * {@code java.util.Date}；其余类型（集合、嵌套对象、{@code BigDecimal} 等）不进入文件模式。
 * </p>
 * <p>
 * SPI 键 {@code "parquet"}；使用前需 {@code addDataSource(name, baseDir)} 指定目录，
 * 未初始化目录或 {@code close()} 之后调用读写会直接抛出 {@link IllegalStateException}，
 * 重新调用 {@code addDataSource} 即重新开启引擎。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("parquet")
public class ParquetEngine extends AbstractEngine {

    /**
     * 合法表名：首字符为字母/下划线/汉字，其余为字母/数字/下划线/汉字，可含点号
     * （兼容 {@code @TableName("schema.table")} 写法），不得以点号结尾，长度上限 128。
     * 该白名单不含 {@code /}、{@code \}，因此 {@code ..}{@code /} 等路径穿越写法一律被拒绝。
     */
    private static final Pattern SAFE_TABLE =
            Pattern.compile("[A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_.\\u4e00-\\u9fa5]{0,127}(?<![.])");

    /**
     * Avro 名称非法字符（Avro 要求 {@code [A-Za-z_][A-Za-z0-9_]*}），
     * 实体字段名含 {@code $}、汉字等字符时需替换后再写入文件模式。
     */
    private static final Pattern ILLEGAL_NAME_CHAR = Pattern.compile("[^A-Za-z0-9_]");

    /**
     * Parquet 文件扩展名
     */
    private static final String PARQUET_EXT = ".parquet";

    /**
     * 写入期临时文件后缀，必须以 {@code .parquet} 结尾，避免被识别为数据文件
     */
    private static final String WRITING_EXT = ".writing.parquet";

    /**
     * 实体字段映射缓存：类 -> (列名 -> 字段)，列名按字段声明顺序排列
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 实体 Avro 模式缓存：类 -> 模式
     */
    private static final Map<Class<?>, Schema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    /**
     * 表级写锁，避免同一表并发覆盖写导致临时文件互相踩踏
     */
    private static final Map<String, ReentrantLock> TABLE_LOCKS = new ConcurrentHashMap<>();

    /**
     * 临时文件名序号
     */
    private static final AtomicLong TMP_SEQ = new AtomicLong();

    /**
     * 基础目录；添加数据源 后赋值
     */
    private volatile String baseDir;

    /**
     * 关闭标记：close 后禁止一切读写，重新注册数据源即复位
     */
    private volatile boolean closed;

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
        if (dataSource == null) {
            throw new IllegalArgumentException("Parquet 数据源不能为空");
        }
        Object source = dataSource.getSource();
        if (source instanceof String s) {
            setBaseDir(s);
        } else if (source instanceof File f) {
            setBaseDir(f.getAbsolutePath());
        } else if (source instanceof CharSequence cs) {
            setBaseDir(cs.toString());
        } else {
            throw new IllegalArgumentException("ParquetEngine 仅支持目录路径(String)或 File，实际为: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        // 交回父类登记，否则 getDataSource()/getDefaultDataSourceName()/close() 感知不到该数据源
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
        setBaseDir(srcDir);
        super.addDataSource(name, new ParquetDataSource(name, srcDir));
        return this;
    }

    /**
     * 初始化并校验基础目录；校验通过后才赋值，避免留下不可用的半初始化状态。
     * <p>重新注册目录同时视为重新开启引擎，复位 {@code close()} 设置的关闭标记。</p>
     *
     * @param dirPath dir路径
     */
    private void setBaseDir(String dirPath) {
        if (dirPath == null || dirPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Parquet 目录不能为空");
        }
        File dir = new File(dirPath.trim());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 Parquet 目录: " + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("Parquet 目录不是目录: " + dir.getAbsolutePath());
        }
        if (!dir.canWrite()) {
            throw new IllegalStateException("Parquet 目录不可写: " + dir.getAbsolutePath());
        }
        this.baseDir = dir.getAbsolutePath();
        this.closed = false;
    }

    /**
     * 关闭引擎：置关闭标记并释放父类持有的数据源与内存映射。
     */
    @Override
    public void close() {
        closed = true;
        super.close();
    }

    /**
     * 校验引擎处于可用状态并返回基础目录。
     *
     * @return 已校验的基础目录
     */
    private File requireBaseDir() {
        if (closed) {
            throw new IllegalStateException("ParquetEngine 已关闭，如需继续使用请重新调用 addDataSource");
        }
        String dir = baseDir;
        if (dir == null) {
            throw new IllegalStateException("Parquet 目录未初始化，请先调用 addDataSource(name, baseDir)");
        }
        File file = new File(dir);
        if (!file.isDirectory()) {
            throw new IllegalStateException("Parquet 目录不存在: " + dir);
        }
        return file;
    }

    /**
     * 解析表对应的数据文件；表名做白名单校验并确保目标路径不越出基础目录。
     *
     * @param table 表名
     * @return 数据文件（父目录已校验）
     */
    private File tableFile(String table) {
        File dir = requireBaseDir();
        if (table == null || table.isEmpty()) {
            throw new IllegalArgumentException("Parquet 表名不能为空");
        }
        if (!SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("非法 Parquet 表名: " + table);
        }
        File file = new File(dir, table + PARQUET_EXT);
        try {
            String root = dir.getCanonicalPath();
            if (!file.getCanonicalPath().startsWith(root + File.separator)) {
                throw new IllegalArgumentException("Parquet 表名越出基础目录: " + table);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法解析 Parquet 文件路径: " + file, e);
        }
        return file;
    }

    // ==================== 写入（真实落盘） ====================

    /**
     * 将实体列表写入 Parquet 文件（覆盖写），即真实持久化。
     * <p>
     * 文件按实体推导出的表名落盘：查询入口只能拿到实体类，若按入参 {@code name} 落盘，
     * 二者不一致会造成"写进去读不回"。{@code name} 仍按 {@link Engine} 契约登记到内存映射。
     * </p>
     *
     * @param name 数据存储名称
     * @param data 实体列表
     * @param <T>  实体类型
     * @return this
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        if (data == null) {
            throw new IllegalArgumentException("Parquet 写入数据不能为 null");
        }
        Class<?> clazz = null;
        for (Object o : data) {
            if (o != null) {
                clazz = o.getClass();
                break;
            }
        }
        if (clazz == null) {
            throw new IllegalArgumentException("空列表无法推断实体类型，无法写入 Parquet 文件");
        }
        String table = getTableName(clazz);
        try {
            writeAll(table, clazz, data);
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 写入失败: " + table, e);
        }
        super.store(name, data);  /* 同时写入内存缓存确保 Lambda 查询可见 */
        if (!table.equals(name)) {
            // 补齐父类按表名回退读取时使用的键，避免镜像与文件错位
            super.store(table, data);
        }
        return this;
    }

    // ==================== 查询（真实文件 + 条件过滤） ====================

    /**
     * 读取真实文件后按 WHERE 过滤（排序由父类完成；传入分页参数时在此截断）。
     *
     * @param where       WHERE 子句
     * @param params      参数值数组
     * @param entityClass 实体类类型
     * @param limit       返回行数上限，0 表示不限制
     * @param offset      偏移行数，0 表示不偏移
     * @param <T>         实体类型
     * @return 过滤后的实体列表
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass,
                                          int limit, int offset) {
        if (entityClass == null) {
            throw new IllegalArgumentException("Parquet 查询缺少实体类型");
        }
        List<GenericRecord> records = readAll(getTableName(entityClass));
        Map<String, Field> fields = fieldsOf(entityClass);
        List<T> out = new ArrayList<>(records.size());
        for (GenericRecord rec : records) {
            T inst = ReflectUtils.instantiate(entityClass);
            if (inst == null) {
                throw new IllegalStateException("Parquet 行映射失败，实体需具备无参构造: "
                        + entityClass.getName());
            }
            for (Schema.Field sf : rec.getSchema().getFields()) {
                Field f = fields.get(sf.name());
                if (f == null) {
                    // 文件中存在但实体已移除的列：跳过，保证模式演进后旧文件仍可读
                    continue;
                }
                Object v = rec.get(sf.name());
                if (v == null) {
                    continue;
                }
                assign(inst, f, v);
            }
            out.add(inst);
        }
        if (where == null || where.trim().isEmpty()) {
            return applyPaging(out, limit, offset);
        }
        var predicate = new MemoryWhereParser().parse(where.trim(),
                params == null ? List.of() : Arrays.asList(params));
        return applyPaging(out.stream().filter(predicate).toList(), limit, offset);
    }

    // ==================== 更新/删除（读-改-写回真实文件） ====================

    /**
     * 读出真实文件数据、按条件更新字段后整体写回，返回真实更新行数。
     *
     * @param sql 更新 SQL 信息
     * @param <T> 实体类型
     * @return 受影响行数
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        Class<T> ec = sql.entityClass();
        if (ec == null) {
            throw new IllegalArgumentException("Parquet 更新缺少实体类型");
        }
        String setClause = sql.setClause();
        if (setClause == null || setClause.trim().isEmpty()) {
            throw new IllegalStateException("Parquet 更新缺少 SET 列: " + ec.getSimpleName());
        }
        List<T> whole = doRead(ec);
        Map<String, Field> fields = fieldsOf(ec);
        List<Object> params = sql.params() == null ? new ArrayList<>() : new ArrayList<>(sql.params());
        // 仅 "?" 占位符消费参数，字面量赋值直接取值；按 SET 段数推算参数个数会把
        // 字面量算成占位符，导致 WHERE 参数整体错位、更新到错误的行
        Map<Field, Object> sets = new LinkedHashMap<>();
        int paramIdx = 0;
        for (String part : setClause.split(", ")) {
            int eq = part.indexOf(" = ");
            if (eq <= 0) {
                throw new IllegalArgumentException("无法解析 Parquet 更新赋值: " + part);
            }
            String col = part.substring(0, eq).trim();
            String rhs = part.substring(eq + 3).trim();
            Object value;
            if ("?".equals(rhs)) {
                if (paramIdx >= params.size()) {
                    throw new IllegalStateException("UPDATE SET 占位符数量超过参数个数: " + setClause);
                }
                value = params.get(paramIdx++);
            } else {
                value = parseLiteral(col, rhs);
            }
            Field f = resolveField(fields, col);
            if (f == null) {
                throw new IllegalArgumentException("Parquet 更新列在实体中不存在: "
                        + ec.getName() + "." + col);
            }
            sets.put(f, value);
        }
        List<Object> whereParams = paramIdx < params.size()
                ? new ArrayList<>(params.subList(paramIdx, params.size())) : new ArrayList<>();
        var hit = new MemoryWhereParser().parse(normalizeColumns(sql.whereClause(), ec), whereParams);
        int affected = 0;
        for (T row : whole) {
            if (!hit.test(row)) {
                continue;
            }
            affected++;
            for (Map.Entry<Field, Object> e : sets.entrySet()) {
                assign(row, e.getKey(), e.getValue());
            }
        }
        if (affected == 0) {
            // 未命中任何行时不回写，避免空操作产生一次全量重写
            return 0;
        }
        String table = getTableName(ec);
        try {
            writeAll(table, ec, whole);
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 更新写回失败: " + table, e);
        }
        syncMemory(table, whole);
        return affected;
    }

    /**
     * 读出真实文件数据、删除命中行后写回，返回真实删除行数。
     *
     * @param sql 删除 SQL 信息
     * @param <T> 实体类型
     * @return 受影响行数
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        Class<T> ec = sql.entityClass();
        if (ec == null) {
            throw new IllegalArgumentException("Parquet 删除缺少实体类型");
        }
        List<T> whole = doRead(ec);
        List<Object> params = sql.params() == null ? new ArrayList<>() : new ArrayList<>(sql.params());
        var hit = new MemoryWhereParser().parse(normalizeColumns(sql.whereClause(), ec), params);
        List<T> keep = new ArrayList<>(whole.size());
        int removed = 0;
        for (T row : whole) {
            if (hit.test(row)) {
                removed++;
            } else {
                keep.add(row);
            }
        }
        if (removed == 0) {
            return 0;
        }
        String table = getTableName(ec);
        try {
            writeAll(table, ec, keep);
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 删除写回失败: " + table, e);
        }
        syncMemory(table, keep);
        return removed;
    }

    /**
     * 读取整表实体列表（文件不存在时返回空表）。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 执行读取的结果
     */
    private <T> List<T> doRead(Class<T> entityClass) {
        return executeNewQuery("", new Object[0], entityClass, 0, 0);
    }

    // ==================== Parquet 本地 IO ====================

    /**
     * 覆盖写入整表数据到 Parquet 文件（先写临时文件再原子改名）。
     *
     * @param table       表名
     * @param entityClass 实体类
     * @param data        数据
     * @throws IOException 写入或改名失败
     */
    private void writeAll(String table, Class<?> entityClass, List<?> data) throws IOException {
        Schema schema = schemaFor(entityClass);
        Map<String, Field> fields = fieldsOf(entityClass);
        File target = tableFile(table);
        // 临时文件名带序号：并发覆盖同一表时不会互相截断对方的写入流
        File tmp = new File(target.getParentFile(),
                table + "-" + TMP_SEQ.incrementAndGet() + WRITING_EXT);
        ReentrantLock lock = TABLE_LOCKS.computeIfAbsent(target.getAbsolutePath(), k -> new ReentrantLock());
        lock.lock();
        boolean moved = false;
        try {
            OutputFile output = localOutput(tmp);
            try (ParquetWriter<GenericRecord> writer = AvroParquetWriter
                    .<GenericRecord>builder(output)
                    .withSchema(schema)
                    .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                    .build()) {
                int lineNo = 0;
                for (Object item : data) {
                    lineNo++;
                    if (item == null) {
                        throw new IllegalArgumentException("Parquet 第 " + lineNo + " 行数据为 null，无法落盘");
                    }
                    if (!entityClass.isInstance(item)) {
                        throw new IllegalArgumentException("Parquet 第 " + lineNo + " 行类型 "
                                + item.getClass().getName() + " 与实体类型 " + entityClass.getName() + " 不一致");
                    }
                    GenericRecord rec = new GenericData.Record(schema);
                    for (Schema.Field sf : schema.getFields()) {
                        Field f = fields.get(sf.name());
                        rec.put(sf.name(), toSchemaValue(ReflectUtils.getField(item, f.getName()), sf.schema()));
                    }
                    writer.write(rec);
                }
            }
            moveOver(tmp, target);
            moved = true;
        } finally {
            if (!moved) {
                deleteQuietly(tmp);
            }
            lock.unlock();
        }
    }

    /**
     * 读取整表 Parquet 文件（不存在返回空表）。
     *
     * @param table 表名
     * @return 文件中的记录列表
     */
    private List<GenericRecord> readAll(String table) {
        File file = tableFile(table);
        List<GenericRecord> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        if (file.length() == 0L) {
            throw new IllegalStateException("Parquet 文件为空（写入中断残留）: " + file);
        }
        InputFile input = localInput(file);
        try (ParquetReader<GenericRecord> reader =
                     AvroParquetReader.<GenericRecord>builder(input).build()) {
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
     * 本地文件 输出文件 实现，复用 parquet 自带的 {@link LocalOutputFile}，
     * 流的创建、缓冲与关闭全部交由 SDK 负责。
     *
     * @param file 文件
     * @return 本地输出的结果
     */
    private static OutputFile localOutput(File file) {
        return new LocalOutputFile(file.toPath());
    }

    /**
     * 本地文件 输入文件 实现，复用 parquet 自带的 {@link LocalInputFile}。
     *
     * @param file 文件
     * @return 本地输入的结果
     */
    private static InputFile localInput(File file) {
        return new LocalInputFile(file.toPath());
    }

    /**
     * 将临时文件原子替换到目标文件；不支持原子移动的平台退化为覆盖移动。
     *
     * @param tmp    临时文件
     * @param target 目标文件
     * @throws IOException 移动失败
     */
    private static void moveOver(File tmp, File target) throws IOException {
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 尽力删除文件，用于清理写入失败后的临时残留。
     *
     * @param file 待删除文件
     */
    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
            // 残留临时文件不影响数据正确性，保留即可，下次覆盖写会重新产生
        }
    }

    // ==================== 元信息与工具 ====================

    /**
     * 按实体受支持字段构建 Avro 模式（每列均为可空联合，忽略不可映射类型字段）。
     *
     * @param clazz 实体类
     * @return 模式for的结果
     */
    private Schema schemaFor(Class<?> clazz) {
        return SCHEMA_CACHE.computeIfAbsent(clazz, c -> {
            List<Schema.Field> fields = new ArrayList<>();
            for (Map.Entry<String, Field> e : fieldsOf(c).entrySet()) {
                Schema base = avroTypeOf(e.getValue().getType());
                if (base == null) {
                    // 集合、嵌套对象等无法用平铺列表达的字段不进入文件模式
                    continue;
                }
                fields.add(new Schema.Field(e.getKey(), nullable(base), null, (Object) null));
            }
            if (fields.isEmpty()) {
                throw new IllegalStateException("实体无可映射列: " + c.getName());
            }
            String recordName = avroName(c.getSimpleName());
            Schema schema = Schema.createRecord(recordName.isEmpty() ? "ParquetRecord" : recordName,
                    null, null, false);
            schema.setFields(fields);
            return schema;
        });
    }

    /**
     * 包装为 Avro 可空联合类型 {@code ["null", type]}，使实体字段允许为 null。
     *
     * @param type 基础类型
     * @return 可空联合类型
     */
    private static Schema nullable(Schema type) {
        return Schema.createUnion(Arrays.asList(Schema.create(Schema.Type.NULL), type));
    }

    /**
     * 实体字段类型到 Avro 基础类型的映射，不可映射时返回 null。
     *
     * @param type 字段类型
     * @return Avro 基础类型，无法映射返回 null
     */
    private static Schema avroTypeOf(Class<?> type) {
        if (type == String.class || type.isEnum()) {
            // 枚举以常量名平铺落盘，读回时由 ReflectUtils 按名称还原
            return Schema.create(Schema.Type.STRING);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Schema.create(Schema.Type.BOOLEAN);
        }
        if (type == float.class || type == Float.class) {
            return Schema.create(Schema.Type.FLOAT);
        }
        if (type == double.class || type == Double.class) {
            return Schema.create(Schema.Type.DOUBLE);
        }
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return Schema.create(Schema.Type.LONG);
        }
        if (type == Date.class) {
            Schema longType = Schema.create(Schema.Type.LONG);
            return LogicalTypes.timestampMillis().addToSchema(longType);
        }
        return null;
    }

    /**
     * 把实体字段值适配成 Avro 模式声明的类型，避免包装类型/枚举写入时被 Avro 校验拒绝。
     *
     * @param value  字段原始值
     * @param schema 列模式（可空联合）
     * @return 适配后的值
     */
    private static Object toSchemaValue(Object value, Schema schema) {
        if (value == null) {
            return null;
        }
        Schema.Type type = unwrapNullable(schema).getType();
        if (type == Schema.Type.STRING) {
            if (value instanceof Enum<?> enumValue) {
                return enumValue.name();
            }
            return value instanceof CharSequence ? value.toString() : value;
        }
        if (value instanceof Date date && type == Schema.Type.LONG) {
            // parquet-avro 对 LONG 列直接按 Number 取值，Date 必须先降为毫秒数
            return date.getTime();
        }
        if (value instanceof Number number) {
            return switch (type) {
                case LONG -> number.longValue();
                case INT -> number.intValue();
                case FLOAT -> number.floatValue();
                case DOUBLE -> number.doubleValue();
                default -> value;
            };
        }
        return value;
    }

    /**
     * 取可空联合中的实际类型。
     *
     * @param schema 列模式
     * @return 实际类型模式
     */
    private static Schema unwrapNullable(Schema schema) {
        if (schema.getType() == Schema.Type.UNION) {
            for (Schema member : schema.getTypes()) {
                if (member.getType() != Schema.Type.NULL) {
                    return member;
                }
            }
        }
        return schema;
    }

    /**
     * 实体字段 -> 列名映射缓存，按字段声明顺序（含父类字段）构建，列名冲突时追加序号。
     *
     * @param clazz 实体类
     * @return 列名到字段的映射
     */
    private static Map<String, Field> fieldsOf(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new LinkedHashMap<>();
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    String column = avroName(camelToSnake(f.getName()));
                    if (column.isEmpty()) {
                        continue;
                    }
                    String unique = column;
                    int suffix = 2;
                    while (map.containsKey(unique)) {
                        unique = column + "_" + suffix++;
                    }
                    map.put(unique, f);
                }
            }
            return Collections.unmodifiableMap(map);
        });
    }

    /**
     * 驼峰转 snake_大小写。
     *
     * @param name 名称
     * @return camel转为snake的结果
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
     * 规范化为 Avro 合法名称：非法字符替换为下划线，数字开头补前导下划线。
     *
     * @param name 原始名称
     * @return 合法名称，入参为空时返回空串
     */
    private static String avroName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        String fixed = ILLEGAL_NAME_CHAR.matcher(name).replaceAll("_");
        if (Character.isDigit(fixed.charAt(0))) {
            fixed = "_" + fixed;
        }
        return fixed;
    }

    /**
     * 按列名定位实体字段，兼容原始列名、驼峰、大小写与下划线增减等写法。
     *
     * @param fields 列名到字段的映射
     * @param column 待解析列名
     * @return 匹配的字段，找不到返回 null
     */
    private static Field resolveField(Map<String, Field> fields, String column) {
        if (column == null) {
            return null;
        }
        String key = column.trim();
        if (key.isEmpty()) {
            return null;
        }
        Field hit = fields.get(key);
        if (hit != null) {
            return hit;
        }
        hit = fields.get(avroName(camelToSnake(key)));
        if (hit != null) {
            return hit;
        }
        String flat = stripUnderscore(key);
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key) || stripUnderscore(e.getKey()).equalsIgnoreCase(flat)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 去掉全部下划线，用于宽松比对列名写法。
     *
     * @param name 名称
     * @return 去下划线后的名称
     */
    private static String stripUnderscore(String name) {
        return name.replace("_", "");
    }

    /**
     * 归一化表达式中的列名（驼峰/大小写/去下划线变体 -> 实体字段名）。
     * <p>WHERE 谓词由 {@link MemoryWhereParser} 反射读取实体属性求值，
     * 因此必须归一到 Java 字段名而不是文件列名，否则被规范化的列会静默取不到值。</p>
     *
     * @param expr        条件表达式
     * @param entityClass 实体类
     * @return normalizeColumns的结果
     */
    private String normalizeColumns(String expr, Class<?> entityClass) {
        if (expr == null || expr.isEmpty()) {
            return expr;
        }
        Map<String, Field> fields = fieldsOf(entityClass);
        List<String[]> rules = new ArrayList<>();
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            String javaName = e.getValue().getName();
            addRule(rules, e.getKey(), javaName);
            addRule(rules, javaName.toLowerCase(), javaName);
            addRule(rules, stripUnderscore(e.getKey()), javaName);
        }
        rules.sort((x, y) -> y[0].length() - x[0].length());
        String out = expr;
        for (String[] rule : rules) {
            out = out.replaceAll("(?<![\\w])" + Pattern.quote(rule[0]) + "(?![\\w])",
                    Matcher.quoteReplacement(rule[1]));
        }
        return out;
    }

    /**
     * 追加别名规则（去重、忽略同名词）。
     *
     * @param rules     规则列表
     * @param variant   列名变体
     * @param canonical 目标字段名
     */
    private void addRule(List<String[]> rules, String variant, String canonical) {
        if (variant != null && !variant.isEmpty() && !variant.equals(canonical)
                && rules.stream().noneMatch(r -> r[0].equals(variant))) {
            rules.add(new String[]{variant, canonical});
        }
    }

    /**
     * 把值写入实体字段：先走类型转换，再校验反射写入结果，失败立即抛出而不静默丢列。
     *
     * @param target 目标实体
     * @param field  目标字段
     * @param raw    原始值
     */
    private static void assign(Object target, Field field, Object raw) {
        Object value = convert(raw, field.getType());
        if (!ReflectUtils.setField(target, field.getName(), value)) {
            throw new IllegalStateException("Parquet 列映射失败: " + field.getDeclaringClass().getName()
                    + "#" + field.getName() + " 无法接收值类型 "
                    + (value == null ? "null" : value.getClass().getName()));
        }
    }

    /**
     * Avro 取值按目标类型转换（Utf8/数值/布尔）。
     *
     * @param v    原始值
     * @param type 目标类型
     * @return 转换的结果
     */
    private static Object convert(Object v, Class<?> type) {
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(v, type);
        if (converted != null) {
            return converted;
        }
        // Converter 未命中时原样交给 ReflectUtils 按字段类型自适应，
        // 不再退化成 v.toString()——那会把布尔/时间等值污染成字符串
        return v;
    }

    /**
     * 解析内存/文件引擎可求值的 SET 字面量，无法求值时抛出而不静默跳过。
     *
     * @param column 列名
     * @param rhs    赋值右侧文本
     * @return 字面量值
     */
    private static Object parseLiteral(String column, String rhs) {
        if (rhs.length() >= 2 && rhs.charAt(0) == '\'' && rhs.charAt(rhs.length() - 1) == '\'') {
            return rhs.substring(1, rhs.length() - 1).replace("''", "'");
        }
        if ("NULL".equalsIgnoreCase(rhs)) {
            return null;
        }
        try {
            return rhs.indexOf('.') >= 0 ? Double.valueOf(rhs) : Long.valueOf(rhs);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parquet 引擎无法求值的 SET 表达式: " + column + " = " + rhs);
        }
    }

    /**
     * 同步父类内存镜像，使其与刚写回文件的整表数据一致。
     *
     * @param table 表名
     * @param rows  最新整表数据
     * @param <T>   实体类型
     */
    private <T> void syncMemory(String table, List<T> rows) {
        if (dataStores.containsKey(table)) {
            dataStores.put(table, new ArrayList<>(rows));
        }
    }

    /**
     * 引擎侧分页截断：父类以 {@code (0, 0)} 调用时不生效，
     * 直接传入 limit/offset 时不再被忽略。
     *
     * @param rows   待截断行
     * @param limit  行数上限，0 表示不限制
     * @param offset 偏移行数，0 表示不偏移
     * @param <T>    实体类型
     * @return 截断后的行
     */
    private static <T> List<T> applyPaging(List<T> rows, int limit, int offset) {
        List<T> out = rows;
        if (offset > 0) {
            out = offset >= out.size() ? List.of() : new ArrayList<>(out.subList(offset, out.size()));
        }
        if (limit > 0 && limit < out.size()) {
            out = new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    /**
     * Parquet 目录数据源封装：底层源为基础目录，无 SQL 方言。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static final class ParquetDataSource implements EngineDataSource<String> {

        /**
         * 数据源名称
         */
        private final String dataSourceName;

        /**
         * Parquet 基础目录
         */
        private volatile String dir;

        /**
         * 方言（列式文件引擎不使用，仅按接口契约保留）
         */
        private Dialect dialect;

        /**
         * 创建 Parquet 目录数据源。
         *
         * @param dataSourceName 数据源名称
         * @param dir            基础目录
         */
        ParquetDataSource(String dataSourceName, String dir) {
            this.dataSourceName = dataSourceName;
            this.dir = dir;
        }

        @Override
        public String name() {
            return dataSourceName;
        }

        @Override
        public String getSource() {
            return dir;
        }

        @Override
        public EngineDataSource<String> setSource(Object source) {
            this.dir = source == null ? null : String.valueOf(source);
            return this;
        }

        @Override
        public Dialect getDialect() {
            return dialect;
        }

        @Override
        public EngineDataSource<String> setDialect(Dialect value) {
            this.dialect = value;
            return this;
        }

        @Override
        public String url() {
            return dir;
        }

        @Override
        public String username() {
            return null;
        }

        @Override
        public String password() {
            return null;
        }
    }
}
