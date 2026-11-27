package com.chua.parquet.support.migration;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.chua.common.support.utils.CollectionUtils;

/**
 * 迁移插件抽象基类：提供插件注解解析、迁移会话管理与 Parquet 本地 IO 公共能力。
 *
 * <p>子类只需关注两侧存储的 {@link #read} / {@link #write} 与 {@link #doRollback}；
 * 插件名称与顺序从类上的 {@link MigrationPlugin} 注解读取，未标注注解的实现在
 * 构造期即失败，避免出现无名插件混入 {@link MigrationService}。</p>
 *
 * <p>迁移会话（{@link MigrationSession}）通过 {@code ThreadLocal} 承载一次
 * {@code migrate} 调用内的状态：已写入的目标行（供回滚删除）、目标文件迁移前
 * 快照（供恢复覆盖）。{@link MigrationService} 按
 * {@code begin -> read/write 循环 -> (失败时 rollback) -> cleanup} 驱动。</p>
 *
 * <p>Parquet 文件布局与 {@code ParquetEngine} 保持一致：
 * {@code <baseDir>/<表名>.parquet}，baseDir 取引擎默认数据源的 {@code url()}。
 * 本地 IO 复用 parquet 自带的 {@link LocalInputFile}/{@link LocalOutputFile}，
 * 写入先落临时文件再原子改名，避免中途失败破坏既有数据。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public abstract class AbstractPluginMigrator implements DataMigrator {

    /**
     * Parquet 数据文件扩展名，与 ParquetEngine 保持一致
     */
    protected static final String PARQUET_EXT = ".parquet";

    /**
     * 临时文件序号：并发迁移同一表时避免临时文件名冲突
     */
    private static final AtomicLong TMP_SEQ = new AtomicLong();

    /**
     * 插件名称（来自 @MigrationPlugin#name）
     */
    private final String pluginName;

    /**
     * 插件执行顺序（来自 @MigrationPlugin#order）
     */
    private final int pluginOrder;

    /**
     * 当前线程的迁移会话；一次 migrate 调用内有效
     */
    private final ThreadLocal<MigrationSession> sessionHolder = new ThreadLocal<>();

    /**
     * 构造插件：从实现类的 {@link MigrationPlugin} 注解解析名称与顺序。
     *
     * @throws IllegalStateException 实现类未标注 @MigrationPlugin 时抛出
     */
    protected AbstractPluginMigrator() {
        MigrationPlugin plugin = getClass().getAnnotation(MigrationPlugin.class);
        if (plugin == null) {
            throw new IllegalStateException("迁移插件缺少 @MigrationPlugin 注解: " + getClass().getName());
        }
        this.pluginName = plugin.name();
        this.pluginOrder = plugin.order();
    }

    @Override
    public final String getName() {
        return pluginName;
    }

    @Override
    public final int getOrder() {
        return pluginOrder;
    }

    /**
     * 开始迁移会话。由 {@link MigrationService} 在迁移开始时调用。
     *
     * @param request 迁移请求
     */
    public void begin(MigrateRequest request) {
        sessionHolder.set(new MigrationSession(request));
    }

    /**
     * 结束并清理迁移会话；无论迁移成功或失败都必须调用，防止 ThreadLocal 泄漏。
     */
    public void cleanup() {
        try {
            onCleanup();
        } finally {
            sessionHolder.remove();
        }
    }

    /**
     * 回滚目标端已写入的数据。无活动会话时仅记录警告并返回。
     *
     * @param request 迁移请求
     */
    public void rollback(MigrateRequest request) {
        MigrationSession session = sessionHolder.get();
        if (session == null) {
            log.warn("[migration] 插件 {} 无活动迁移会话，跳过回滚", pluginName);
            return;
        }
        doRollback(session);
    }

    /**
     * 获取当前迁移会话；未开始会话时抛出异常。
     *
     * @return 当前迁移会话
     */
    protected MigrationSession requireSession() {
        MigrationSession session = sessionHolder.get();
        if (session == null) {
            throw new IllegalStateException("插件 " + pluginName + " 无活动迁移会话，请先调用 begin(request)");
        }
        return session;
    }

    /**
     * 会话清理钩子，子类按需覆盖；调用方保证在 finally 中执行。
     */
    protected void onCleanup() {
    }

    /**
     * 执行回滚：将目标端恢复到本次迁移之前的状态。
     *
     * @param session 当前迁移会话
     */
    protected abstract void doRollback(MigrationSession session);

    // ==================== Parquet 本地 IO ====================

    /**
     * 定位 Parquet 引擎下指定表的数据文件：{@code <baseDir>/<表名>.parquet}。
     *
     * @param engine Parquet 引擎
     * @param table  表名
     * @return 数据文件
     * @throws IllegalStateException 引擎未注册数据源或目录不可用时抛出
     */
    protected static File parquetFile(Engine engine, String table) {
        EngineDataSource<?> dataSource = engine.getDataSource(engine.getDefaultDataSourceName());
        if (dataSource == null || dataSource.url() == null) {
            throw new IllegalStateException("Parquet 引擎未注册数据源，无法定位表文件: " + table);
        }
        var dir = new File(dataSource.url().trim());
        if (!dir.isDirectory()) {
            throw new IllegalStateException("Parquet 目录不存在: " + dir.getAbsolutePath());
        }
        return new File(dir, table + PARQUET_EXT);
    }

    /**
     * 读取整个 Parquet 文件的全部记录；文件不存在或为空时返回空列表。
     *
     * @param file Parquet 数据文件
     * @return 记录列表
     */
    protected static List<GenericRecord> readRecords(File file) {
        var records = new ArrayList<GenericRecord>();
        if (!file.exists() || file.length() == 0L) {
            return records;
        }
        try (ParquetReader<GenericRecord> reader =
                     AvroParquetReader.<GenericRecord>builder(new LocalInputFile(file.toPath())).build()) {
            GenericRecord record;
            while ((record = reader.read()) != null) {
                records.add(record);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Parquet 读取失败: " + file.getAbsolutePath(), e);
        }
        return records;
    }

    /**
     * 以指定模式原子覆写 Parquet 文件：先写临时文件，成功后原子改名替换目标文件。
     *
     * @param file    目标文件
     * @param schema  Avro 模式
     * @param records 待写入记录
     */
    protected static void writeRecords(File file, Schema schema, List<GenericRecord> records) {
        var tmp = new File(file.getParentFile(),
                file.getName() + ".migrating-" + TMP_SEQ.incrementAndGet() + ".tmp");
        try {
            try (ParquetWriter<GenericRecord> writer = AvroParquetWriter
                    .<GenericRecord>builder(new LocalOutputFile(tmp.toPath()))
                    .withSchema(schema)
                    .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                    .build()) {
                for (GenericRecord record : records) {
                    writer.write(record);
                }
            }
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException ignored) {
                // 临时文件清理失败不影响数据正确性，残留文件会被下次写入覆盖
            }
            throw new IllegalStateException("Parquet 写入失败: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 将 {@link GenericRecord} 转为有序 {@code Map}（列名 -> 值）。
     * Avro {@code string} 读出为 {@code Utf8}，统一转为 {@code String}；
     * {@code timestamp-millis} 逻辑类型列转为 {@link java.sql.Timestamp}，便于直插 RDBMS。
     *
     * @param record Parquet 记录
     * @return 行数据
     */
    protected static Map<String, Object> recordToMap(GenericRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Schema.Field field : record.getSchema().getFields()) {
            row.put(field.name(), fromAvroValue(record.get(field.name()), field.schema()));
        }
        return row;
    }

    /**
     * 按行数据推断 Parquet 写入模式：列序取自首行，列类型取自首个非空值，
     * 全空列退化为 string。同时给出 Avro 字段名到源列名的映射（列名含非法字符时
     * 会被归一化，写回时需要按原列名取值）。
     *
     * @param rows 行数据（至少一行）
     * @return 模式推断结果
     */
    protected static SchemaMapping inferSchema(List<Map<String, Object>> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            throw new IllegalArgumentException("无法从空数据推断 Parquet 模式");
        }
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        List<Schema.Field> fields = new ArrayList<>(columns.size());
        Map<String, String> fieldToColumn = new LinkedHashMap<>();
        for (String column : columns) {
            String fieldName = avroColumnName(column);
            Schema base = null;
            for (Map<String, Object> row : rows) {
                Object value = row.get(column);
                if (value != null) {
                    base = avroTypeOf(value);
                    break;
                }
            }
            if (base == null) {
                // 全列无非空值时按可空字符串落盘，保证模式仍可构建
                base = Schema.create(Schema.Type.STRING);
            }
            fields.add(new Schema.Field(fieldName, nullable(base), null, (Object) null));
            fieldToColumn.put(fieldName, column);
        }
        Schema schema = Schema.createRecord("MigrateRecord", null, null, false);
        schema.setFields(fields);
        return new SchemaMapping(schema, fieldToColumn);
    }

    /**
     * 将行数据按推断出的模式转为 {@link GenericRecord}。
     *
     * @param row     行数据
     * @param mapping 模式推断结果
     * @return Parquet 记录
     */
    protected static GenericRecord mapToRecord(Map<String, Object> row, SchemaMapping mapping) {
        GenericRecord record = new GenericData.Record(mapping.schema);
        for (Schema.Field field : mapping.schema.getFields()) {
            String column = mapping.fieldToColumn.get(field.name());
            record.put(field.name(), toAvroValue(row.get(column), field.schema()));
        }
        return record;
    }

    /**
     * 将任意表名归一化为合法 Avro 字段名：非法字符替换为下划线，数字开头补前缀。
     *
     * @param column 原始列名
     * @return 合法字段名
     */
    protected static String avroColumnName(String column) {
        String cleaned = column == null ? "" : column.replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "col";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    /**
     * 校验并返回安全的表名：仅允许字母/数字/下划线/点号/汉字组合，防止 SQL 拼接注入。
     *
     * @param table 表名
     * @return 校验通过的表名
     */
    protected static String sanitizeTable(String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        String trimmed = table.trim();
        if (!trimmed.matches("[A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_.\\u4e00-\\u9fa5]*")) {
            throw new IllegalArgumentException("表名包含非法字符: " + table);
        }
        return trimmed;
    }

    /**
     * Avro 值转业务值：Utf8 转 String，timestamp-millis 转 java.sql.Timestamp。
     *
     * @param value       Avro 读出的原始值
     * @param fieldSchema 列模式（可空联合）
     * @return 业务值
     */
    private static Object fromAvroValue(Object value, Schema fieldSchema) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence chars) {
            return chars.toString();
        }
        Schema base = unwrapNullable(fieldSchema);
        if (base.getType() == Schema.Type.LONG
                && base.getLogicalType() instanceof LogicalTypes.TimestampMillis) {
            // 兼容 Long 毫秒与 parquet-avro 读出的 java.time 时间类型
            return toTimestamp(value);
        }
        return value;
    }

    /**
     * 业务值转 Avro 值：按目标列模式适配类型，避免 Avro 写入校验拒绝。
     *
     * @param value       业务值
     * @param fieldSchema 列模式（可空联合）
     * @return Avro 值
     */
    private static Object toAvroValue(Object value, Schema fieldSchema) {
        if (value == null) {
            return null;
        }
        Schema.Type type = unwrapNullable(fieldSchema).getType();
        if (type == Schema.Type.STRING) {
            if (value instanceof byte[] bytes) {
                return Base64.getEncoder().encodeToString(bytes);
            }
            if (value instanceof CharSequence || value instanceof Enum<?>) {
                return value instanceof CharSequence chars ? chars.toString() : ((Enum<?>) value).name();
            }
            return String.valueOf(value);
        }
        if (type == Schema.Type.LONG && value instanceof Temporal temporal) {
            // Instant/LocalDateTime 等 java.time 类型统一降为毫秒数
            return toEpochMillis(temporal);
        }
        if (value instanceof Date date && type == Schema.Type.LONG) {
            // java.sql.Timestamp/java.sql.Date 均为 Date 子类，统一降为毫秒数
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
     * 时间值统一转为 {@link Timestamp}：兼容毫秒数与 parquet-avro 读出的
     * java.time 时间类型（不同版本实现可能返回 Instant/OffsetDateTime）。
     *
     * @param value 时间值
     * @return 时间戳
     */
    private static Timestamp toTimestamp(Object value) {
        if (value instanceof Number number) {
            return new Timestamp(number.longValue());
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp;
        }
        if (value instanceof Date date) {
            return new Timestamp(date.getTime());
        }
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return Timestamp.from(offsetDateTime.toInstant());
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return Timestamp.from(zonedDateTime.toInstant());
        }
        throw new IllegalStateException("无法将时间值转换为 Timestamp: " + value.getClass().getName());
    }

    /**
     * java.time 时间类型统一转为毫秒数，供 timestamp-millis 列落盘。
     *
     * @param temporal 时间值
     * @return 纪元毫秒数
     */
    private static long toEpochMillis(Temporal temporal) {
        if (temporal instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (temporal instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (temporal instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant().toEpochMilli();
        }
        if (temporal instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant().toEpochMilli();
        }
        throw new IllegalStateException("无法将时间值转换为毫秒数: " + temporal.getClass().getName());
    }

    /**
     * 按值推断 Avro 基础类型：字符串/布尔/浮点/整数/时间；其余类型退化为 string。
     *
     * @param value 非空业务值
     * @return Avro 基础类型
     */
    private static Schema avroTypeOf(Object value) {
        if (value instanceof CharSequence) {
            return Schema.create(Schema.Type.STRING);
        }
        if (value instanceof Boolean) {
            return Schema.create(Schema.Type.BOOLEAN);
        }
        if (value instanceof Float) {
            return Schema.create(Schema.Type.FLOAT);
        }
        if (value instanceof Double) {
            return Schema.create(Schema.Type.DOUBLE);
        }
        if (value instanceof Date || value instanceof Temporal) {
            return LogicalTypes.timestampMillis().addToSchema(Schema.create(Schema.Type.LONG));
        }
        if (value instanceof Number) {
            return Schema.create(Schema.Type.LONG);
        }
        return Schema.create(Schema.Type.STRING);
    }

    /**
     * 包装为 Avro 可空联合类型 {@code ["null", type]}。
     *
     * @param type 基础类型
     * @return 可空联合类型
     */
    private static Schema nullable(Schema type) {
        return Schema.createUnion(java.util.Arrays.asList(Schema.create(Schema.Type.NULL), type));
    }

    /**
     * 取可空联合中的实际类型模式。
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
     * 模式推断结果：Avro 模式 + Avro 字段名到源列名的映射。
     */
    protected static final class SchemaMapping {

        /**
         * 推断出的 Avro 模式
         */
        private final Schema schema;

        /**
         * Avro 字段名 -> 源列名
         */
        private final Map<String, String> fieldToColumn;

        private SchemaMapping(Schema schema, Map<String, String> fieldToColumn) {
            this.schema = schema;
            this.fieldToColumn = fieldToColumn;
        }

        /**
         * 获取推断出的 Avro 模式。
         *
         * @return Avro 模式
         */
        public Schema getSchema() {
            return schema;
        }

        /**
         * 获取 Avro 字段名到源列名的映射。
         *
         * @return 字段名到列名的映射
         */
        public Map<String, String> getFieldToColumn() {
            return fieldToColumn;
        }
    }

    /**
     * 迁移会话：一次 migrate 调用内的状态容器，用于失败回滚。
     */
    public static final class MigrationSession {

        /**
         * 迁移请求
         */
        private final MigrateRequest request;

        /**
         * 已写入目标端的行（parquet->rdbms 回滚删除依据）
         */
        private final List<Map<String, Object>> writtenRows = new ArrayList<>();

        /**
         * 本次迁移已累积写入目标文件的新增行（rdbms->parquet 追加语义，跨批累加）
         */
        private final List<Map<String, Object>> appendedRows = new ArrayList<>();

        /**
         * 目标文件迁移前快照（rdbms->parquet 回滚恢复依据）
         */
        private List<GenericRecord> snapshotRecords;

        /**
         * 快照是否已加载（首次向目标写入前加载）
         */
        private boolean snapshotLoaded;

        /**
         * 迁移前目标文件是否存在
         */
        private boolean targetFileExisted;

        /**
         * 迁移目标文件（首次写入时定位并缓存）
         */
        private File targetFile;

        private MigrationSession(MigrateRequest request) {
            this.request = request;
        }

        /**
         * 获取迁移请求。
         *
         * @return 迁移请求
         */
        public MigrateRequest getRequest() {
            return request;
        }

        /**
         * 获取已写入目标端的行（只读视图）。
         *
         * @return 已写入行列表
         */
        public List<Map<String, Object>> getWrittenRows() {
            return writtenRows;
        }

        /**
         * 追加已写入目标端的行。
         *
         * @param rows 本批写入的行
         */
        void addWrittenRows(List<Map<String, Object>> rows) {
            writtenRows.addAll(rows);
        }

        /**
         * 获取本次迁移已累积写入目标文件的新增行（只读视图）。
         *
         * @return 已累积新增行列表
         */
        public List<Map<String, Object>> getAppendedRows() {
            return appendedRows;
        }

        /**
         * 追加本批写入目标文件的新增行。
         *
         * @param rows 本批新增行
         */
        void addAppendedRows(List<Map<String, Object>> rows) {
            appendedRows.addAll(rows);
        }

        /**
         * 是否已加载目标文件快照。
         *
         * @return 已加载返回 {@code true}
         */
        public boolean isSnapshotLoaded() {
            return snapshotLoaded;
        }

        /**
         * 标记快照加载状态。
         *
         * @param snapshotLoaded 快照是否已加载
         */
        void setSnapshotLoaded(boolean snapshotLoaded) {
            this.snapshotLoaded = snapshotLoaded;
        }

        /**
         * 获取目标文件迁移前快照。
         *
         * @return 快照记录列表
         */
        public List<GenericRecord> getSnapshotRecords() {
            return snapshotRecords;
        }

        /**
         * 设置目标文件迁移前快照。
         *
         * @param snapshotRecords 快照记录列表
         */
        void setSnapshotRecords(List<GenericRecord> snapshotRecords) {
            this.snapshotRecords = snapshotRecords;
        }

        /**
         * 迁移前目标文件是否存在。
         *
         * @return 存在返回 {@code true}
         */
        public boolean isTargetFileExisted() {
            return targetFileExisted;
        }

        /**
         * 标记迁移前目标文件是否存在。
         *
         * @param targetFileExisted 是否存在
         */
        void setTargetFileExisted(boolean targetFileExisted) {
            this.targetFileExisted = targetFileExisted;
        }

        /**
         * 获取迁移目标文件。
         *
         * @return 目标文件
         */
        public File getTargetFile() {
            return targetFile;
        }

        /**
         * 设置迁移目标文件。
         *
         * @param targetFile 目标文件
         */
        void setTargetFile(File targetFile) {
            this.targetFile = targetFile;
        }
    }
}
