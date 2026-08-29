package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * JDBC 文件存储引擎。
 */
@Slf4j
public class JdbcWalStoreSystem extends AbstractWalStoreSystem<String> {

    private static final byte OP_JDBC = 0x04;
    private String joinStrategyName;

    public JdbcWalStoreSystem(WalStoreConfig config, String joinStrategyName) throws IOException {
        super(config);
        this.joinStrategyName = joinStrategyName != null ? joinStrategyName : "none";
        Files.createDirectories(config.baseDir().resolve("_schemas"));
    }

    @Override
    protected byte opType() { return OP_JDBC; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        int colCount = ByteBuffer.wrap(payload).getInt();
        if (colCount <= 0) return null;
        ByteBuffer bb = ByteBuffer.wrap(payload);
        bb.getInt(); // skip colCount
        int nameLen = bb.getInt();
        if (nameLen <= 0 || nameLen > bb.remaining()) return null;
        byte[] nameBytes = new byte[nameLen];
        bb.get(nameBytes);
        return new String(nameBytes, StandardCharsets.UTF_8);
    }

    @Override
    protected Object decodeValue(String rowId, byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int colCount = bb.getInt();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < colCount; i++) {
            if (bb.remaining() < 4) break;
            int nameLen = bb.getInt();
            if (nameLen <= 0 || nameLen > bb.remaining()) break;
            byte[] nb = new byte[nameLen]; bb.get(nb);
            String colName = new String(nb, StandardCharsets.UTF_8);
            if (bb.remaining() < 4) break;
            int valLen = bb.getInt();
            byte[] vb = valLen > 0 ? new byte[valLen] : new byte[0];
            if (valLen > 0) bb.get(vb);
            row.put(colName, parseJsonValue(vb));
        }
        return row;
    }

    @Override
    public int shardCount() { return config.shardCount(); }

    // ==================== DDL ====================

    public void createTable(String tableName, List<ColumnDef> columns) throws IOException {
        Path schemaFile = config.baseDir().resolve("_schemas").resolve(tableName + ".json");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", tableName);
        schema.put("columns", columns);
        Files.writeString(schemaFile, com.chua.common.support.lang.json.Json.toJson(schema));
        log.info("[jdbc-store] created table: {}", tableName);
    }

    public List<ColumnDef> getSchema(String tableName) throws IOException {
        Path schemaFile = config.baseDir().resolve("_schemas").resolve(tableName + ".json");
        if (!Files.exists(schemaFile)) return Collections.emptyList();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = com.chua.common.support.lang.json.Json.fromJson(
                Files.readString(schemaFile), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) schema.get("columns");
        if (cols == null) return Collections.emptyList();
        List<ColumnDef> result = new ArrayList<>();
        for (Map<String, Object> c : cols) {
            result.add(new ColumnDef((String) c.get("name"), (String) c.get("type"),
                    Boolean.TRUE.equals(c.get("nullable"))));
        }
        return result;
    }

    public record ColumnDef(String name, String type, boolean nullable) {}

    // ==================== DML ====================

    public long insert(String table, Map<String, Object> row) throws IOException {
        String rowId = generateRowId(table);
        return insertWithId(table, rowId, row);
    }

    public long insertWithId(String table, String rowId, Map<String, Object> row) throws IOException {
        return append(rowId, encodeJdbcPayload(row));
    }

    public int update(String table, String rowId, Map<String, Object> updates) throws IOException {
        delete(rowId);
        insertWithId(table, rowId, updates);
        return 1;
    }

    @Override
    public boolean delete(String rowId) throws IOException {
        return super.delete(rowId);
    }

    // ==================== SQL 查询 ====================

    public List<Map<String, Object>> query(String sql, Object... params) throws IOException {
        return new SimpleSqlParser(this).parseSelect(sql, params);
    }

    public int execute(String sql, Object... params) throws IOException {
        return new SimpleSqlParser(this).parseDml(sql, params);
    }

    public void setJoinStrategy(String strategyName) {
        this.joinStrategyName = strategyName;
        log.info("[jdbc-store] join strategy set to: {}", strategyName);
    }

    public String joinStrategy() { return joinStrategyName; }

    // ==================== 内部工具 ====================

    private final Map<String, AtomicLong> rowId_counters = new ConcurrentHashMap<>();

    private String generateRowId(String table) {
        return table + "_" + rowId_counters
                .computeIfAbsent(table, k -> new AtomicLong(0)).incrementAndGet();
    }

    private byte[] encodeJdbcPayload(Map<String, Object> row) {
        ByteBuffer bb = ByteBuffer.allocate(1024);
        bb.putInt(row.size());
        for (Map.Entry<String, Object> e : row.entrySet()) {
            byte[] nb = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] vb = serializeValue(e.getValue());
            bb.putInt(nb.length); bb.put(nb);
            bb.putInt(vb.length); bb.put(vb);
        }
        byte[] result = new byte[bb.position()];
        bb.position(0); bb.get(result);
        return result;
    }

    private byte[] serializeValue(Object value) {
        if (value == null) return new byte[0];
        if (value instanceof String s) return s.getBytes(StandardCharsets.UTF_8);
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private Object parseJsonValue(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        String s = new String(bytes, StandardCharsets.UTF_8);
        try {
            if (s.contains(".")) return Double.parseDouble(s);
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {}
        try { return com.chua.common.support.lang.json.Json.fromJson(s, Object.class); }
        catch (Exception ignored) {}
        return s;
    }

    @Override
    public StoreType storeType() { return StoreType.JDBC; }

    public static JdbcWalStoreSystem create(Path baseDir) throws IOException {
        return create(baseDir, "default", "none");
    }

    public static JdbcWalStoreSystem create(Path baseDir, String namespace, String joinStrategy)
            throws IOException {
        WalStoreConfig config = new WalStoreEnvDetector().detect(baseDir, namespace);
        return new JdbcWalStoreSystem(config, joinStrategy);
    }
}
