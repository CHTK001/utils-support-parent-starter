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
 * 内置迁移插件：Parquet 文件 -&gt; RDBMS 表。
 *
 * <p>读取侧复用 {@code ParquetEngine} 的文件布局（{@code <baseDir>/<表名>.parquet}），
 * 整表读入后按 offset 切片分批返回；写入侧通过 {@link JdbcTemplate} 拼接
 * {@code INSERT INTO ... VALUES (...)} 批量落库。</p>
 *
 * <p>失败回滚：按「全列匹配」删除本次已插入的行（{@code col = ? OR (col IS NULL AND ? IS NULL)}），
 * 仅回滚本插件写入的数据，不影响目标表既有数据。</p>
 *
 * <p>依赖注入采用 JSR-330 标准注解 {@code @Inject} 构造注入 {@link JdbcTemplate}。
 * 注：Spring Framework 7 起不再运行期处理 {@code javax.inject}，实际装配由
 * {@code AdditionalPluginsConfiguration} 的 {@code @Bean} 方法参数解析完成，
 * 本注解保留为标准元数据并兼容其他 JSR-330 容器。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@MigrationPlugin(name = "parquet->rdbms", order = 0)
public class ParquetToRdbmsMigrator extends AbstractPluginMigrator {

    /**
     * 数据库操作模板（JSR-330 构造注入）
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造插件。
     *
     * @param jdbcTemplate 数据库操作模板
     */
    @Inject
    public ParquetToRdbmsMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Map<String, Object>> read(Object source, MigrateRequest request, long offset, int batchSize) {
        if (!(source instanceof Engine engine)) {
            throw new IllegalArgumentException("parquet->rdbms 迁移要求源端为 Engine 实例，实际为: "
                    + (source == null ? "null" : source.getClass().getName()));
        }
        File file = parquetFile(engine, request.getSourceTable());
        List<GenericRecord> records = readRecords(file);
        int from = (int) Math.min(offset, records.size());
        int to = (int) Math.min(offset + batchSize, records.size());
        List<Map<String, Object>> batch = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            batch.add(recordToMap(records.get(i)));
        }
        return batch;
    }

    @Override
    public long write(Object target, MigrateRequest request, List<Map<String, Object>> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0L;
        }
        String table = sanitizeTable(request.getTargetTable());
        List<String> columns = new ArrayList<>(batch.get(0).keySet());
        String insertSql = buildInsertSql(table, columns);
        List<Object[]> args = new ArrayList<>(batch.size());
        for (Map<String, Object> row : batch) {
            Object[] one = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                one[i] = row.get(columns.get(i));
            }
            args.add(one);
        }
        jdbcTemplate.batchUpdate(insertSql, args);
        requireSession().addWrittenRows(batch);
        log.info("[migration] parquet->rdbms 写入表 {} 本批 {} 行", table, batch.size());
        return batch.size();
    }

    @Override
    protected void doRollback(MigrationSession session) {
        List<Map<String, Object>> written = session.getWrittenRows();
        if (written.isEmpty()) {
            log.info("[migration] parquet->rdbms 无已写入数据，无需回滚");
            return;
        }
        String table = sanitizeTable(session.getRequest().getTargetTable());
        List<String> columns = new ArrayList<>(written.get(0).keySet());
        String deleteSql = buildDeleteSql(table, columns);
        int deleted = 0;
        for (Map<String, Object> row : written) {
            List<Object> args = new ArrayList<>(columns.size() * 2);
            for (String column : columns) {
                Object value = row.get(column);
                // 同时绑定「等值比较」与「IS NULL 判定」两组参数
                args.add(value);
                args.add(value);
            }
            deleted += jdbcTemplate.update(deleteSql, args.toArray());
        }
        log.warn("[migration] parquet->rdbms 回滚完成: 表 {}，待回滚 {} 行，实际删除 {} 行", table, written.size(), deleted);
    }

    /**
     * 拼接批量 INSERT 语句。
     *
     * @param table   表名（已校验）
     * @param columns 列名列表
     * @return INSERT 语句
     */
    private static String buildInsertSql(String table, List<String> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(columns.get(i));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
        sql.append(')');
        return sql.toString();
    }

    /**
     * 拼接按全列匹配的 DELETE 语句：NULL 值使用 {@code IS NULL} 判定而非 {@code = NULL}。
     *
     * @param table   表名（已校验）
     * @param columns 列名列表
     * @return DELETE 语句
     */
    private static String buildDeleteSql(String table, List<String> columns) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(table).append(" WHERE ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            String column = columns.get(i);
            sql.append('(').append(column).append(" = ? OR (").append(column)
                    .append(" IS NULL AND ? IS NULL))");
        }
        return sql.toString();
    }

    /**
     * 释放插件资源：无持有关闭型资源，仅记录日志保证幂等。
     */
    @Override
    public void close() {
        log.debug("[migration] parquet->rdbms 插件关闭");
    }
}
