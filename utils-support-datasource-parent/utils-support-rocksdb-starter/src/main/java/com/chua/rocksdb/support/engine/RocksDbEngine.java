package com.chua.rocksdb.support.engine;

import com.chua.common.support.lang.datasource.search.DocumentStore;
import com.chua.common.support.lang.datasource.search.FulltextSearch;
import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.rocksdb.support.datasource.RocksDbEngineDataSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;
import org.rocksdb.DB;
import org.rocksdb.Options;
import org.rocksdb.RBatch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocksDB 引擎实现（嵌入式本地文件目录）。
 * <p>
 * RocksDB 为 LSM-Tree 键值数据库，本引擎不伪装 ORM，而是暴露真实领域 API：
 * 字节 KV（{@link #putBytes} / {@link #getBytes} / {@link #deleteBytes} / {@link #scanBytes}）、
 * 字符串 KV（{@link KvEngine}）、批量写入（{@link #writeBatch}）、
 * 文档存储（{@link DocumentStore}，JSON 序列化）、全文检索（{@link FulltextSearch}，倒排索引）。
 * Lambda 查询/存储等接口按语义显式拒绝（与 hbaseengine 同风格）。
 * SPI 键 {@code "rocksdb"}；数据源支持传入目录路径或现成 {@code DB} 实例。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("rocksdb")
public class RocksDbEngine extends AbstractEngine implements KvEngine, DocumentStore, FulltextSearch {

    /** 文档键前缀 */
    private static final String DOC_PREFIX = "DOC:";
    /** 全文倒排索引键前缀 */
    private static final String FTS_PREFIX = "FTS_";
    /** JSON 序列化器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 数据源未找到错误前缀 */
    private static final String ERROR_DATASOURCE_NOT_FOUND = "RocksDB 数据源未找到: ";

    /** 字符串键值映射表，键为数据源名称 */
    private final ConcurrentHashMap<String, Map<String, String>> stringStores = new ConcurrentHashMap<>();
    /** RocksDB 数据库映射表，键为数据源名称 */
    private final ConcurrentHashMap<String, DB> databases = new ConcurrentHashMap<>();

    /**
     * 添加一个 RocksDB 数据源。
     *
     * @param name 数据源名称
     * @param path 数据库目录路径
     * @return this
     */
    public RocksDbEngine addDataSource(String name, String path) {
        DB db;
        try {
            db = openDatabase(path);
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 打开失败: " + path, e);
        }
        register(name, db, path);
        return this;
    }

    /**
     * 添加一个已打开的 RocksDB 数据源。
     *
     * @param name 数据源名称
     * @param db   RocksDB 实例
     * @param path 数据库目录路径
     * @return this
     */
    public RocksDbEngine addDataSource(String name, DB db, String path) {
        register(name, db, path);
        return this;
    }

    /**
     * 注册数据源到引擎内部映射。
     *
     * @param name 数据源名称
     * @param db   RocksDB 实例
     * @param path 数据库目录路径
     * @return this
     */
    private RocksDbEngine register(String name, DB db, String path) {
        databases.put(name, db);
        stringStores.put(name, new ConcurrentHashMap<>());
        super.addDataSource(name, new RocksDbEngineDataSource(name, path, db));
        return this;
    }

    /**
     * 以默认选项打开 RocksDB 数据库。
     *
     * @param path 数据库目录路径
     * @return 打开的数据库实例
     * @throws RocksDBException 打开失败
     */
    private static DB openDatabase(String path) throws RocksDBException {
        RocksDB.loadLibrary();
        try (Options options = new Options()) {
            return RocksDB.open(options, path);
        }
    }

    /**
     * 获取 RocksDB 实例。
     *
     * @param name 数据源名称
     * @return 数据库实例
     */
    public DB getDB(String name) {
        return databases.get(name);
    }

    /**
     * 获取当前默认数据源对应的 RocksDB 实例。
     *
     * @return 数据库实例
     */
    private DB currentDB() {
        if (defaultDataSourceName == null) {
            if (databases.isEmpty()) {
                return null;
            }
            return databases.values().iterator().next();
        }
        return databases.get(defaultDataSourceName);
    }

    /**
     * 获取当前默认数据源对应的字符串存储。
     *
     * @return 字符串存储映射
     */
    private Map<String, String> currentStringStore() {
        if (defaultDataSourceName == null) {
            if (stringStores.isEmpty()) {
                return Collections.emptyMap();
            }
            return stringStores.values().iterator().next();
        }
        return stringStores.get(defaultDataSourceName);
    }

    // ==================== 字节 KV 领域 API ====================

    /**
     * 字节写入（真实 放入）。
     *
     * @param name    数据源名称
     * @param key     键（字节）
     * @param value   值（字节）
     * @return this
     */
    public RocksDbEngine putBytes(String name, byte[] key, byte[] value) {
        DB db = requireDB(name);
        try {
            db.put(key, value);
            return this;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB put 失败: " + new String(key, StandardCharsets.UTF_8), e);
        }
    }

    /**
     * 字节读取（真实 获取）。
     *
     * @param name 数据源名称
     * @param key  键（字节）
     * @return 值字节，不存在返回 空
     */
    public byte[] getBytes(String name, byte[] key) {
        DB db = requireDB(name);
        try {
            return db.get(key);
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB get 失败: " + new String(key, StandardCharsets.UTF_8), e);
        }
    }

    /**
     * 字节删除（真实 删除）。
     *
     * @param name 数据源名称
     * @param key  键（字节）
     * @return this
     */
    public RocksDbEngine deleteBytes(String name, byte[] key) {
        DB db = requireDB(name);
        try {
            db.delete(key);
            return this;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB delete 失败: " + new String(key, StandardCharsets.UTF_8), e);
        }
    }

    /**
     * 字节全量扫描（真实 扫描）。
     *
     * @param name 数据源名称
     * @return 键值对列表
     */
    public List<Map.Entry<byte[], byte[]>> scanBytes(String name) {
        DB db = requireDB(name);
        List<Map.Entry<byte[], byte[]>> rows = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seekToFirst(); iter.isValid(); iter.next()) {
                rows.add(Map.entry(iter.key(), iter.value()));
            }
        }
        return rows;
    }

    /**
     * 字节前缀扫描。
     *
     * @param name   数据源名称
     * @param prefix 键前缀（字节）
     * @return 匹配前缀的键值对列表
     */
    public List<Map.Entry<byte[], byte[]>> scanBytes(String name, byte[] prefix) {
        DB db = requireDB(name);
        List<Map.Entry<byte[], byte[]>> rows = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(prefix); iter.isValid() && startsWith(iter.key(), prefix); iter.next()) {
                rows.add(Map.entry(iter.key(), iter.value()));
            }
        }
        return rows;
    }

    /**
     * 原子批量写入。
     *
     * @param name 数据源名称
     * @param ops  批量操作列表，每项为 [op, key, value]（op: 0=put, 1=delete）
     * @return this
     */
    public RocksDbEngine writeBatch(String name, List<byte[][]> ops) {
        DB db = requireDB(name);
        try (RBatch batch = db.batch()) {
            for (byte[][] op : ops) {
                if (op[0][0] == 0) {
                    batch.put(op[1], op[2]);
                } else {
                    batch.delete(op[1]);
                }
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
            return this;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB writeBatch 失败", e);
        }
    }

    /**
     * 获取指定数据源的 RocksDB 实例（必须存在）。
     *
     * @param name 数据源名称
     * @return 数据库实例
     */
    private DB requireDB(String name) {
        DB db = databases.get(name);
        if (db == null) {
            throw new IllegalArgumentException(ERROR_DATASOURCE_NOT_FOUND + name);
        }
        return db;
    }

    /**
     * 判断字节数组是否以指定前缀开头。
     *
     * @param data   数据
     * @param prefix 前缀
     * @return true 表示匹配
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    // ==================== KvEngine 字符串 KV 实现 ====================

    @Override
    /** 获取 */
    public String get(String key) {
        return currentStringStore().get(key);
    }

    @Override
    /** 放入 */
    public void put(String key, String value) {
        Map<String, String> store = currentStringStore();
        if (store instanceof ConcurrentHashMap<String, String> c) {
            if (value == null) {
                c.remove(key);
            } else {
                c.put(key, value);
            }
        }
    }

    @Override
    /** 判断键是否存在 */
    public boolean containsKey(String key) {
        return currentStringStore().containsKey(key);
    }

    @Override
    /** 删除 */
    public boolean delete(String key) {
        Map<String, String> store = currentStringStore();
        if (store instanceof ConcurrentHashMap<String, String> c) {
            return c.remove(key) != null;
        }
        return false;
    }

    @Override
    /** 递增 */
    public long incr(String key) {
        Map<String, String> store = currentStringStore();
        long newValue = 1L;
        if (store instanceof ConcurrentHashMap<String, String> c) {
            String old = c.get(key);
            if (old != null) {
                try {
                    newValue = Long.parseLong(old) + 1;
                } catch (NumberFormatException ignored) {
                    newValue = 1L;
                }
            }
            c.put(key, String.valueOf(newValue));
        }
        return newValue;
    }

    @Override
    /** 查找前缀 */
    public Map<String, String> findAllByPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        currentStringStore().forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                result.put(k, v);
            }
        });
        return result;
    }

    // ==================== DocumentStore 文档实现 ====================

    @Override
    /** 插入 */
    @SuppressWarnings("unchecked")
    public <T> T insert(String collection, T document) {
        DB db = currentDB();
        if (db == null) {
            throw new IllegalStateException("RocksDB 数据源未连接");
        }
        Map<String, Object> docMap = toDocumentMap(document);
        String id = String.valueOf(docMap.get("id"));
        String key = DOC_PREFIX + collection + ":" + id;
        try {
            db.put(key.getBytes(StandardCharsets.UTF_8), toJson(docMap).getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 文档插入失败: " + key, e);
        }
        buildFtsIndex(db, collection, docMap);
        return document;
    }

    @Override
    /** 查找byid */
    @SuppressWarnings("unchecked")
    public <T> T findById(String collection, Object id, Class<T> documentClass) {
        DB db = currentDB();
        if (db == null) {
            return null;
        }
        String key = DOC_PREFIX + collection + ":" + id;
        try {
            byte[] value = db.get(key.getBytes(StandardCharsets.UTF_8));
            if (value == null) {
                return null;
            }
            return fromJson(new String(value, StandardCharsets.UTF_8), documentClass);
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 文档读取失败: " + key, e);
        }
    }

    @Override
    /** 更新 */
    @SuppressWarnings("unchecked")
    public <T> T update(String collection, Object id, T document) {
        DB db = currentDB();
        if (db == null) {
            return null;
        }
        String key = DOC_PREFIX + collection + ":" + id;
        try {
            byte[] existing = db.get(key.getBytes(StandardCharsets.UTF_8));
            if (existing == null) {
                return null;
            }
            Map<String, Object> docMap = toDocumentMap(document);
            db.put(key.getBytes(StandardCharsets.UTF_8), toJson(docMap).getBytes(StandardCharsets.UTF_8));
            removeFtsIndex(db, collection, new String(existing, StandardCharsets.UTF_8));
            buildFtsIndex(db, collection, docMap);
            return document;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 文档更新失败: " + key, e);
        }
    }

    @Override
    /** 删除 */
    public boolean delete(String collection, Object id) {
        DB db = currentDB();
        if (db == null) {
            return false;
        }
        String key = DOC_PREFIX + collection + ":" + id;
        try {
            byte[] existing = db.get(key.getBytes(StandardCharsets.UTF_8));
            if (existing == null) {
                return false;
            }
            db.delete(key.getBytes(StandardCharsets.UTF_8));
            removeFtsIndex(db, collection, new String(existing, StandardCharsets.UTF_8));
            return true;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 文档删除失败: " + key, e);
        }
    }

    @Override
    /** 查找全部 */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(String collection, Class<T> documentClass) {
        DB db = currentDB();
        if (db == null) {
            return Collections.emptyList();
        }
        byte[] prefix = (DOC_PREFIX + collection + ":").getBytes(StandardCharsets.UTF_8);
        List<T> results = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(prefix); iter.isValid() && startsWith(iter.key(), prefix); iter.next()) {
                byte[] value = iter.value();
                results.add(fromJson(new String(value, StandardCharsets.UTF_8), documentClass));
            }
        }
        return results;
    }

    // ==================== FulltextSearch 全文检索实现 ====================

    @Override
    /** 创建全文索引 */
    public <T> void createFulltextIndex(Class<T> entityClass, String... fieldNames) {
        // 全文索引在 insert/update 时自动构建，此方法为空操作
    }

    @Override
    /** 搜索 */
    @SuppressWarnings("unchecked")
    public <T> List<T> search(String query, Class<T> entityClass) {
        return search(query, entityClass, Integer.MAX_VALUE);
    }

    @Override
    /** 搜索 */
    @SuppressWarnings("unchecked")
    public <T> List<T> search(String query, Class<T> entityClass, int limit) {
        DB db = currentDB();
        if (db == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        String collection = resolveCollection(entityClass);
        byte[] ftsKey = (FTS_PREFIX + collection + ":" + normalizeToken(query)).getBytes(StandardCharsets.UTF_8);
        List<T> results = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(ftsKey); iter.isValid() && startsWith(iter.key(), ftsKey) && results.size() < limit; iter.next()) {
                String docKey = new String(iter.value(), StandardCharsets.UTF_8);
                byte[] docValue = db.get(docKey.getBytes(StandardCharsets.UTF_8));
                if (docValue != null) {
                    results.add(fromJson(new String(docValue, StandardCharsets.UTF_8), entityClass));
                }
            }
        }
        return results;
    }

    @Override
    /** 删除全文索引 */
    public <T> void dropFulltextIndex(Class<T> entityClass, String... fieldNames) {
        DB db = currentDB();
        if (db == null) {
            return;
        }
        String collection = resolveCollection(entityClass);
        byte[] prefix = (FTS_PREFIX + collection + ":").getBytes(StandardCharsets.UTF_8);
        try (RocksIterator iter = db.newIterator()) {
            List<byte[]> keys = new ArrayList<>();
            for (iter.seek(prefix); iter.isValid() && startsWith(iter.key(), prefix); iter.next()) {
                keys.add(iter.key());
            }
            try (RBatch batch = db.batch()) {
                for (byte[] key : keys) {
                    batch.delete(key);
                }
                try (WriteOptions writeOptions = new WriteOptions()) {
                    db.write(writeOptions, batch);
                }
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 全文索引删除失败: " + collection, e);
        }
    }

    // ==================== 全文索引辅助方法 ====================

    /**
     * 为文档构建全文倒排索引。
     *
     * @param db         数据库实例
     * @param collection 集合名
     * @param docMap     文档映射
     */
    private void buildFtsIndex(DB db, String collection, Map<String, Object> docMap) {
        try (RBatch batch = db.batch()) {
            for (Map.Entry<String, Object> entry : docMap.entrySet()) {
                if (entry.getValue() instanceof String text) {
                    for (String token : tokenize(text)) {
                        byte[] ftsKey = (FTS_PREFIX + collection + ":" + token).getBytes(StandardCharsets.UTF_8);
                        byte[] docKey = (DOC_PREFIX + collection + ":" + docMap.get("id")).getBytes(StandardCharsets.UTF_8);
                        batch.put(ftsKey, docKey);
                    }
                }
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 全文索引构建失败: " + collection, e);
        }
    }

    /**
     * 移除文档的全文倒排索引。
     *
     * @param db         数据库实例
     * @param collection 集合名
     * @param json       文档 JSON
     */
    private void removeFtsIndex(DB db, String collection, String json) {
        try {
            Map<String, Object> docMap = MAPPER.readValue(json, Map.class);
            byte[] docKey = (DOC_PREFIX + collection + ":" + docMap.get("id")).getBytes(StandardCharsets.UTF_8);
            try (RBatch batch = db.batch()) {
                for (Map.Entry<String, Object> entry : docMap.entrySet()) {
                    if (entry.getValue() instanceof String text) {
                        for (String token : tokenize(text)) {
                            byte[] ftsKey = (FTS_PREFIX + collection + ":" + token).getBytes(StandardCharsets.UTF_8);
                            batch.delete(ftsKey);
                        }
                    }
                }
                try (WriteOptions writeOptions = new WriteOptions()) {
                    db.write(writeOptions, batch);
                }
            }
        } catch (Exception ignored) {
            // 索引清理失败不影响主流程
        }
    }

    /**
     * 分词（简单按空格/标点切分，转小写）。
     *
     * @param text 文本
     * @return 分词结果列表
     */
    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String part : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /**
     * 规范化检索词（小写）。
     *
     * @param token 检索词
     * @return 规范化结果
     */
    private static String normalizeToken(String token) {
        return token.toLowerCase();
    }

    /**
     * 解析实体类对应的集合名（驼峰转下划线）。
     *
     * @param entityClass 实体类
     * @param <T> 实体类型
     * @return 集合名
     */
    private static <T> String resolveCollection(Class<T> entityClass) {
        return AbstractEngine.resolveTableName(entityClass);
    }

    // ==================== 文档序列化辅助方法 ====================

    /**
     * 将通用对象转换为文档映射。
     *
     * @param source 源对象
     * @return 文档映射
     */
    private static Map<String, Object> toDocumentMap(Object source) {
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        try {
            return MAPPER.convertValue(source, Map.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("不支持的文档类型: " + source.getClass().getName(), e);
        }
    }

    /**
     * 将文档映射序列化为 JSON。
     *
     * @param docMap 文档映射
     * @return JSON 字符串
     */
    private static String toJson(Map<String, Object> docMap) {
        try {
            return MAPPER.writeValueAsString(docMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文档序列化失败", e);
        }
    }

    /**
     * 将 JSON 反序列化为目标类型。
     *
     * @param json          JSON 字符串
     * @param documentClass 目标类型
     * @param <T>           目标泛型
     * @return 目标类型实例
     */
    @SuppressWarnings("unchecked")
    private static <T> T fromJson(String json, Class<T> documentClass) {
        if (documentClass == Map.class) {
            try {
                return documentClass.cast(MAPPER.readValue(json, Map.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("文档反序列化失败", e);
            }
        }
        try {
            return MAPPER.readValue(json, documentClass);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("文档反序列化失败: " + documentClass.getName(), e);
        }
    }

    // ==================== 接口语义：显式拒绝 ====================

    /**
     * RocksDB 无 ORM：请使用 putBytes()/getBytes()/insert() 等真实领域 API。
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException(
                "RocksDbEngine 不支持内存存储/ORM。请使用 putBytes()/getBytes()/insert() 等真实领域 API。");
    }

    /**
     * RocksDB 无 SQL 查询：请使用 scanBytes() 或 查找全部()。
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        throw new UnsupportedOperationException(
                "RocksDB 无 SQL 查询。请使用 scanBytes() 或 findAll(collection, class) 真实 API。");
    }

    /**
     * RocksDB 无 更新：请使用 update() 文档 API 或 放入 覆盖写。
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        throw new UnsupportedOperationException(
                "RocksDB 无 UPDATE。覆盖写使用 putBytes() 相同键即可，文档更新使用 update()。");
    }

    /**
     * RocksDB 无 SQL 删除：请使用 deleteBytes() 或 删除()。
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        throw new UnsupportedOperationException(
                "RocksDB 无 SQL DELETE。请使用 deleteBytes() 或 delete(collection, id) 真实 API。");
    }

    /** 关闭所有数据源连接 */
    @Override
    public void close() {
        for (DB db : databases.values()) {
            try {
                db.close();
            } catch (Exception ignored) {
                // 忽略单个数据源关闭异常
            }
        }
        databases.clear();
        stringStores.clear();
        dataSources.clear();
    }
}
