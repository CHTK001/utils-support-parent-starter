package com.chua.ibd.support.innodb;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 Java 的 {@code .ibd} 读取门面：一行代码拿到表结构与全部行数据。
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (IbdTableReader reader = IbdTableReader.open(new File("actor.ibd"))) {
 *     System.out.println(reader.definition());
 *     List<Map<String, Object>> rows = reader.readAll();
 * }
 * }</pre>
 *
 * <p><b>零外部依赖</b>：不调 Python、不调 ibd2sql、不连 MySQL Server，
 * 只用 JDK 自带的 {@code Inflater}（解 SDI 的 zlib）和 Jackson（解 SDI 的 JSON，
 * 该依赖已由 {@code utils-support-common-starter} 传递进来）。</p>
 *
 * <p>取行数据的做法是「扫出该索引的所有叶子页 → 页内按 {@code REC_NEXT} 链遍历」，
 * 而不是顺着 B+ 树从根往下走：省掉节点指针记录的解析，也不怕中间层页损坏。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdTableReader implements Closeable {

    /**
    * 表空间。
    */
    private final IbdTablespace tablespace;

    /**
    * 表定义。
    */
    private final IbdTableDefinition definition;

    /**
    * 溢出页读取器。
    */
    private final IbdLobReader lobReader;

    /**
    * 渲染 {@code TIMESTAMP} 用的时区。
    */
    private final ZoneId zone;

    /**
    * 被标记删除（未 purge）的记录数。
    */
    private long deletedRows;

    /**
    * 打开表空间并读出表定义。
    *
    * @param file {@code .ibd} 文件
    * @return 读取器，使用后需关闭
    * @throws IOException 文件无效或没有表定义
    */
    public static IbdTableReader open(File file) throws IOException {
        return open(file, ZoneId.systemDefault());
    }

    /**
    * 打开表空间并读出表定义。
    *
    * @param file {@code .ibd} 文件
    * @param zone 渲染 {@code TIMESTAMP} 用的时区
    * @return 读取器，使用后需关闭
    * @throws IOException 文件无效或没有表定义
    */
    public static IbdTableReader open(File file, ZoneId zone) throws IOException {
        IbdTablespace tablespace = IbdTablespace.open(file);
        try {
            IbdTableDefinition definition = IbdSdiReader.readTableDefinition(tablespace);
            return new IbdTableReader(tablespace, definition, zone);
        } catch (IOException e) {
            tablespace.close();
            throw e;
        }
    }

    /**
    * 构造读取器。
    *
    * @param tablespace 表空间
    * @param definition 表定义
    * @param zone       时区
    */
    private IbdTableReader(IbdTablespace tablespace, IbdTableDefinition definition, ZoneId zone) {
        this.tablespace = tablespace;
        this.definition = definition;
        this.lobReader = new IbdLobReader(tablespace);
        this.zone = zone;
    }

    /**
    * 表定义。
    *
    * @return 表定义
    */
    public IbdTableDefinition definition() {
        return definition;
    }

    /**
    * 表空间。
    *
    * @return 表空间
    */
    public IbdTablespace tablespace() {
        return tablespace;
    }

    /**
    * 输出用的列名（用户列，按建表顺序；已剔除 {@code DB_TRX_ID} 等系统列）。
    *
    * @return 列名列表
    */
    public List<String> columnNames() {
        List<IbdColumn> columns = definition.userColumns();
        List<String> names = new ArrayList<>(columns.size());
        for (IbdColumn column : columns) {
            names.add(column.name());
        }
        return names;
    }

    /**
    * 输出用的列定义（用户列，按建表顺序）。
    *
    * @return 列定义列表
    */
    public List<IbdColumn> columns() {
        return definition.userColumns();
    }

    /**
    * 读出全部行数据（按聚簇索引）。
    *
    * @return 行数据，列名 → 值；{@code null} 表示 SQL NULL
    * @throws IOException 读取失败
    */
    public List<Map<String, Object>> readAll() throws IOException {
        IbdIndex clustered = definition.clusteredIndex();
        if (clustered == null) {
            throw new IOException("表定义里没有任何索引，无法定位数据: " + definition.name());
        }
        return readAll(clustered);
    }

    /**
    * 读出某个索引的全部行数据。
    *
    * @param index 索引（通常用聚簇索引，它包含所有列）
    * @return 行数据
    * @throws IOException 读取失败
    */
    public List<Map<String, Object>> readAll(IbdIndex index) throws IOException {
        List<IbdColumn> recordColumns = index.columns();
        List<String> outputColumns = columnNames();
        int pageSize = tablespace.pageSize();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long pageNo : tablespace.leafPagesOf(index.id())) {
            byte[] page = tablespace.readPage(pageNo);
            IbdRecordCursor cursor = new IbdRecordCursor(page, pageSize);
            for (IbdRecordCursor.Header header : IbdRecordCursor.chain(page, pageSize)) {
                if (header.recordType() == IbdConstants.REC_STATUS_ORDINARY
                        && (header.infoBits() & 0x1) != 0) {
                    deletedRows++;
                    continue;
                }
                if (!header.userRecord()) {
                    continue;
                }
                Map<String, Object> record = cursor.at(header.origin()).readRow(recordColumns, lobReader);
                Map<String, Object> row = new LinkedHashMap<>();
                for (String name : outputColumns) {
                    row.put(name, record.get(name));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
    * 估算行数（把叶子页记录数加起来，不做字段解码，用于日志与进度）。
    *
    * @return 估算行数
    * @throws IOException 读取失败
    */
    public long estimateRowCount() throws IOException {
        IbdIndex clustered = definition.clusteredIndex();
        if (clustered == null) {
            return 0L;
        }
        long total = 0;
        for (long pageNo : tablespace.leafPagesOf(clustered.id())) {
            total += IbdPageHeader.recordCount(tablespace.readPage(pageNo));
        }
        return total;
    }

    /**
    * 被标记删除（未 purge）的记录数，由 {@link #readAll()} 统计。
    *
    * @return 记录数
    */
    public long deletedRows() {
        return deletedRows;
    }

    /**
    * 渲染 {@code TIMESTAMP} 用的时区。
    *
    * @return 时区
    */
    public ZoneId zone() {
        return zone;
    }

    @Override
    public void close() throws IOException {
        tablespace.close();
    }
}
