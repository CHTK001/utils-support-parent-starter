package com.chua.parquet.support.migration;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link MigrationService} 单元测试：覆盖迁移成功、批次边界、部分失败回滚、
 * 插件缺失、启动校验（插件名重复/为空）与销毁释放。
 *
 * @author CH
 * @since 4.0.0.51
 */
class MigrationServiceTest {

    /**
     * 构造一行测试数据。
     */
    private static Map<String, Object> row(int id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", (long) id);
        row.put("name", "用户" + id);
        return row;
    }

    /**
     * 构造 id 从 1 到 count 的行集合。
     */
    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(row(i));
        }
        return rows;
    }

    /**
     * 迁移成功：5 行按每批 2 行拆为 3 批（2+2+1），offset 依次为 0/2/4。
     */
    @Test
    void migrateSuccessWithPartialLastBatch() {
        FakeMigrator plugin = new FakeMigrator(rows(5), -1);
        MigrationService service = new MigrationService(
                Map.of("fake", mock(Engine.class)), List.of(plugin));
        service.init();

        MigrateResult result = service.migrate(request(2));

        assertTrue(result.isSuccess());
        assertEquals("fake->fake", result.getMigratorName());
        assertEquals(5L, result.getTotalRead());
        assertEquals(5L, result.getTotalWritten());
        assertEquals(3, result.getBatchCount());
        assertEquals(List.of(0L, 2L, 4L), plugin.readOffsets);
        assertEquals(3, plugin.writtenBatches.size());
        assertEquals(1, plugin.writtenBatches.get(2).size());
    }

    /**
     * 批次整除边界：4 行按每批 2 行拆为 2 批，末批写完后再次读取返回空批终止。
     */
    @Test
    void migrateStopsOnEmptyBatchWhenExactMultiple() {
        FakeMigrator plugin = new FakeMigrator(rows(4), -1);
        MigrationService service = new MigrationService(
                Map.of("fake", mock(Engine.class)), List.of(plugin));
        service.init();

        MigrateResult result = service.migrate(request(2));

        assertTrue(result.isSuccess());
        assertEquals(4L, result.getTotalRead());
        assertEquals(2, result.getBatchCount());
        assertEquals(List.of(0L, 2L, 4L), plugin.readOffsets);
    }

    /**
     * 部分失败：第 2 批写入失败 → 返回失败结果并回滚已写入数据，会话被清理。
     */
    @Test
    void migrateRollsBackWhenBatchWriteFails() {
        FakeMigrator plugin = new FakeMigrator(rows(5), 2);
        MigrationService service = new MigrationService(
                Map.of("fake", mock(Engine.class)), List.of(plugin));
        service.init();

        MigrateResult result = service.migrate(request(2));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("模拟写入失败"));
        assertTrue(result.getMessage().contains("回滚"));
        assertTrue(plugin.rolledBack);
        assertEquals(1, plugin.writtenBatches.size());
        // 会话已清理：再次要求会话应抛出异常
        assertThrows(IllegalStateException.class, plugin::requireSession);
    }

    /**
     * 插件缺失：返回失败结果而非抛出异常。
     */
    @Test
    void migrateReturnsFailureWhenPluginMissing() {
        MigrationService service = new MigrationService(Map.of(), List.of());
        service.init();

        MigrateResult result = service.migrate(MigrateRequest.builder()
                .sourceType("nope").targetType("other")
                .sourceTable("s").targetTable("t")
                .batchSize(10)
                .build());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("未找到迁移插件"));
        assertEquals("nope->other", result.getMigratorName());
    }

    /**
     * 源端 Engine 缺失：返回失败结果并说明缺失的端别与名称。
     */
    @Test
    void migrateReturnsFailureWhenEngineMissing() {
        FakeMigrator plugin = new FakeMigrator(rows(2), -1);
        MigrationService service = new MigrationService(Map.of(), List.of(plugin));
        service.init();

        MigrateResult result = service.migrate(request(2));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("源端 Engine 未找到"));
        assertFalse(plugin.rolledBack);
    }

    /**
     * 启动校验：插件名重复时 init 抛出 IllegalStateException。
     */
    @Test
    void initFailsWhenPluginNameDuplicated() {
        MigrationService service = new MigrationService(Map.of(), List.of(
                new NamedMigrator("dup", 0), new NamedMigrator("dup", 1)));
        assertThrows(IllegalStateException.class, service::init);
    }

    /**
     * 启动校验：插件名为空时 init 抛出 IllegalStateException。
     */
    @Test
    void initFailsWhenPluginNameBlank() {
        MigrationService service = new MigrationService(Map.of(), List.of(new NamedMigrator(" ", 0)));
        assertThrows(IllegalStateException.class, service::init);
    }

    /**
     * 生命周期：destroy 逐个关闭插件且单个插件关闭异常不影响其余插件。
     */
    @Test
    void destroyClosesAllPlugins() {
        FakeMigrator plugin = new FakeMigrator(rows(1), -1);
        FailingCloseMigrator failing = new FailingCloseMigrator();
        MigrationService service = new MigrationService(Map.of(), List.of(plugin, failing));

        service.destroy();

        assertTrue(plugin.closed);
        assertTrue(failing.closeAttempted);
    }

    /**
     * 构造 fake->fake 方向的迁移请求。
     */
    private static MigrateRequest request(int batchSize) {
        return MigrateRequest.builder()
                .sourceType("fake").targetType("fake")
                .sourceTable("source").targetTable("target")
                .batchSize(batchSize)
                .build();
    }

    /**
     * 测试插件：内存数据源，可在指定批次抛出写入异常。
     */
    @MigrationPlugin(name = "fake->fake", order = 0)
    static class FakeMigrator extends AbstractPluginMigrator {

        private final List<Map<String, Object>> sourceRows;
        private final int failOnWriteCall;

        final List<Long> readOffsets = new ArrayList<>();
        final List<List<Map<String, Object>>> writtenBatches = new ArrayList<>();
        boolean rolledBack;
        boolean closed;

        FakeMigrator(List<Map<String, Object>> sourceRows, int failOnWriteCall) {
            this.sourceRows = sourceRows;
            this.failOnWriteCall = failOnWriteCall;
        }

        @Override
        public List<Map<String, Object>> read(Object source, MigrateRequest request, long offset, int batchSize) {
            readOffsets.add(offset);
            int from = (int) Math.min(offset, sourceRows.size());
            int to = (int) Math.min(offset + batchSize, sourceRows.size());
            return new ArrayList<>(sourceRows.subList(from, to));
        }

        @Override
        public long write(Object target, MigrateRequest request, List<Map<String, Object>> batch) {
            if (writtenBatches.size() + 1 == failOnWriteCall) {
                throw new IllegalStateException("模拟写入失败");
            }
            writtenBatches.add(new ArrayList<>(batch));
            requireSession().addWrittenRows(batch);
            return batch.size();
        }

        @Override
        protected void doRollback(MigrationSession session) {
            rolledBack = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * 可自定义名称/顺序的裸插件（不继承 AbstractPluginMigrator），用于启动校验测试。
     */
    static class NamedMigrator implements DataMigrator {

        private final String name;
        private final int order;

        NamedMigrator(String name, int order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public List<Map<String, Object>> read(Object source, MigrateRequest request, long offset, int batchSize) {
            return List.of();
        }

        @Override
        public long write(Object target, MigrateRequest request, List<Map<String, Object>> batch) {
            return 0L;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public void close() {
        }
    }

    /**
     * close 时抛异常的插件，用于验证 destroy 的异常隔离。
     */
    @MigrationPlugin(name = "failing-close", order = 9)
    static class FailingCloseMigrator extends AbstractPluginMigrator {

        boolean closeAttempted;

        @Override
        public List<Map<String, Object>> read(Object source, MigrateRequest request, long offset, int batchSize) {
            return List.of();
        }

        @Override
        public long write(Object target, MigrateRequest request, List<Map<String, Object>> batch) {
            return 0L;
        }

        @Override
        protected void doRollback(MigrationSession session) {
        }

        @Override
        public void close() {
            closeAttempted = true;
            throw new IllegalStateException("关闭失败");
        }
    }
}
