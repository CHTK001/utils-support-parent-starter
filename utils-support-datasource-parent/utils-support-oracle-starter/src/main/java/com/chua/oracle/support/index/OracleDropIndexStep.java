package com.chua.oracle.support.index;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.datasource.support.index.IndexManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Oracle 删除索引链式步骤实现。
 * <p>
 * 语法为 {@code DROP INDEX [schema.]索引名}：Oracle 删除索引不挂表名，
 * {@link #onTable(String)} 仅为与其他方言保持链式 API 一致而保留，
 * 传入时只做白名单校验、不参与语句拼装。
 * </p>
 * <p>
 * 索引名经 {@link SqlName} 校验后加双引号，与创建索引时的引用方式一致，
 * 保证大小写敏感的对象名能被正确删除。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleDropIndexStep implements IndexManager.DropIndexStep {

    /**
     * ORA-01418：指定的索引不存在
     */
    private static final int ORA_INDEX_NOT_EXIST = 1418;

    /**
     * ORA-02282：索引由唯一/主键约束维护，不能单独删除
     */
    private static final int ORA_INDEX_HELD_BY_CONSTRAINT = 2282;

    /**
     * 数据来源
     */
    private final DataSource dataSource;

    /**
     * 索引名称，可为 {@code schema.index} 限定名，构造时即完成白名单校验
     */
    private final String indexName;

    /**
     * 构造方法，创建 Oracle 删除索引 Step 实例。
     *
     * @param dataSource 数据来源，不允许为 null
     * @param indexName  索引名称，必须通过 {@link SqlName} 白名单校验
     * @throws IllegalArgumentException 数据源为空，或索引名为空、含非法字符
     */
    OracleDropIndexStep(DataSource dataSource, String indexName) {
        if (dataSource == null) {
            throw new IllegalArgumentException("删除 Oracle 索引需要数据源");
        }
        this.dataSource = dataSource;
        this.indexName = SqlName.check(indexName, "索引名");
    }

    /**
     * Oracle 删除索引不需要表名，但保留 {@code onTable} 方法以保持链式 API 一致。
     *
     * @param table 表名（Oracle 忽略此参数，仅做合法性校验）
     * @return this
     * @throws IllegalArgumentException 表名含非法字符
     */
    @Override
    public OracleDropIndexStep onTable(String table) {
        SqlName.check(table, "表名");
        return this;
    }

    /**
     * 执行删除索引语句。
     * <p>
     * 链式契约的 {@code execute()} 为 {@code void}，无法像布尔返回的删除接口那样把
     * “索引不存在”表达成 {@code false}，因此索引确实不存在时同样抛出异常并在消息中
     * 说明原因，与 JDBC 失败区分开；两种情况都不会静默返回。
     * </p>
     *
     * @throws IllegalStateException 索引不存在，或删除失败（原因链入 {@link SQLException}）
     */
    @Override
    public void execute() {
        String sql = "DROP INDEX " + SqlName.quote(indexName, "索引名");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            int code = e.getErrorCode();
            if (code == ORA_INDEX_NOT_EXIST) {
                throw new IllegalStateException("Oracle 索引不存在: " + indexName + " (ORA-01418)", e);
            }
            if (code == ORA_INDEX_HELD_BY_CONSTRAINT) {
                throw new IllegalStateException("Oracle 索引 " + indexName
                        + " 由唯一/主键约束维护，需删除对应约束 (ORA-02282)", e);
            }
            throw new IllegalStateException("删除 Oracle 索引失败: " + indexName + ", 语句: " + sql, e);
        }
    }
}
