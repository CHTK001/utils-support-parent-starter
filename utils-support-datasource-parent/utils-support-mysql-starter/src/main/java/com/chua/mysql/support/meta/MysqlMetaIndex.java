package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.dialect.meta.IndexMetadata;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.IndexCreateBuilder;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 索引元数据操作。
 * <p>
 * 索引明细统一来自 {@code INFORMATION_SCHEMA.STATISTICS}（读取实现与表元数据共用
 * {@link MysqlMetaTable#readIndexes(String, String, String)}，包内只保留一份 SQL），
 * 以 {@code TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ?} 过滤，
 * 库名与表名均为绑定参数；多列索引按 {@code SEQ_IN_INDEX} 归并成一条索引定义。
 * </p>
 * <p>
 * 能力边界：MySQL 的 {@code information_schema} 不暴露索引可见性，
 * 因此 {@link IndexMetadata#isInvisible()} 在只读路径上恒为 {@code false}（不伪造）；
 * 写入路径支持 {@code INVISIBLE}（MySQL 8.0+）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaIndex extends AbstractMetaIndex {

    /**
     * 构造方法（无索引名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected MysqlMetaIndex(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带索引名上下文）。
     *
     * @param metaData  元数据入口
     * @param engine    引擎实例
     * @param indexName 索引名
     */
    protected MysqlMetaIndex(AbstractMetaData metaData, Engine engine, String indexName) {
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
        List<IndexMetadata> indexes = readIndexes(target);
        for (IndexMetadata meta : indexes) {
            if (target.equals(meta.getName())) {
                return meta;
            }
        }
        return null;
    }

    @Override
    public IndexCreateBuilder create(String indexName) {
        return new MysqlIndexCreateBuilder(this, indexName);
    }

    @Override
    public boolean drop(String indexName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
        }
        String sql = "ALTER TABLE " + quote(tableName) + " DROP INDEX " + quote(indexName);
        return MysqlMetaData.execute(engine, "删除索引 " + indexName, sql, List.of());
    }

    /**
     * 读取索引列表：SQL 与多列归并逻辑与表元数据共用同一份实现，避免包内重复。
     *
     * @param indexName 索引名过滤，{@code null} 表示全部
     * @return 归并后的索引定义列表
     * @throws IllegalStateException 未指定表名或查询失败
     */
    private List<IndexMetadata> readIndexes(String indexName) {
        if (tableName == null) {
            throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
        }
        return new MysqlMetaTable(metaData, engine, tableName)
                .readIndexes(MysqlMetaData.resolveSchema(metaData), tableName, indexName);
    }

    /**
     * 引用 MySQL 标识符。
     *
     * @param name 标识符
     * @return 引用后的标识符
     */
    private String quote(String name) {
        return MysqlMetaData.quote(name);
    }

    /**
     * MySQL 建索引链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlIndexCreateBuilder implements IndexCreateBuilder {

        /**
         * 所属索引元数据入口
         */
        private final MysqlMetaIndex metaIndex;
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
         * 索引算法
         */
        private String type;
        /**
         * 注释
         */
        private String comment;
        /**
         * 可见性
         */
        private boolean visible = true;

        MysqlIndexCreateBuilder(MysqlMetaIndex metaIndex, String indexName) {
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
            this.type = MysqlMetaData.checkDdlFragment("索引类型", type);
            return this;
        }

        @Override
        public IndexCreateBuilder using(String algorithm) {
            this.type = MysqlMetaData.checkDdlFragment("索引类型", algorithm);
            return this;
        }

        @Override
        public IndexCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public IndexCreateBuilder visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        @Override
        public IndexMetadata execute() {
            if (metaIndex.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
            }
            if (columns.isEmpty()) {
                throw new IllegalStateException("索引列不能为空");
            }
            String sql = MysqlMetaData.addIndexClause(MysqlMetaData.quote(metaIndex.tableName), indexName, columns,
                    unique, type, comment, visible);
            MysqlMetaData.execute(metaIndex.engine, "创建索引 " + indexName, sql, List.of());
            return metaIndex.get(indexName);
        }
    }
}
