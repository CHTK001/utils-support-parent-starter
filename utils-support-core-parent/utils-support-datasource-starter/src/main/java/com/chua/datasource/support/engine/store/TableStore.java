package com.chua.datasource.support.engine.store;

import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 引擎内存表的承载实现（SPI）。
 *
 * <p>默认实现 {@link HeapTableStore} 把行放在进程堆内的列表里，语义与历史行为一致；
 * {@link MappedFileTableStore} 把整表行序列化进 JDK 共享内存文件（{@code FileChannel.map}），
 * 堆内只保留偏移索引，用于在堆内存受限时承载更多行。算子仍在本地执行，
 * 存储只承载「整表快照」。</p>
 *
 * <h2>实现契约</h2>
 * <ol>
 *   <li>{@link #open(String)} 对同一表名必须返回<b>同一个列表实例</b>：引擎用引用身份判断
 *       表是否被整体替换（{@code entry.getValue() == data}），返回新实例会让索引与删除路径失效。</li>
 *   <li>返回的列表必须可变，引擎会在其上执行 add/set/remove/clear/removeIf。</li>
 *   <li>{@link #closeTable(String)} 表示该表已从引擎摘除（逐出 / TTL 回收），实现应释放其资源；
 *       不保证之后仍能读到旧内容。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi(TableStore.SPI_NAME)
public interface TableStore extends AutoCloseable {

    /**
     * SPI 名称，供 {@code ServiceProvider#getExtension(String)} 取实现。
     */
    String SPI_NAME = "table-store";

    /**
     * 存储实现名（与 {@code @Spi} 的 value 一致）。
     *
     * @return 实现名
     */
    String name();

    /**
     * 判断两个存储实例是否可以互相替代（切换时可直接沿用旧实例）。
     *
     * <p>默认按实现名判定：同名实现视为等价，不做迁移。实现名不足以区分实例时
     * （例如 {@link MappedFileTableStore} 的目录是实例级配置），实现必须覆写本方法，
     * 否则「显式换一个目录」会被当成空操作而静默失效。</p>
     *
     * @param other 另一个存储实例
     * @return {@code true} 表示可直接沿用当前实例
     */
    default boolean sameStore(TableStore other) {
        return this == other || other != null && name().equals(other.name());
    }

    /**
     * 打开（或创建）一张表。
     *
     * @param table 表名
     * @return 该表的行列表（可变，同一表名恒返回同一实例）
     */
    List<Object> open(String table);

    /**
     * 用给定行整体替换一张表的全部内容。
     *
     * <p>默认实现复用 {@link #open(String)} 返回的稳定实例做内容覆盖，
     * 从而保证「引擎持有的列表引用」在整表替换后依然有效；堆实现可以直接
     * 采纳传入实例（等价于历史 {@code Map#put} 语义）。</p>
     *
     * @param table 表名
     * @param rows  新内容，可能与 {@link #open(String)} 返回同一实例
     */
    default void replace(String table, List<Object> rows) {
        List<Object> target = open(table);
        if (target == rows) {
            return;
        }
        target.clear();
        target.addAll(rows);
    }

    /**
     * 关闭并释放一张表。
     *
     * @param table 表名
     */
    default void closeTable(String table) {
    }

    /**
     * 关闭存储本身，释放全部表。
     */
    @Override
    default void close() {
    }
}
