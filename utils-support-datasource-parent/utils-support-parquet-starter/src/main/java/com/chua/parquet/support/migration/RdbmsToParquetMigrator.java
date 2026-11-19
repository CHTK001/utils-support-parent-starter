package com.chua.parquet.support.migration;

import com.chua.common.support.lang.datasource.engine.Engine;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内置迁移插件：RDBMS 表 -&gt; Parquet 文件。
 *
 * <p>读取侧通过 {@link JdbcTemplate#queryForList} 执行 {@code SELECT *} 后按 offset
 * 内存分批；写入侧为<b>追加语义</b>：首次写入前对目标文件做快照，之后每批将
 * 「快照 + 已累积新增 + 本批」整体原子覆写到 {@code <baseDir>/<表名>.parquet}。</p>
 *
 * <p>失败回滚：恢复迁移前快照——迁移前文件存在则重写回快照内容，否则删除迁移产生的新文件。</p>
 *
 * <p>依赖注入采用 JSR-330 标准注解 {@code @Inject} 字段注入 {@link JdbcTemplate}。
 * 注：Spring Framework 7 起不再运行期处理 {@code javax.inject}，实际装配由
 * {@code AdditionalPluginsConfiguration} 的 {@code @Bean} 方法调用
 * {@link #setJdbcTemplate(JdbcTemplate)} 完成，本注解保留为标准元数据并兼容其他 JSR-330 容器。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@MigrationPlugin(name = "rdbms->parquet", order = 0)
public class RdbmsToParquetMigrator extends AbstractPluginMigrator {

    /**
     * 数据库操作模板（JSR-330 字段注入）
     */
    @Inject
    private JdbcTemplate jdbcTemplate;

    /**
     * 注入数据库操作模板。
     * <p>Spring 7 环境下由自动装配 {@code @Bean} 方法调用；JSR-330 容器下由容器字段注入。</p>
     *
     * @param jdbcTemplate 数据库操作模板
     */
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Map<String, Object>> read(Object source, MigrateRequest request, long offset, int batchSize) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("RdbmsToParquetMigrator 未注入 JdbcTemplate，无法读取源表");
        }
        String table = sanitizeTable(request.getSourceTable());
        List<Map<String, Object>> all = jdbcTemplate.queryForList("SELECT * FROM " + table);
        int from = (int) Math.min(offset, all.size());
        int to = (int) Math.min(offset + batchSize, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    @Override
    public long write(Object target, MigrateRequest request, List<Map<String, Object>> batch) {
        if (!(target instanceof Engine engine)) {
            throw new IllegalArgumentException("rdbms->parquet 迁移要求目标端为 Engine 实例，实际为: "
                    + (target == null ? "null" : target.getClass().getName()));
        }
        if (batch == null || batch.isEmpty()) {
            return 0L;
        }
        File file = parquetFile(engine, request.getTargetTable());
        MigrationSession session = requireSession();
        if (!session.isSnapshotLoaded()) {
            session.setTargetFileExisted(file.exists());
            session.setSnapshotRecords(readRecords(file));
            session.setTargetFile(file);
            session.setSnapshotLoaded(true);
        }
        // 追加语义：快照 + 本次迁移已累积新增 + 本批数据整体覆写，避免批次间相互覆盖
        session.addAppendedRows(batch);
        List<Map<String, Object>> combined = new ArrayList<>(
                session.getSnapshotRecords().size() + session.getAppendedRows().size());
        for (GenericRecord snapshot : session.getSnapshotRecords()) {
            combined.add(recordToMap(snapshot));
        }
        combined.addAll(session.getAppendedRows());
        SchemaMapping mapping = inferSchema(combined);
        List<GenericRecord> records = new ArrayList<>(combined.size());
        for (Map<String, Object> row : combined) {
            records.add(mapToRecord(row, mapping));
        }
        writeRecords(file, mapping.getSchema(), records);
        log.info("[migration] rdbms->parquet 写入文件 {}，累计 {} 行（本批 {} 行）",
                file.getAbsolutePath(), combined.size(), batch.size());
        return batch.size();
    }

    @Override
    protected void doRollback(MigrationSession session) {
        if (!session.isSnapshotLoaded()) {
            log.info("[migration] rdbms->parquet 未发生目标写入，无需回滚");
            return;
        }
        File file = session.getTargetFile();
        List<GenericRecord> snapshot = session.getSnapshotRecords();
        if (snapshot.isEmpty()) {
            // 迁移前目标文件不存在（或为空）：删除迁移产生的新文件
            boolean removed = file.exists() && file.delete();
            log.warn("[migration] rdbms->parquet 回滚完成: 目标文件 {} {}", file.getAbsolutePath(),
                    removed ? "已删除" : "不存在或删除失败");
            return;
        }
        writeRecords(file, snapshot.get(0).getSchema(), snapshot);
        log.warn("[migration] rdbms->parquet 回滚完成: 已恢复迁移前快照，文件 {}，共 {} 行",
                file.getAbsolutePath(), snapshot.size());
    }

    /**
     * 释放插件资源：无持有关闭型资源，仅记录日志保证幂等。
     */
    @Override
    public void close() {
        log.debug("[migration] rdbms->parquet 插件关闭");
    }
}
