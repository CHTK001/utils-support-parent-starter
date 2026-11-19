package com.chua.parquet.support.migration;

import com.chua.parquet.support.engine.ParquetEngine;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RdbmsToParquetMigrator} 单元测试：覆盖分批读取、追加写入、
 * 快照回滚（恢复/删除）与部分失败场景（Mockito 模拟 JdbcTemplate + 真实 Parquet 文件）。
 *
 * @author CH
 * @since 4.0.0.51
 */
class RdbmsToParquetMigratorTest {

    @TempDir
    File tempDir;

    private ParquetEngine engine;
    private JdbcTemplate jdbc;
    private RdbmsToParquetMigrator migrator;

    @BeforeEach
    void setUp() {
        engine = new ParquetEngine();
        engine.addDataSource("default", tempDir.getAbsolutePath());
        jdbc = mock(JdbcTemplate.class);
        migrator = new RdbmsToParquetMigrator();
        migrator.setJdbcTemplate(jdbc);
    }

    /**
     * 构造行数据：id/name/age（age 可为 null）。
     */
    private static Map<String, Object> row(int n, Integer age) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", (long) n);
        row.put("name", "用户" + n);
        row.put("age", age);
        return row;
    }

    /**
     * 构造 id 从 1 到 count 的行集合。
     */
    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(row(i, i * 10));
        }
        return rows;
    }

    /**
     * 构造 rdbms->parquet 迁移请求。
     */
    private static MigrateRequest request(int batchSize) {
        return MigrateRequest.builder()
                .sourceType("rdbms").targetType("parquet")
                .sourceTable("users").targetTable("users")
                .batchSize(batchSize)
                .build();
    }

    /**
     * 读取分批：SELECT * 结果在内存中按 offset/batchSize 切片。
     */
    @Test
    void readBatchesByOffset() {
        when(jdbc.queryForList("SELECT * FROM users")).thenReturn(rows(5));
        MigrateRequest request = request(2);

        List<Map<String, Object>> first = migrator.read(null, request, 0, 2);
        assertEquals(2, first.size());
        assertEquals("用户1", first.get(0).get("name"));

        List<Map<String, Object>> third = migrator.read(null, request, 4, 2);
        assertEquals(1, third.size());
        assertEquals("用户5", third.get(0).get("name"));

        assertTrue(migrator.read(null, request, 5, 2).isEmpty());
    }

    /**
     * 写入新文件：两批共 5 行全部落盘，时间戳列以 timestamp-millis 往返保持精度。
     */
    @Test
    void writeAppendsBatchesIntoNewFile() {
        Timestamp created = Timestamp.valueOf("2026-01-02 03:04:05");
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", 1L);
        row1.put("name", "用户1");
        row1.put("created_at", created);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", 2L);
        row2.put("name", "用户2");
        row2.put("created_at", null);

        MigrateRequest request = request(2);
        migrator.begin(request);
        assertEquals(1L, migrator.write(engine, request, List.of(row1)));
        assertEquals(1L, migrator.write(engine, request, List.of(row2)));

        File file = new File(tempDir, "users.parquet");
        assertTrue(file.exists());
        List<GenericRecord> records = AbstractPluginMigrator.readRecords(file);
        assertEquals(2, records.size());

        Map<String, Object> first = AbstractPluginMigrator.recordToMap(records.get(0));
        assertEquals(1L, first.get("id"));
        assertEquals("用户1", first.get("name"));
        assertEquals(created, first.get("created_at"));
        assertNull(AbstractPluginMigrator.recordToMap(records.get(1)).get("created_at"));
    }

    /**
     * 追加语义：目标文件已有数据时，新批次写入后包含全部历史行与新行。
     */
    @Test
    void writeAppendsToExistingFile() {
        List<GenericRecord> existing = new ArrayList<>();
        existing.add(AbstractPluginMigrator.mapToRecord(row(0, 0),
                AbstractPluginMigrator.inferSchema(List.of(row(0, 0)))));
        AbstractPluginMigrator.writeRecords(new File(tempDir, "users.parquet"),
                AbstractPluginMigrator.inferSchema(List.of(row(0, 0))).getSchema(), existing);

        MigrateRequest request = request(2);
        migrator.begin(request);
        migrator.write(engine, request, List.of(row(1, 10)));
        migrator.write(engine, request, List.of(row(2, 20)));

        List<GenericRecord> records = AbstractPluginMigrator.readRecords(new File(tempDir, "users.parquet"));
        assertEquals(3, records.size());
        assertEquals(0L, AbstractPluginMigrator.recordToMap(records.get(0)).get("id"));
        assertEquals(1L, AbstractPluginMigrator.recordToMap(records.get(1)).get("id"));
        assertEquals(2L, AbstractPluginMigrator.recordToMap(records.get(2)).get("id"));
    }

    /**
     * 回滚（迁移前无文件）：删除迁移产生的新文件。
     */
    @Test
    void rollbackDeletesCreatedFile() {
        MigrateRequest request = request(2);
        migrator.begin(request);
        migrator.write(engine, request, List.of(row(1, 10)));
        assertTrue(new File(tempDir, "users.parquet").exists());

        migrator.rollback(request);

        assertFalse(new File(tempDir, "users.parquet").exists());
    }

    /**
     * 回滚（迁移前已有文件）：恢复迁移前快照内容。
     */
    @Test
    void rollbackRestoresSnapshot() {
        Map<String, Object> original = row(0, 0);
        AbstractPluginMigrator.SchemaMapping mapping = AbstractPluginMigrator.inferSchema(List.of(original));
        List<GenericRecord> existing = new ArrayList<>();
        existing.add(AbstractPluginMigrator.mapToRecord(original, mapping));
        AbstractPluginMigrator.writeRecords(new File(tempDir, "users.parquet"),
                mapping.getSchema(), existing);

        MigrateRequest request = request(2);
        migrator.begin(request);
        migrator.write(engine, request, List.of(row(1, 10), row(2, 20)));
        assertEquals(3, AbstractPluginMigrator.readRecords(new File(tempDir, "users.parquet")).size());

        migrator.rollback(request);

        List<GenericRecord> restored = AbstractPluginMigrator.readRecords(new File(tempDir, "users.parquet"));
        assertEquals(1, restored.size());
        assertEquals(0L, AbstractPluginMigrator.recordToMap(restored.get(0)).get("id"));
    }

    /**
     * 端到端部分失败：第 2 批读取失败 → 服务回滚，迁移前无文件时删除新文件，
     * 返回失败结果且消息含「回滚」。
     */
    @Test
    void serviceRollsBackWhenSecondReadFails() {
        when(jdbc.queryForList("SELECT * FROM users"))
                .thenReturn(rows(5))
                .thenThrow(new IllegalStateException("模拟读取失败"));
        MigrationService service = new MigrationService(Map.of("parquet", engine), List.of(migrator));
        service.init();

        MigrateResult result = service.migrate(request(2));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("模拟读取失败"));
        assertTrue(result.getMessage().contains("回滚"));
        assertFalse(new File(tempDir, "users.parquet").exists());
    }

    /**
     * 未注入 JdbcTemplate 时读取应抛出明确异常。
     */
    @Test
    void readFailsWithoutJdbcTemplate() {
        RdbmsToParquetMigrator bare = new RdbmsToParquetMigrator();
        assertThrows(IllegalStateException.class,
                () -> bare.read(null, request(2), 0, 2));
    }

    /**
     * 插件元数据来自注解；destroy 关闭插件为幂等操作。
     */
    @Test
    void pluginMetadataAndClose() {
        assertEquals("rdbms->parquet", migrator.getName());
        assertEquals(0, migrator.getOrder());
        migrator.close();
        // 幂等：重复关闭不抛异常
        migrator.close();
        verify(jdbc, times(0)).queryForList(anyString());
    }
}
