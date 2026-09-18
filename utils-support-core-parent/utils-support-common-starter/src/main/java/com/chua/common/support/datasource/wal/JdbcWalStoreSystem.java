package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
* JDBC 文件存储引擎。
 */
public class JdbcWalStoreSystem implements WalStoreSystem<String> {

    private final WalStoreConfig config;
    /** joinStrategy名称 */
    private String joinStrategyName;
    final SegmentWalLog[] walLogs;
    private final Map<String, AtomicLong> rowIdCounters = new ConcurrentHashMap<>();
    private final AtomicLong totalRecords = new AtomicLong(0);
    private volatile boolean closed = false;

    /**
     * 构造方法，创建 JdbcWalStoreSystem 实例。
     *
     * @param config 配置，不允许为 null
     * @param joinStrategyName joinStrategy名称，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    public JdbcWalStoreSystem(WalStoreConfig config, String joinStrategyName) throws IOException {
        this.config = config;
        this.joinStrategyName = joinStrategyName != null ? joinStrategyName : "none";
        this.walLogs = new SegmentWalLog[config.shardCount()];
        Path walDir = config.baseDir().resolve("_wal");
        Files.createDirectories(walDir);
        Files.createDirectories(config.baseDir().resolve("_schemas"));
        for (int i = 0; i < config.shardCount(); i++) {
            WalConfig c = WalConfig.builder().walDir(walDir).namespace(config.namespace()+"-"+i)
                    .impl(WalConfig.WalImpl.SEGMENT).syncOnWrite(false)
                    .fsyncBatchSize(config.flushBatchSize()).fsyncBatchIntervalMs(config.flushIntervalMs())
                    .maxSegmentBytes(config.segmentBytes()).maxRecordsPerSegment(10_000_000).build();
            walLogs[i] = (SegmentWalLog) WalFactory.open(c);
        }
    }

    @Override public String type() { return "jdbc"; }
    @Override public Path baseDir() { return config.baseDir(); }
    @Override public int shardCount() { return config.shardCount(); }
    @Override public StoreType storeType() { return StoreType.JDBC; }

    @Override
    public long append(String key, byte[] payload) throws IOException {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        int idx = Math.abs(key.hashCode()) % config.shardCount();
        long lsn = walLogs[idx].append((byte) 0x04, payload == null ? new byte[0] : payload);
        totalRecords.incrementAndGet();
        return lsn;
    }

    @Override
    public Optional<byte[]> get(String key) throws IOException { return Optional.empty(); }
    @Override public boolean contains(String key) { return false; }
    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to) throws IOException { return Collections.emptyList(); }
    @Override
    public List<Map.Entry<String, byte[]>> range(String from, String to, int offset, int limit) throws IOException { return Collections.emptyList(); }
    @Override
    public boolean delete(String key) throws IOException { return false; }
    @Override
    public void rebuildIndex() throws IOException {}
    @Override
    public void compact() throws IOException {
        for (SegmentWalLog log : walLogs) {
            try {
                log.purgeCheckpointed(1);
            } catch (Exception e) {
            }
        }
    }
    @Override
    public int size() { return (int) totalRecords.get(); }
    @Override
    public List<WalSegmentInfo> listSegments() throws IOException {
        List<WalSegmentInfo> all = new ArrayList<>();
        for (SegmentWalLog log : walLogs) {
            all.addAll(log.listSegments());
        }
        return all;
    }
    @Override
    public void close() throws IOException {
        closed = true;
        for (SegmentWalLog log : walLogs) { try { log.close(); } catch (IOException ignored) {} }
    }
    @Override
    public void appendBatch(List<WalStoreSystem.WalAppendItem<String>> items) throws IOException {
        for (WalStoreSystem.WalAppendItem<String> item : items) {
            append(item.key(), item.payload());
        }
    }

    // ==================== DDL/DML ====================

    /**
     * 获取Schema。
     *
     * @param tableName 表名称，不允许为 null
     * @return 结果列表，无数据时为空列表
     * @throws IOException 当执行过程不满足前置条件时
     */
    public List<ColumnDef> getSchema(String tableName) throws IOException {
        Path f = config.baseDir().resolve("_schemas").resolve(tableName + ".json");
        if (!Files.exists(f)) {
            return Collections.emptyList();
        }
        String json = Files.readString(f);
        Map<String, Object> schema = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) schema.get("columns");
        if (cols == null) {
            return Collections.emptyList();
        }
        List<ColumnDef> result = new ArrayList<>(cols.size());
        for (Map<String, Object> col : cols) {
            result.add(new ColumnDef(
                    (String) col.get("name"),
                    (String) col.get("type"),
                    Boolean.TRUE.equals(col.get("nullable"))
            ));
        }
        return result;
    }

    /**
     * 创建表。
     *
     * @param tableName 表名称，不允许为 null
     * @param columns 方法入参 columns
     * @throws IOException 当执行过程不满足前置条件时
     */
    public void createTable(String tableName, List<ColumnDef> columns) throws IOException {
        Path f = config.baseDir().resolve("_schemas").resolve(tableName + ".json");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", tableName);
        schema.put("columns", columns);
        Files.writeString(f, com.chua.common.support.lang.json.Json.toJson(schema));
    }

    /**
     * 插入。
     *
     * @param table 表，不允许为 null
     * @param row 行，不允许为 null
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public long insert(String table, Map<String, Object> row) throws IOException {
        String rowId = table + "_" + rowIdCounters.computeIfAbsent(table, k -> new AtomicLong(0)).incrementAndGet();
        return insertWithId(table, rowId, row);
    }

    /**
     * 插入WithID。
     *
     * @param table 表，不允许为 null
     * @param rowId 行ID，不允许为 null
     * @param row 行，不允许为 null
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public long insertWithId(String table, String rowId, Map<String, Object> row) throws IOException {
        return append(rowId, encodeRow(row));
    }

    /**
     * 更新。
     *
     * @param table 表，不允许为 null
     * @param rowId 行ID，不允许为 null
     * @param updates 方法入参 updates
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public int update(String table, String rowId, Map<String, Object> updates) throws IOException {
        delete(rowId);
        insertWithId(table, rowId, updates);
        return 1;
    }

    /**
     * 查询。
     *
     * @param sql SQL，不允许为 null
     * @param params 参数，不允许为 null
     * @return 结果列表，无数据时为空列表
     * @throws IOException 当执行过程不满足前置条件时
     */
    public List<Map<String, Object>> query(String sql, Object... params) throws IOException {
        return new SimpleSqlParser(this).parseSelect(sql, params);
    }

    /**
     * 执行。
     *
     * @param sql SQL，不允许为 null
     * @param params 参数，不允许为 null
     * @return 结果数值
     * @throws IOException 当执行过程不满足前置条件时
     */
    public int execute(String sql, Object... params) throws IOException {
        return new SimpleSqlParser(this).parseDml(sql, params);
    }

    public void setJoinStrategy(String name) { this.joinStrategyName = name; }
    public String joinStrategy() { return joinStrategyName; }

    // ==================== 内部 ====================

    /**
     * 编码行。
     *
     * @param row 行，不允许为 null
     * @return 结果值
     */
    private byte[] encodeRow(Map<String, Object> row) {
        row = new LinkedHashMap<>(row);
        ByteBuffer bb = ByteBuffer.allocate(256);
        bb.putInt(row.size());
        for (Map.Entry<String, Object> e : row.entrySet()) {
            byte[] nb = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] vb = String.valueOf(e.getValue()).getBytes(StandardCharsets.UTF_8);
            bb.putInt(nb.length);
            bb.put(nb);
            bb.putInt(vb.length);
            bb.put(vb);
        }
        byte[] result = new byte[bb.position()];
        bb.position(0);
        bb.get(result);
        return result;
    }

    /**
     * 解码值。
     *
     * @param rowId 行ID，不允许为 null
     * @param payload 方法入参 payload
     * @return 对象 对象
     */
    @SuppressWarnings("unchecked")
    public Object decodeValue(String rowId, byte[] payload) {
        if (payload == null || payload.length < 4) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int colCount = bb.getInt();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < colCount; i++) {
            if (bb.remaining() < 4) {
                break;
            }
            int nl = bb.getInt();
            if (nl <= 0 || nl > bb.remaining()) {
                break;
            }
            byte[] nb = new byte[nl];
            bb.get(nb);
            String colName = new String(nb, StandardCharsets.UTF_8);
            if (bb.remaining() < 4) {
                break;
            }
            int vl = bb.getInt();
            byte[] vb = vl > 0 ? new byte[vl] : new byte[0];
            if (vl > 0) {
                bb.get(vb);
            }
            row.put(colName, parseVal(vb));
        }
        return row;
    }

    /**
     * 解析Val。
     *
     * @param b 方法入参 b
     * @return 对象 对象
     */
    private Object parseVal(byte[] b) {
        if (b.length == 0) {
            return null;
        }
        String s = new String(b, StandardCharsets.UTF_8);
        try {
            if (s.contains(".")) {
                return Double.parseDouble(s);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            return com.chua.common.support.lang.json.Json.fromJson(s, Object.class);
        } catch (Exception ignored) {
            // fall through
        }
        return s;
    }

    public record ColumnDef(String name, String type, boolean nullable) {}

    /**
     * 创建。
     *
     * @param baseDir base目录，不允许为 null
     * @return JdbcWalStoreSystem 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    public static JdbcWalStoreSystem create(Path baseDir) throws IOException {
        return create(baseDir, "default", "none");
    }

    /**
     * 创建。
     *
     * @param baseDir base目录，不允许为 null
     * @param namespace 方法入参 namespace
     * @param joinStrategy 方法入参 joinStrategy
     * @return JdbcWalStoreSystem 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    public static JdbcWalStoreSystem create(Path baseDir, String namespace, String joinStrategy) throws IOException {
        return new JdbcWalStoreSystem(new WalStoreEnvDetector().detect(baseDir, namespace), joinStrategy);
    }
}
