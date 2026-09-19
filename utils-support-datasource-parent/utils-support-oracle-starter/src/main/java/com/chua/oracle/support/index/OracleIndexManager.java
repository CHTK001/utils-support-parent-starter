package com.chua.oracle.support.index;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.datasource.support.index.IndexManager;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Oracle 索引管理器 SPI 实现。
 * <p>
 * 索引列表来自 {@code USER_INDEXES} 视图，创建与删除分别走
 * {@link OracleCreateIndexStep} 与 {@link OracleDropIndexStep}，
 * 索引形态默认普通 B 树索引，位图索引必须显式索取。
 * </p>
 * <p>
 * 表名与索引名等标识符一律先经 {@link SqlName} 白名单校验，取值仍走占位符绑定；
 * JDBC 失败统一抛出 {@link IllegalStateException} 并链入原始异常，
 * 只有对象确实不存在时才返回空列表。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OracleIndexManager implements IndexManager, DataSourceAware {

    /**
     * 按表名查索引：同时匹配原值与全大写值。
     * <p>不加引号的 Oracle 对象以大写存储，而本实现建索引时使用双引号引用，
     * 对象名按调用方给定的大小写存储，两种写法都要覆盖。</p>
     */
    private static final String LIST_INDEX_SQL =
            "SELECT index_name FROM user_indexes WHERE table_name = ? OR table_name = ? "
                    + "ORDER BY index_name";

    /**
     * 数据来源
     */
    private DataSource dataSource;

    /**
     * 返回 SPI 扩展键：{@code oracle}
     *
     * @return "oracle"
     */
    @Override
    public String type() {
        return "oracle";
    }

    /**
     * 设置 JDBC 数据源，由 SPI 工厂自动调用。
     *
     * @param dataSource 数据源
     */
    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 查询指定表上的所有索引（通过 {@code USER_INDEXES} 视图）。
     *
     * @param table 表名，可带一段 {@code schema.} 前缀（视图只按对象名匹配，前缀会被剔除）
     * @return 索引名列表；表不存在或表上没有索引时返回空列表
     * @throws IllegalArgumentException 表名为空或含非法字符
     * @throws IllegalStateException    数据源未绑定或 JDBC 访问失败
     */
    @Override
    public List<String> listIndexes(String table) {
        String name = objectOf(SqlName.check(table, "表名"));
        List<String> list = new ArrayList<>();
        try (Connection conn = requireDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(LIST_INDEX_SQL)) {
            ps.setString(1, name);
            ps.setString(2, name.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("index_name"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出 Oracle 索引失败: " + table, e);
        }
        return list;
    }

    /**
     * 创建索引的链式构建器。
     * <p>
     * 默认生成普通 {@code CREATE INDEX}；需要位图索引时调用
     * {@code type("BITMAP")}，或持有具体类型时调用
     * {@link OracleCreateIndexStep#bitmap()}，位图索引的适用限制见
     * {@link OracleIndexKind#BITMAP}。
     * </p>
     *
     * @param indexName 索引名，必须通过白名单校验
     * @return 创建索引的链式步骤对象
     * @throws IllegalArgumentException 索引名为空或含非法字符
     */
    @Override
    public OracleCreateIndexStep createIndex(String indexName) {
        return new OracleCreateIndexStep(requireDataSource(), indexName);
    }

    /**
     * 删除索引的链式构建器。
     *
     * @param indexName 索引名，可带一段 {@code schema.} 前缀，必须通过白名单校验
     * @return 删除索引的链式步骤对象
     * @throws IllegalArgumentException 索引名为空或含非法字符
     */
    @Override
    public OracleDropIndexStep dropIndex(String indexName) {
        return new OracleDropIndexStep(requireDataSource(), indexName);
    }

    /**
     * 取得已绑定的数据源。
     *
     * @return JDBC 数据源
     * @throws IllegalStateException 数据源未绑定
     */
    private DataSource requireDataSource() {
        DataSource source = dataSource;
        if (source == null) {
            throw new IllegalStateException("Oracle 索引管理器未绑定数据源");
        }
        return source;
    }

    /**
     * 取限定名中的对象段，{@code USER_INDEXES} 按对象名而非限定名存储。
     *
     * @param name 已通过 {@link SqlName} 校验的标识符
     * @return 去掉 {@code schema.} 前缀后的对象名
     */
    private static String objectOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
