package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.IndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.MetaIndex;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaIndex;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
/**
* @author CH
* @since 4.0.0.42
 */

public class MysqlMetaIndex extends AbstractMetaIndex {

    /**
    * 创建 mysqlmeta索引 实例
    * @param metaData meta数据
    * @param engine Engine
    * @param engine engine
     */
    protected MysqlMetaIndex(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
    * 创建 mysqlmeta索引 实例
    * @param metaData meta数据
    * @param engine Engine
    * @param indexName 字符串
    * @param engine engine
    * @param indexName 索引名称
     */
    protected MysqlMetaIndex(AbstractMetaData metaData, Engine engine, String indexName) {
        super(metaData, engine, indexName);
    }

    @Override
    /** 列表 */
    public List<IndexMetadata> list() {
        List<IndexMetadata> result = new ArrayList<>();
        if (tableName == null) {
            return result;
        }
        String sql = "SHOW INDEX FROM " + quote(tableName);
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String keyName = rs.getString("Key_name");
                String columnName = rs.getString("Column_name");
                String nonUnique = rs.getString("Non_unique");
                String indexType = rs.getString("Index_type");
                int position = rs.getInt("Seq_in_index");
                String comment = rs.getString("Comment");
                boolean isUnique = "0".equals(nonUnique);
                boolean isPrimary = "PRIMARY".equals(keyName);
                IndexMetadata existing = result.stream()
                        .filter(m -> keyName.equals(m.getName()))
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    IndexMetadata meta = new IndexMetadata();
                    meta.setName(keyName);
                    meta.setTableName(tableName);
                    meta.setPrimary(isPrimary);
                    meta.setUnique(isUnique);
                    meta.setType(indexType);
                    meta.setComment(comment);
                    meta.setPosition(position);
                    List<String> cols = new ArrayList<>();
                    cols.add(columnName);
                    meta.setColumns(cols);
                    meta.setColumnName(columnName);
                    result.add(meta);
                } else {
                    existing.getColumns().add(columnName);
                    if (existing.getPosition() == null || position < existing.getPosition()) {
                        existing.setPosition(position);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("列出索引失败: " + tableName, e);
        }
        return result;
    }

    @Override
    /** 获取 */
    public IndexMetadata get(String indexName) {
        List<IndexMetadata> all = list();
        return all.stream()
                .filter(m -> indexName.equals(m.getName()))
                .findFirst()
                .orElse(null);
    }

    @Override
    /** 创建 */
    public IndexCreateBuilder create(String indexName) {
        return new MysqlIndexCreateBuilder(this, indexName);
    }

    @Override
    /** 掉落 */
    public boolean drop(String indexName) {
        String sql = "ALTER TABLE " + quote(tableName) + " DROP INDEX " + quote(indexName);
        return executeUpdate(sql);
    }

    /**
    * 获取Connection
    *
    * @return 获取connection的结果
     */
    protected Connection getConnection() throws Exception {
        EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
        if (eds == null) {
            throw new IllegalStateException("默认数据源未配置");
        }
        Object source = eds.getSource();
        if (source instanceof DataSource ds) {
            return ds.getConnection();
        }
        throw new IllegalStateException("数据源类型不支持 JDBC 连接获取: " + source.getClass().getName());
    }

    /**
    * 引述
    *
    * @param name 名称
    * @return 引述的结果
     */
    private String quote(String name) {
        return "`" + name + "`";
    }

    /**
    * 执行更新
    *
    * @param sql SQL
    * @return 执行更新的结果
    * @author CH
    * @since 4.0.0
     */
    private boolean executeUpdate(String sql) {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    private static class MysqlIndexCreateBuilder implements IndexCreateBuilder {

        /** Meta索引 */
        private final MysqlMetaIndex metaIndex;
        /** 索引名称 */
        private final String indexName;
        /** Columns */
        private final List<String> columns = new ArrayList<>();
        /** Unique */
        private boolean unique;
        /** 类型 */
        private String type;
        /** 评论 */
        private String comment;
        /** Visible */
        private boolean visible = true;

        MysqlIndexCreateBuilder(MysqlMetaIndex metaIndex, String indexName) {
            this.metaIndex = metaIndex;
            this.indexName = indexName;
        }

        @Override
        /** Column */
        public IndexCreateBuilder column(String columnName) {
            columns.add(columnName);
            return this;
        }

        @Override
        /** Columns */
        public IndexCreateBuilder columns(String... columnNames) {
            for (String col : columnNames) {
                columns.add(col);
            }
            return this;
        }

        @Override
        /** Unique */
        public IndexCreateBuilder unique() {
            this.unique = true;
            return this;
        }

        @Override
        /** 类型 */
        public IndexCreateBuilder type(String type) {
            this.type = type;
            return this;
        }

        @Override
        /** 使用 */
        public IndexCreateBuilder using(String algorithm) {
            this.type = algorithm;
            return this;
        }

        @Override
        /** 评论 */
        public IndexCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        /** Visible */
        public IndexCreateBuilder visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        @Override
        /** 执行 */
        public IndexMetadata execute() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("索引列不能为空");
            }
            if (metaIndex.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable()");
            }
            StringBuilder sb = new StringBuilder();
            if (unique) {
                sb.append("ALTER TABLE ").append(metaIndex.quote(metaIndex.tableName)).append(" ADD UNIQUE INDEX ");
            } else if (type != null && !type.isEmpty()) {
                sb.append("ALTER TABLE ").append(metaIndex.quote(metaIndex.tableName)).append(" ADD INDEX ");
            } else {
                sb.append("ALTER TABLE ").append(metaIndex.quote(metaIndex.tableName)).append(" ADD INDEX ");
            }
            sb.append(metaIndex.quote(indexName)).append(" (");
            sb.append(String.join(", ", columns.stream().map(metaIndex::quote).toList()));
            sb.append(")");
            if (type != null && !type.isEmpty() && !unique) {
                sb.append(" USING ").append(type);
            }
            if (comment != null && !comment.isEmpty()) {
                sb.append(" COMMENT '").append(escapeSql(comment)).append("'");
            }
            metaIndex.executeUpdate(sb.toString());
            return metaIndex.get(indexName);
        }
    }

    /**
    * escapesql
    *
    * @param value 值
    * @return escapeSql的结果
     */
    private static String escapeSql(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
}
