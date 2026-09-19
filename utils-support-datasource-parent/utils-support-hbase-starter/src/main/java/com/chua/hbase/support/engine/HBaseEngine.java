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
 * Lambda 查询/存储等接口按语义显式拒绝（与 prometheusengine 同风格）。
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
            ownedDataSources.remove(name);
            wrapped = new HBaseEngineDataSource(name, dataSource.url(), conn);
        } else if (source instanceof String quorum) {
            ownedDataSources.add(name);
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
     * 由引擎自建的连接名称集合（close 时仅关闭这些，外部传入的连接不归本引擎管理）。
     */
    private final java.util.Set<String> ownedDataSources =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * 便捷添加数据源（ZooKeeper 地址），由本引擎创建并负责关闭连接。
     *
     * @param name   数据源名称
     * @param quorum 形如 {@code 172.16.0.40:2181}
     * @return this
     */
    public HBaseEngine addDataSource(String name, String quorum) {
        ownedDataSources.add(name);
        dataSources.put(name, (EngineDataSource<Object>) (Object)
                new HBaseEngineDataSource(name, quorum, connect(quorum)));
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 由 quorum 串创建真实连接。
     *
     * @param quorum ZK 地址串，形如 {@code host:port}
     * @return 连接的结果
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

    /**
     * 连接。
     *
     * @return 连接 对象
     */
    private Connection conn() {
        EngineDataSource<Object> ds = defaultDataSourceName == null
                ? null : dataSources.get(defaultDataSourceName);
        if (ds == null) {
            if (dataSources.size() == 1) {
                ds = dataSources.values().iterator().next();
            } else if (dataSources.size() > 1) {
                throw new IllegalStateException(
                        "未指定默认 HBase 数据源且存在多个数据源: " + dataSources.keySet());
            }
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
     * 写入一行（真实 放入）。
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
     * 读取一行（真实 获取）。
     *
     * @param table  表名
     * @param rowKey 行键
     * @param family 列族
     * @return 列限定符 -> 字符串值；行不存在返回空 映射
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
     * 全表扫描（真实 扫描），可选行键前缀过滤。
     *
     * @param table      表名
     * @param family     列族，为空表示全部列族
     * @param rowPrefix  行键前缀，可为 空
     * @return 行列表，每行含 {@code __row} 键为行键
     */
    public java.util.List<Map<String, String>> scan(String table, String family, String rowPrefix) {
        try (Table t = conn().getTable(TableName.valueOf(table));
             ResultScanner scanner = t.getScanner(buildScan(family, rowPrefix))) {
            java.util.List<Map<String, String>> out = new java.util.ArrayList<>();
            for (Result r : scanner) {
                if (r.isEmpty()) {
                    continue;
                }
                Map<String, String> m = toMap(r, family);
                m.put("__row", Bytes.toString(r.getRow()));
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("HBase scan 失败: " + table, e);
        }
    }

    /**
     * 删除一行（真实 删除）。
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
     * 构建 扫描（含可选前缀）。
     * @param family family，为空表示全部列族
     * @param rowPrefix row前缀
     * @return 构建扫描的结果
     */
    private static Scan buildScan(String family, String rowPrefix) {
        Scan s = new Scan();
        if (family != null && !family.isEmpty()) {
            s.addFamily(Bytes.toBytes(family));
        }
        if (rowPrefix != null && !rowPrefix.isEmpty()) {
            byte[] start = Bytes.toBytes(rowPrefix);
            byte[] stop = prefixStopRow(start);
            s.withStartRow(start);
            if (stop != null) {
                s.setStopRow(stop);
            }
        }
        return s;
    }

    /**
     * 前缀末字节+1 截断得到 扫描 停止row（纯字节运算，不做字符串往返以免破坏非 ASCII 前缀）。
     *
     * @param start 起始row字节
     * @return 停止row；前缀全为 0xFF（无后继）时返回 空 表示不设边界
     */
    private static byte[] prefixStopRow(byte[] start) {
        byte[] b = start.clone();
        for (int i = b.length - 1; i >= 0; i--) {
            if (b[i] != (byte) 0xFF) {
                b[i]++;
                return java.util.Arrays.copyOf(b, i + 1);
            }
        }
        return null;
    }

    /**
     * 结果 转 映射（family 为空取全部列族，否则仅取指定列族下的字符串值）。
     * @param r r
     * @param family family
     * @return 转为映射的结果
     */
    private static Map<String, String> toMap(Result r, String family) {
        Map<String, String> m = new LinkedHashMap<>();
        if (r.isEmpty()) {
            return m;
        }
        boolean allFamilies = family == null || family.isEmpty();
        for (org.apache.hadoop.hbase.Cell cell : r.rawCells()) {
            String fam = Bytes.toString(cell.getFamilyArray(), cell.getFamilyOffset(), cell.getFamilyLength());
            if (allFamilies || family.equals(fam)) {
                String q = Bytes.toString(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength());
                String v = Bytes.toString(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
                if (q != null && v != null) {
                    m.put(allFamilies ? fam + ":" + q : q, v);
                }
            }
        }
        return m;
    }

    // ==================== 接口语义：显式拒绝 ====================

    /**
     * HBase 无 SQL：请使用 放入/获取/扫描/删除row 领域 API。
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException(
                "HBaseEngine 不支持内存存储/ORM。请使用 put()/scan() 等真实领域 API。");
    }

    /**
     * HBase 无 SQL：请使用 扫描()。
     */
    @Override
    protected <T> java.util.List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        throw new UnsupportedOperationException(
                "HBase 无 SQL 查询。请使用 scan(table, family, rowPrefix) 真实 API。");
    }

    /**
     * HBase 无 更新。
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        throw new UnsupportedOperationException(
                "HBase 无 UPDATE。覆盖写使用 put() 相同行键即可。");
    }

    /**
     * HBase 无 SQL 删除：请使用 删除row()。
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        throw new UnsupportedOperationException(
                "HBase 无 SQL DELETE。请使用 deleteRow(table, rowKey) 真实 API。");
    }

    /**
     * 关闭所有由本引擎自建的连接（外部传入的连接不关闭），并清空数据源
    */
    @Override
    public void close() {
        for (Map.Entry<String, EngineDataSource<Object>> entry : dataSources.entrySet()) {
            if (ownedDataSources.contains(entry.getKey())) {
                entry.getValue().close();
            }
        }
        ownedDataSources.clear();
        dataSources.clear();
    }
}
