package com.chua.ibd.support.innodb;

import java.util.List;

/**
 * InnoDB 索引定义，来自 SDI JSON 的 {@code dd_object.indexes[]}。
 *
 * <p>{@link #columns()} 的顺序就是<b>记录里的字段顺序</b>，这一点非常关键：
 * 聚簇索引是「主键列 + {@code DB_TRX_ID} + {@code DB_ROLL_PTR} + 其余列」，
 * 二级索引是「索引列 + 主键列」，都和 {@code CREATE TABLE} 里的书写顺序不同。
 * 按 DDL 顺序去读记录会全线错位。</p>
 *
 * <p>索引 id 与根页号藏在 {@code se_private_data} 里，形如
 * {@code id=154;root=4;space_id=2;table_id=1064;trx_id=1296;}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdIndex {

    /**
     * 主键索引类型值（{@code dd::Index::IT_PRIMARY}）。
     */
    public static final int TYPE_PRIMARY = 1;

    /**
     * 唯一索引类型值（{@code dd::Index::IT_UNIQUE}）。
     */
    public static final int TYPE_UNIQUE = 2;

    /**
     * 普通索引类型值（{@code dd::Index::IT_MULTIPLE}）。
     */
    public static final int TYPE_MULTIPLE = 3;

    /**
     * 索引名。
     */
    private final String name;

    /**
     * 索引类型（1 主键 / 2 唯一 / 3 普通）。
     */
    private final int type;

    /**
     * 索引 id，与索引页头里的 {@code PAGE_INDEX_ID} 对应。
     */
    private final long id;

    /**
     * 根页页号。
     */
    private final long rootPage;

    /**
     * 表 id。
     */
    private final long tableId;

    /**
     * 记录里的字段顺序。
     */
    private final List<IbdColumn> columns;

    /**
     * 是否隐藏索引（如外键自动建的索引）。
     */
    private final boolean hidden;

    /**
     * 是否可见。
     */
    private final boolean visible;

    /**
     * 索引算法（2 = BTREE）。
     */
    private final int algorithm;

    /**
     * 构造索引定义。
     *
     * @param name      索引名
     * @param type      索引类型
     * @param id        索引 id
     * @param rootPage  根页页号
     * @param tableId   表 id
     * @param columns   记录里的字段顺序
     * @param hidden    是否隐藏
     * @param visible   是否可见
     * @param algorithm 索引算法
     */
    public IbdIndex(String name, int type, long id, long rootPage, long tableId,
                    List<IbdColumn> columns, boolean hidden, boolean visible, int algorithm) {
        this.name = name;
        this.type = type;
        this.id = id;
        this.rootPage = rootPage;
        this.tableId = tableId;
        this.columns = List.copyOf(columns);
        this.hidden = hidden;
        this.visible = visible;
        this.algorithm = algorithm;
    }

    /**
     * 索引名。
     *
     * @return 索引名
     */
    public String name() {
        return name;
    }

    /**
     * 索引类型。
     *
     * @return 1 主键 / 2 唯一 / 3 普通
     */
    public int type() {
        return type;
    }

    /**
     * 索引 id。
     *
     * @return 索引 id
     */
    public long id() {
        return id;
    }

    /**
     * 根页页号。
     *
     * @return 根页页号
     */
    public long rootPage() {
        return rootPage;
    }

    /**
     * 表 id。
     *
     * @return 表 id
     */
    public long tableId() {
        return tableId;
    }

    /**
     * 记录里的字段顺序。
     *
     * @return 字段列表
     */
    public List<IbdColumn> columns() {
        return columns;
    }

    /**
     * 是否隐藏索引。
     *
     * @return 隐藏返回 true
     */
    public boolean hidden() {
        return hidden;
    }

    /**
     * 是否可见。
     *
     * @return 可见返回 true
     */
    public boolean visible() {
        return visible;
    }

    /**
     * 索引算法。
     *
     * @return 算法编号
     */
    public int algorithm() {
        return algorithm;
    }

    /**
     * 是否为主键索引。
     *
     * @return 主键返回 true
     */
    public boolean primary() {
        return type == TYPE_PRIMARY;
    }

    @Override
    public String toString() {
        return name + "(id=" + id + ",root=" + rootPage + ")";
    }
}
