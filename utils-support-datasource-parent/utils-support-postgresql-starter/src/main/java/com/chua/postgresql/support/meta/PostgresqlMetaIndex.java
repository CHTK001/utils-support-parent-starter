package com.chua.postgresql.support.meta;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.IndexCreateBuilder;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 索引元数据操作。
 * <p>
 * 索引明细来自 {@code pg_index} + {@code pg_class} + {@code pg_am}，
 * 用 {@code unnest(indkey, indoption) WITH ORDINALITY} 展开多列索引，
 * 以 {@code nspname = COALESCE(?, current_schema()) AND t.relname = ?} 过滤，
 * 模式名与表名均为绑定参数；读取实现与表元数据共用
 * {@link PostgresqlMetaTable#readIndexes(String, String, String)}，包内只保留一份 SQL。
 * </p>
 * <p>
 * 能力边界：PostgreSQL 没有不可见索引，{@code visible(false)} 显式拒绝而不是静默忽略；
 * 索引注释通过 {@code COMMENT ON INDEX} 真实下发，读回时取自 {@code obj_description}。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgresqlMetaIndex extends AbstractMetaIndex {

    /**
     * 索引所属表名查询，用于 {@code drop(String)} 未指定 {@code onTable} 时补齐上下文。
     */
    private static final String INDEX_OWNER_SQL =
            "SELECT t.relname AS table_name, n.nspname AS schema_name"
                    + " FROM pg_catalog.pg_class ci"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = ci.relnamespace"
                    + " JOIN pg_catalog.pg_index ix ON ix.indexrelid = ci.oid"
                    + " JOIN pg_catalog.pg_class t ON t.oid = ix.indrelid"
                    + " WHERE ci.relkind = 'i' AND n.nspname = COALESCE(?, current_schema())"
                    + " AND ci.relname = ?";

    /**
     * 构造方法（无索引名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected PostgresqlMetaIndex(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带索引名上下文）。
     *
     * @param metaData  元数据入口
     * @param engine    引擎实例
     * @param indexName 索引名
     */
    protected PostgresqlMetaIndex(AbstractMetaData metaData, Engine engine, String indexName) {
        super(metaData, engine, indexName);
    }

    /**
     * 列出当前表的所有索引。
     *
     * @return 索引定义列表
     * @throws IllegalStateException 未指定表名或查询失败
     */
    @Override
    public List<IndexMetadata> list() {
        return readIndexes(null);
    }

    /**
     * 获取指定索引的定义。
     *
     * @param indexName 索引名，为 {@code null} 时使用构造期上下文
     * @return 索引定义，不存在时返回 {@code null}
     * @throws IllegalStateException 未指定表名或索引名、查询失败
     */
    @Override
    public IndexMetadata get(String indexName) {
        String target = indexName != null ? indexName : this.indexName;
        if (target == null) {
            throw new IllegalStateException("未指定索引名");
        }
        for (IndexMetadata meta : readIndexes(target)) {
            if (target.equals(meta.getName())) {
                return meta;
            }
        }
        return null;
    }

    @Override
    public IndexCreateBuilder create(String indexName) {
        return new PostgresIndexCreateBuilder(this, indexName);
    }

    /**
     * 删除索引。
     * <p>未调用 {@code onTable(String)} 时，先按索引名从字典定位其所属模式，保证删除的确实是该索引。</p>
     *
     * @param indexName 索引名
     * @return 是否成功
     * @throws IllegalStateException 索引不存在或执行失败
     */
    @Override
    public boolean drop(String indexName) {
        if (indexName == null) {
            throw new IllegalStateException("未指定索引名");
        }
        String sql = "DROP INDEX IF EXISTS " + qualified(indexName);
        return PostgresqlMetaData.execute(metaData, "删除索引 " + indexName, sql, PostgresqlMetaData.args());
    }

    /**
     * 读取索引列表。
     *
     * @param target 索引名过滤，{@code null} 表示全部
     * @return 索引定义列表
     * @throws IllegalStateException 未指定表名或查询失败
     */
    private List<IndexMetadata> readIndexes(String target) {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
        }
        return new PostgresqlMetaTable(metaData, engine, tableName)
                .readIndexes(PostgresqlMetaData.resolveSchema(metaData), tableName, target);
    }

    /**
     * 查询索引归属并以所属模式限定索引名。
     *
     * @param indexName 索引名
     * @return 可执行的索引引用名
     * @throws IllegalStateException 索引不存在或查询失败
     */
    private String qualified(String indexName) {
        String schema = PostgresqlMetaData.resolveSchema(metaData);
        String ownerSchema = PostgresqlMetaData.queryOne(metaData, "定位索引 " + indexName, INDEX_OWNER_SQL,
                PostgresqlMetaData.args(schema, indexName),
                rs -> PostgresqlMetaData.trimToNull(rs.getString("schema_name")));
        if (ownerSchema == null) {
            throw new IllegalStateException("索引不存在: " + indexName
                    + "（模式过滤=" + (schema == null ? "current_schema()" : schema) + "）");
        }
        return PostgresqlMetaData.quote(ownerSchema) + "." + PostgresqlMetaData.quote(indexName);
    }

    /**
     * PostgreSQL 建索引链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresIndexCreateBuilder implements IndexCreateBuilder {

        /**
         * 所属索引元数据入口
         */
        private final PostgresqlMetaIndex metaIndex;
        /**
         * 索引名
         */
        private final String indexName;
        /**
         * 索引列
         */
        private final List<String> columns = new ArrayList<>();
        /**
         * 唯一
         */
        private boolean unique;
        /**
         * 索引访问方法
         */
        private String type;
        /**
         * 注释
         */
        private String comment;

        PostgresIndexCreateBuilder(PostgresqlMetaIndex metaIndex, String indexName) {
            this.metaIndex = metaIndex;
            this.indexName = indexName;
        }

        @Override
        public IndexCreateBuilder column(String columnName) {
            columns.add(columnName);
            return this;
        }

        @Override
        public IndexCreateBuilder columns(String... columnNames) {
            for (String col : columnNames) {
                columns.add(col);
            }
            return this;
        }

        @Override
        public IndexCreateBuilder unique() {
            this.unique = true;
            return this;
        }

        @Override
        public IndexCreateBuilder type(String type) {
            this.type = PostgresqlMetaData.checkDdlFragment("索引类型", type);
            return this;
        }

        @Override
        public IndexCreateBuilder using(String algorithm) {
            this.type = PostgresqlMetaData.checkDdlFragment("索引类型", algorithm);
            return this;
        }

        @Override
        public IndexCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public IndexCreateBuilder visible(boolean visible) {
            if (!visible) {
                throw new UnsupportedOperationException("PostgreSQL 不支持不可见索引");
            }
            return this;
        }

        /**
         * 执行 {@code CREATE INDEX}（必要时追加 {@code COMMENT ON INDEX}），并以字典读回结果。
         *
         * @return 落库后的索引定义
         * @throws IllegalStateException 未指定表名/列或执行失败
         */
        @Override
        public IndexMetadata execute() {
            if (metaIndex.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
            }
            if (columns.isEmpty()) {
                throw new IllegalStateException("索引列不能为空");
            }
            List<String> quoted = new ArrayList<>();
            for (String col : columns) {
                quoted.add(PostgresqlMetaData.quote(col));
            }
            StringBuilder sql = new StringBuilder("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX ")
                    .append(PostgresqlMetaData.quote(indexName))
                    .append(" ON ").append(PostgresqlMetaData.quote(metaIndex.tableName));
            if (type != null) {
                sql.append(" USING ").append(type);
            }
            sql.append(" (").append(String.join(", ", quoted)).append(")");
            PostgresqlMetaData.execute(metaIndex.metaData, "创建索引 " + indexName, sql.toString(),
                    PostgresqlMetaData.args());
            if (comment != null && !comment.isEmpty()) {
                PostgresqlMetaData.execute(metaIndex.metaData, "注释索引 " + indexName,
                        "COMMENT ON INDEX " + PostgresqlMetaData.quote(indexName) + " IS '"
                                + PostgresqlMetaData.escapeSql(comment) + "'", PostgresqlMetaData.args());
            }
            return metaIndex.get(indexName);
        }
    }
}
