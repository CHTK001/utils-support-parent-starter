package com.chua.hbase.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.hbase.support.datasource.HBaseEngineDataSource;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.util.Bytes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HBase 引擎实现（真实 ZooKeeper 连接）。
 * <p>
 * HBase 为无 SQL 的宽表模型，本引擎不伪装 ORM，而是暴露真实领域 API：
 * {@link #put} / {@link #get} / {@link #scan} / {@link #deleteRow} / 建表。
 * Lambda 查询/存储等接口按语义显式拒绝（与 PrometheusEngine 同风格）。
 * SPI 键 {@code "hbase"}；数据源支持传入 {@code quorum:port} 串或现成 Connection。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("hbase")
public class HBaseEngine extends AbstractEngine {

    /**
     * 默认列族
     */
    public static final String DEFAULT_FAMILY = "cf";

    /**
     * 添加数据源（HBase Connection 或 ZK 地址串）。
     *
     * @param name       数据源名称
     * @param dataSource 数据源封装
     * @param <T>        底层类型
     * @return this
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        Object source = dataSource.getSource();
        HBaseEngineDataSource wrapped;
        if (source instanceof Connection conn) {
            wrapped = new HBaseEngineDataSource(name, dataSource.url(), conn);
        } else if (source instanceof String quorum) {
            wrapped = new HBaseEngineDataSource(name, quorum, connect(quorum));
        } else {
            throw new IllegalArgumentException("HBaseEngine 仅支持 Connection 或 quorum 地址串");
        }
        dataSources.put(name, (EngineDataSource<Object>) (Object) wrapped);
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 便捷添加数据源（ZooKeeper 地址）。
     *
     * @param name   数据源名称
     * @param quorum 形如 {@code 172.16.0.40:2181}
     * @return this
     */
    public HBaseEngine addDataSource(String name, String quorum) {
        dataSources.put(name, (EngineDataSource<Object>) (Object)
                new HBaseEngineDataSource(name, quorum, connect(quorum)));
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 由 quorum 串创建真实连接。
     */
    private static Connection connect(String quorum) {
        String host = quorum;
        String port = "2181";
        int colon = quorum.indexOf(':');
        if (colon > 0) {
            host = quorum.substring(0, colon);
            port = quorum.substring(colon + 1);
        }
        Configuration conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", host);
        conf.set("hbase.zookeeper.property.clientPort", port);
        try {
            return ConnectionFactory.createConnection(conf);
        } catch (Exception e) {
            throw new IllegalStateException("HBase 连接失败: " + quorum, e);
        }
    }

    private Connection conn() {
        EngineDataSource<Object> ds = defaultDataSourceName == null
                ? null : dataSources.get(defaultDataSourceName);
        if (ds == null && !dataSources.isEmpty()) {
            ds = dataSources.values().iterator().next();
        }
        Object raw = ds;
        if (!(raw instanceof HBaseEngineDataSource h)) {
            throw new IllegalStateException("未配置 HBase 数据源");
        }
        return h.getSource();
    }

    // ==================== 真实领域 API ====================

    /**
     * 建表（已存在则跳过）。
     *
     * @param table  表名
     * @param family 列族
     * @return this
     */
    public HBaseEngine createTable(String table, String family) {
        try (var admin = conn().getAdmin()) {
            TableName tn = TableName.valueOf(table);
            if (!admin.tableExists(tn)) {
                admin.createTable(TableDescriptorBuilder.newBuilder(tn)
                        .setColumnFamily(ColumnFamilyDescriptorBuilder.of(family)).build());
            }
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("HBase 建表失败: " + table, e);
        }
    }

    /**
     * 写入一行（真实 Put）。
     *
     * @param table    表名
     * @param rowKey   行键
     * @param family   列族
     * @param values   列限定符 -> 字符串值
     * @return this
     */
    public HBaseEngine put(String table, String rowKey, String family, Map<String, String> values) {
        try (Table t = conn().getTable(TableName.valueOf(table))) {
            Put p = new Put(Bytes.toBytes(rowKey));
            values.forEach((q, v) -> p.addColumn(Bytes.toBytes(family), Bytes.toBytes(q), Bytes.toBytes(v)));
            t.put(p);
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("HBase put 失败: " + table + "/" + rowKey, e);
        }
    }

    /**
     * 读取一行（真实 Get）。
     *
     * @param table  表名
     * @param rowKey 行键
     * @param family 列族
     * @return 列限定符 -> 字符串值；行不存在返回空 Map
     */
    public Map<String, String> get(String table, String rowKey, String family) {
        try (Table t = conn().getTable(TableName.valueOf(table))) {
            Result r = t.get(new Get(Bytes.toBytes(rowKey)));
            return toMap(r, family);
        } catch (Exception e) {
            throw new IllegalStateException("HBase get 失败: " + table + "/" + rowKey, e);
        }
    }

    /**
     * 全表扫描（真实 Scan），可选行键前缀过滤。
     *
     * @param table      表名
     * @param family     列族
     * @param rowPrefix  行键前缀，可为 null
     * @return 行列表，每行含 {@code __row} 键为行键
     */
    public java.util.List<Map<String, String>> scan(String table, String family, String rowPrefix) {
        try (Table t = conn().getTable(TableName.valueOf(table));
             ResultScanner scanner = t.getScanner(buildScan(family, rowPrefix))) {
            java.util.List<Map<String, String>> out = new java.util.ArrayList<>();
            for (Result r : scanner) {
                Map<String, String> m = toMap(r, family);
                if (!m.isEmpty()) {
                    m.put("__row", Bytes.toString(r.getRow()));
                    out.add(m);
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("HBase scan 失败: " + table, e);
        }
    }

    /**
     * 删除一行（真实 Delete）。
     *
     * @param table  表名
     * @param rowKey 行键
     * @return this
     */
    public HBaseEngine deleteRow(String table, String rowKey) {
        try (Table t = conn().getTable(TableName.valueOf(table))) {
            t.delete(new Delete(Bytes.toBytes(rowKey)));
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("HBase delete 失败: " + table + "/" + rowKey, e);
        }
    }

    /**
     * 构建 Scan（含可选前缀）。
     */
    private static Scan buildScan(String family, String rowPrefix) {
        Scan s = new Scan();
        s.addFamily(Bytes.toBytes(family));
        if (rowPrefix != null && !rowPrefix.isEmpty()) {
            s.withStartRow(Bytes.toBytes(rowPrefix));
            s.setStopRow(Bytes.toBytes(incrementPrefix(rowPrefix)));
        }
        return s;
    }

    /**
     * 前缀+1 用于 Scan stopRow（包含式边界处理）。
     */
    private static String incrementPrefix(String prefix) {
        byte[] b = Bytes.toBytes(prefix);
        for (int i = b.length - 1; i >= 0; i--) {
            if (b[i] != (byte) 0xFF) {
                b[i]++;
                return Bytes.toString(b, 0, i + 1);
            }
        }
        return prefix + "\0";
    }

    /**
     * Result 转 Map（仅取指定列族下的字符串值）。
     */
    private static Map<String, String> toMap(Result r, String family) {
        Map<String, String> m = new LinkedHashMap<>();
        if (r.isEmpty()) {
            return m;
        }
        for (org.apache.hadoop.hbase.Cell cell : r.rawCells()) {
            String fam = Bytes.toString(cell.getFamilyArray(), cell.getFamilyOffset(), cell.getFamilyLength());
            if (family.equals(fam)) {
                String q = Bytes.toString(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength());
                String v = Bytes.toString(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
                if (q != null && v != null) {
                    m.put(q, v);
                }
            }
        }
        return m;
    }

    // ==================== 接口语义：显式拒绝 ====================

    /**
     * HBase 无 SQL：请使用 put/get/scan/deleteRow 领域 API。
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException(
                "HBaseEngine 不支持内存存储/ORM。请使用 put()/scan() 等真实领域 API。");
    }

    /**
     * HBase 无 SQL：请使用 scan()。
     */
    @Override
    protected <T> java.util.List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        throw new UnsupportedOperationException(
                "HBase 无 SQL 查询。请使用 scan(table, family, rowPrefix) 真实 API。");
    }

    /**
     * HBase 无 UPDATE。
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        throw new UnsupportedOperationException(
                "HBase 无 UPDATE。覆盖写使用 put() 相同行键即可。");
    }

    /**
     * HBase 无 SQL DELETE：请使用 deleteRow()。
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        throw new UnsupportedOperationException(
                "HBase 无 SQL DELETE。请使用 deleteRow(table, rowKey) 真实 API。");
    }

    /** 关闭所有数据源连接 */
    @Override
    public void close() {
        for (EngineDataSource<?> ds : dataSources.values()) {
            ds.close();
        }
        dataSources.clear();
    }
}
