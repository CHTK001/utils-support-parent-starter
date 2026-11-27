package com.chua.datasource.support.engine.store;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认的堆内表存储：每张表就是一个进程堆内的列表。
 *
 * <p>该实现的语义与引擎历史行为完全一致（{@code ConcurrentHashMap<String, List<?>>} +
 * {@link Wraps#list()}），不改变行的存放位置与访问方式。切换存储能力默认关闭，
 * 只有显式指定 {@code mmap} 才会走到 {@link MappedFileTableStore}。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi(value = HeapTableStore.SPI_NAME, order = Integer.MIN_VALUE)
@SpiDefault
public class HeapTableStore implements TableStore {

    /**
     * SPI 名称。
     */
    public static final String SPI_NAME = "heap";

    /**
     * 表名 → 行列表。
     */
    private final Map<String, List<Object>> tables = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return SPI_NAME;
    }

    @Override
    public List<Object> open(String table) {
        return tables.computeIfAbsent(table, k -> Wraps.list());
    }

    /**
     * 直接采纳传入实例，与历史 {@code ConcurrentHashMap#put} 语义完全一致。
     *
     * @param table 表名
     * @param rows  新内容
     */
    @Override
    public void replace(String table, List<Object> rows) {
        tables.put(table, rows);
    }

    @Override
    public void closeTable(String table) {
        tables.remove(table);
    }

    @Override
    public void close() {
        tables.clear();
    }
}
