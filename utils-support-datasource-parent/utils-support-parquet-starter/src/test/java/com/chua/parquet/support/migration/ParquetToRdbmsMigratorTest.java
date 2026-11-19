package com.chua.parquet.support.migration;

import com.chua.parquet.support.engine.ParquetEngine;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ParquetToRdbmsMigrator} 单元测试：覆盖分批读取、批量写入、
 * 空值处理与部分失败回滚（真实 Parquet 文件 + Mockito 模拟 JdbcTemplate）。
 *
 * @author CH
 * @since 4.0.0.51
 */
class ParquetToRdbmsMigratorTest {

    @TempDir
    File tempDir;

    private ParquetEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ParquetEngine();
        engine.addDataSource("default", tempDir.getAbsolutePath());
    }

    /**
     * 构造行数据：name 恒为「用户N」，age 为 N（age 可为 null）。
     */
    private static Map<String, Object> row(int n, Integer age) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "用户" + n);
        row.put("age", age);
        return row;
    }

    /**
     * 向临时目录写入 users.parquet 源文件。
     */
    private void writeSourceFile(List<Map<String, Object>> rows) {
        AbstractPluginMigrator.SchemaMapping mapping = AbstractPluginMigrator.inferSchema(rows);
        List<org.apache.avro.generic.GenericRecord> records = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            records.add(AbstractPluginMigrator.mapToRecord(row, mapping));
        }
        AbstractPluginMigrator.writeRecords(new File(tempDir, "users.parquet"),
                mapping.getSchema(), records);
    }

    /**
     * 构造 parquet->rdbms 迁移请求。
     */
    private static MigrateRequest request(int batchSize) {
        return MigrateRequest.builder()
                .sourceType("parquet").targetType("rdbms")
                .sourceTable("users").targetTable("users")
                .batchSize(batchSize)
                .build();
    }

    /**
     * 读取分批：5 行按每批 2 行，offset 0/2/4 分别得到 2/2/1 行，越界返回空批。
     */
    @Test
    void readBatchesByOffset() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(row(i, i * 10));
        }
        writeSourceFile(rows);

        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(mock(JdbcTemplate.class));
        MigrateRequest request = request(2);

        List<Map<String, Object>> first = migrator.read(engine, request, 0, 2);
        assertEquals(2, first.size());
        assertEquals("用户1", first.get(0).get("name"));
        assertEquals(10L, first.get(0).get("age"));

        List<Map<String, Object>> third = migrator.read(engine, request, 4, 2);
        assertEquals(1, third.size());
        assertEquals("用户5", third.get(0).get("name"));

        assertTrue(migrator.read(engine, request, 5, 2).isEmpty());
        assertTrue(migrator.read(engine, request, 100, 2).isEmpty());
    }

    /**
     * 源文件不存在时读取返回空批（不抛异常）。
     */
    @Test
    void readReturnsEmptyWhenFileMissing() {
        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(mock(JdbcTemplate.class));
        assertTrue(migrator.read(engine, request(2), 0, 2).isEmpty());
    }

    /**
     * 写入：按首行列序拼 INSERT，批量参数与行数据一致；空值列保留 null。
     */
    @Test
    void writeBuildsInsertAndTracksRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(jdbc);
        MigrateRequest request = request(2);
        migrator.begin(request);

        List<Map<String, Object>> batch = List.of(row(1, 10), row(2, null));
        long written = migrator.write(null, request, batch);

        assertEquals(2L, written);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> argsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(sqlCaptor.capture(), argsCaptor.capture());
        assertEquals("INSERT INTO users (name, age) VALUES (?, ?)", sqlCaptor.getValue());
        assertArrayEquals(new Object[]{"用户1", 10}, argsCaptor.getValue().get(0));
        assertArrayEquals(new Object[]{"用户2", null}, argsCaptor.getValue().get(1));
    }

    /**
     * 写入空批次直接返回 0，不触发 SQL。
     */
    @Test
    void writeEmptyBatchIsNoOp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(jdbc);
        migrator.begin(request(2));
        assertEquals(0L, migrator.write(null, request(2), List.of()));
        verify(jdbc, times(0)).batchUpdate(anyString(), anyList());
    }

    /**
     * 回滚：对已写入行按全列匹配逐行 DELETE，NULL 列使用 IS NULL 判定。
     */
    @Test
    void rollbackDeletesWrittenRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(jdbc);
        MigrateRequest request = request(2);
        migrator.begin(request);
        migrator.write(null, request, List.of(row(1, 10), row(2, null)));

        migrator.rollback(request);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(sqlCaptor.capture(), argsCaptor.capture());
        assertEquals("DELETE FROM users WHERE (name = ? OR (name IS NULL AND ? IS NULL)) "
                + "AND (age = ? OR (age IS NULL AND ? IS NULL))", sqlCaptor.getValue());
        assertArrayEquals(new Object[]{"用户1", "用户1", 10, 10}, argsCaptor.getAllValues().get(0));
        assertArrayEquals(new Object[]{"用户2", "用户2", null, null}, argsCaptor.getAllValues().get(1));
    }

    /**
     * 端到端部分失败：第 1 批写入成功、第 2 批失败 → 服务回滚删除第 1 批已写入行，
     * 返回失败结果且消息含「回滚」。
     */
    @Test
    void serviceRollsBackWrittenRowsWhenSecondBatchFails() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(row(i, i * 10));
        }
        writeSourceFile(rows);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(anyString(), anyList()))
                .thenReturn(new int[2])
                .thenThrow(new IllegalStateException("模拟写入失败"));
        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(jdbc);
        MigrationService service = new MigrationService(Map.of("parquet", engine), List.of(migrator));
        service.init();

        MigrateResult result = service.migrate(request(2));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("模拟写入失败"));
        assertTrue(result.getMessage().contains("回滚"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sqlCaptor.capture(), any(Object[].class));
        assertTrue(sqlCaptor.getValue().startsWith("DELETE FROM users"));
    }

    /**
     * 未注入会话时写入应抛出明确异常。
     */
    @Test
    void writeFailsWithoutSession() {
        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(mock(JdbcTemplate.class));
        assertThrows(IllegalStateException.class, () -> {
            migrator.write(null, request(2), List.of(row(1, 10)));
        });
    }

    /**
     * 插件元数据来自注解。
     */
    @Test
    void pluginMetadataFromAnnotation() {
        ParquetToRdbmsMigrator migrator = new ParquetToRdbmsMigrator(mock(JdbcTemplate.class));
        assertEquals("parquet->rdbms", migrator.getName());
        assertEquals(0, migrator.getOrder());
    }
}
