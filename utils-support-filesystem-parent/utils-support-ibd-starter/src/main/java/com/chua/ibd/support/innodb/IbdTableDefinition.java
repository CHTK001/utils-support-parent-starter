package com.chua.ibd.support.innodb;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 SDI 解析出来的表定义。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdTableDefinition {

    /**
     * 表名。
     */
    private final String name;

    /**
     * 所属库名。
     */
    private final String schema;

    /**
     * 行格式（{@code DYNAMIC} / {@code COMPACT} / {@code REDUNDANT} / {@code COMPRESSED}）。
     */
    private final String rowFormat;

    /**
     * 表默认排序规则 id。
     */
    private final long collationId;

    /**
     * 建表时的 MySQL 版本号（如 80032）。
     */
    private final long mysqlVersionId;

    /**
     * 全部列（含系统列），按 {@code ordinal_position} 排序。
     */
    private final List<IbdColumn> columns;

    /**
     * 全部索引。
     */
    private final List<IbdIndex> indexes;

    /**
     * 构造表定义。
     *
     * @param name           表名
     * @param schema         库名
     * @param rowFormat      行格式
     * @param collationId    排序规则 id
     * @param mysqlVersionId MySQL 版本号
     * @param columns        全部列
     * @param indexes        全部索引
     */
    public IbdTableDefinition(String name, String schema, String rowFormat, long collationId,
                              long mysqlVersionId, List<IbdColumn> columns, List<IbdIndex> indexes) {
        this.name = name;
        this.schema = schema;
        this.rowFormat = rowFormat;
        this.collationId = collationId;
        this.mysqlVersionId = mysqlVersionId;
        this.columns = List.copyOf(columns);
        this.indexes = List.copyOf(indexes);
    }

    /**
     * 表名。
     *
     * @return 表名
     */
    public String name() {
        return name;
    }

    /**
     * 库名。
     *
     * @return 库名
     */
    public String schema() {
        return schema;
    }

    /**
     * 行格式。
     *
     * @return 行格式
     */
    public String rowFormat() {
        return rowFormat;
    }

    /**
     * 排序规则 id。
     *
     * @return 排序规则 id
     */
    public long collationId() {
        return collationId;
    }

    /**
     * 建表时的 MySQL 版本号。
     *
     * @return 版本号
     */
    public long mysqlVersionId() {
        return mysqlVersionId;
    }

    /**
     * 全部列（含系统列）。
     *
     * @return 列列表
     */
    public List<IbdColumn> columns() {
        return columns;
    }

    /**
     * 全部索引。
     *
     * @return 索引列表
     */
    public List<IbdIndex> indexes() {
        return indexes;
    }

    /**
     * 取用户列（剔除系统列与隐藏列）。
     *
     * <p>除了 {@code DB_TRX_ID} / {@code DB_ROLL_PTR} / {@code DB_ROW_ID} 这三个系统列，
     * 还要滤掉「隐藏列」—— 例如给全文索引用的 {@code FTS_DOC_ID}。
     * 它们确实存在于记录里（所以解析时不能少算字节），但不该出现在输出与建表语句里，
     * 否则表头会比 MySQL 里看到的多一列。</p>
     *
     * @return 用户列列表
     */
    public List<IbdColumn> userColumns() {
        List<IbdColumn> out = new ArrayList<>(columns.size());
        for (IbdColumn column : columns) {
            if (!column.systemColumn() && !column.hidden()) {
                out.add(column);
            }
        }
        return out;
    }

    /**
     * 取聚簇索引（主键索引）。
     *
     * <p>没有显式主键时 InnoDB 会自动造一个隐藏的 {@code GEN_CLUST_INDEX}，
     * 它在 SDI 里同样是 {@code type = 1}。万一 type 也没标对，就退回「索引 id 最小」
     * 的那个 —— InnoDB 的索引 id 按创建顺序分配，聚簇索引一定是最小的。</p>
     *
     * @return 聚簇索引；表里没有任何索引时返回 {@code null}
     */
    public IbdIndex clusteredIndex() {
        for (IbdIndex index : indexes) {
            if (index.primary()) {
                return index;
            }
        }
        IbdIndex smallest = null;
        for (IbdIndex index : indexes) {
            if (smallest == null || index.id() < smallest.id()) {
                smallest = index;
            }
        }
        return smallest;
    }

    /**
     * 表默认字符集名。
     *
     * @return 如 {@code utf8mb4}
     */
    public String charsetName() {
        return IbdColumn.charsetNameOf(collationId);
    }

    @Override
    public String toString() {
        return (schema == null || schema.isEmpty() ? "" : schema + ".") + name
                + " cols=" + columns.size() + " idx=" + indexes.size() + " row_format=" + rowFormat;
    }
}
