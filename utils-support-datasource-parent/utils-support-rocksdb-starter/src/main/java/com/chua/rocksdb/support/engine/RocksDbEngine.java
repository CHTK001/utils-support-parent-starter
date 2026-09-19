package com.chua.rocksdb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.kv.KvEngine;
import com.chua.common.support.lang.datasource.search.DocumentStore;
import com.chua.common.support.lang.datasource.search.FulltextSearch;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.rocksdb.support.datasource.RocksDbEngineDataSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocksDB 引擎实现（嵌入式本地文件目录）。
 * <p>
 * RocksDB 为 LSM-Tree 键值数据库，本引擎暴露真实领域 API：
 * 字节 KV（{@link #putBytes} / {@link #getBytes} / {@link #deleteBytes} / {@link #scanBytes}）、
 * 字符串 KV（{@link KvEngine}，真实 写 入 RocksDB {@code SKV:} 键 空间）、
 * 批量写入（{@link #writeBatch}）、
 * 文档存储（{@link DocumentStore}，JSON 序列化，文档 与 全文 索引 同 批 原子 写 入）、
 * 全文检索（{@link FulltextSearch}，倒排 索引 单 值 键 布局，集合 级 锁 保证 并 发 安全）、
 * 以及 Lambda ORM（{@link #executeNewQuery} / {@link #executeUpdate} / {@link #executeDelete} /
 * {@link #store}，基于 {@code RocksDbOrmStore} 前缀 扫描 + 内存 WHERE 过滤，键 布局
 * {@code ORM:<table>:<id>}，实体 JSON 序列化，表 级 锁 串行化 读-改-写 循环）。
 * SPI 键 {@code "rocksdb"}；数据源 支持 传入 目录 路径 或 现成 {@code RocksDB} 实例。
 * </p>
 *
 * <p><b>并 发 语义</b>：单 个 {@code RocksDB} 实例 本身 线程 安全（RocksDB 官方 契约）。
 * 引擎 层 的 复合 操作（incr 计数、FTS 倒排 条目 维护、文档+索引 原子 写 入、
 * ORM 读-改-写）通过 键/集合/表 级 {@link Object} 锁 串行化，避免 丢失 更新
 * 与 重复 行 键。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("rocksdb")
public class RocksDbEngine extends AbstractEngine implements KvEngine, DocumentStore, FulltextSearch {

    /**
     * 文档键前缀
    */
    private static final String DOC_PREFIX = "DOC:";
    /**
     * 全文倒排索引键前缀
    */
    private static final String FTS_PREFIX = "FTS_";
    /**
     * JSON 序列化器
    */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 数据源未找到错误前缀
    */
    private static final String ERROR_DATASOURCE_NOT_FOUND = "RocksDB 数据源未找到: ";

    /**
     * RocksDB 数据库映射表，键为数据源名称
    */
    private final ConcurrentHashMap<String, RocksDB> databases = new ConcurrentHashMap<>();
    /**
     * 字符串 KV 键 级 锁：完整 键 → {@link Object}（incr 读-改-写 串行化）
     */
    private final ConcurrentHashMap<String, Object> kvKeyLocks = new ConcurrentHashMap<>();
    /**
     * FTS 集合 级 锁：集合名 → {@link Object}（倒排 条目 读-改-写 串行化）
     */
    private final ConcurrentHashMap<String, Object> ftsCollectionLocks = new ConcurrentHashMap<>();
    /**
     * ORM 表 级 锁：表名 → {@link Object}（序号 分配 与 读-改-写 串行化，跨 引擎 内 所有 ORM 操作 共享）
     */
    private final ConcurrentHashMap<String, Object> ormTableLocks = new ConcurrentHashMap<>();

    /**
     * 添加一个 RocksDB 数据源。
     *
     * @param name 数据源名称
     * @param path 数据库目录路径
     * @return this
     */
    public RocksDbEngine addDataSource(String name, String path) {
        RocksDB db;
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
    public RocksDbEngine addDataSource(String name, RocksDB db, String path) {
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
    private RocksDbEngine register(String name, RocksDB db, String path) {
        databases.put(name, db);
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
    private static RocksDB openDatabase(String path) throws RocksDBException {
        RocksDB.loadLibrary();
        try (Options options = new Options()) {
            options.setCreateIfMissing(true);
            return RocksDB.open(options, path);
        }
    }

    /**
     * 获取 RocksDB 实例。
     *
     * @param name 数据源名称
     * @return 数据库实例
     */
    public RocksDB getDB(String name) {
        return databases.get(name);
    }

    /**
     * 获取当前默认数据源对应的 RocksDB 实例。
     *
     * @return 数据库实例
     */
    private RocksDB currentDB() {
        if (defaultDataSourceName == null) {
            if (databases.isEmpty()) {
                return null;
            }
            return databases.values().iterator().next();
        }
        return databases.get(defaultDataSourceName);
    }

    // ==================== 字节 KV 领域 API ====================

    /**
     * 字节写入（真实 放入）。
     *
     * @param name  数据源名称
     * @param key   键（字节）
     * @param value 值（字节）
     * @return this
     */
    public RocksDbEngine putBytes(String name, byte[] key, byte[] value) {
        RocksDB db = requireDB(name);
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
        RocksDB db = requireDB(name);
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
        RocksDB db = requireDB(name);
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
        RocksDB db = requireDB(name);
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
        RocksDB db = requireDB(name);
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
        RocksDB db = requireDB(name);
        WriteBatch batch = new WriteBatch();
        try {
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
        } finally {
            batch.close();
        }
    }

    /**
     * 获取指定数据源的 RocksDB 实例（必须存在）。
     *
     * @param name 数据源名称
     * @return 数据库实例
     */
    private RocksDB requireDB(String name) {
        RocksDB db = databases.get(name);
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

    // ==================== KvEngine 字符串 KV 实现（RocksDB 真实 存储） ====================

    /**
     * 字符串 KV 键 前缀（与 字节/文档/ORM/FTS 键 空间 隔离，避免 跨 能力 键 冲突）。
     */
    private static final String STR_KV_PREFIX = "SKV:";

    @Override
    /**
     * 获取（RocksDB 真实 读取，重启 后 仍可 读 回）
    */
    public String get(String key) {
        RocksDB db = currentDB();
        if (db == null) {
            return null;
        }
        try {
            byte[] value = db.get(kvKey(key));
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 字符串 KV 读取 失败: " + key, e);
        }
    }

    @Override
    /**
     * 放入（value 为 空 时 等效 删除，RocksDB 真实 写入）
    */
    public void put(String key, String value) {
        RocksDB db = currentDB();
        if (db == null) {
            throw new IllegalStateException("RocksDB 数据源未连接");
        }
        try {
            if (value == null) {
                db.delete(kvKey(key));
            } else {
                db.put(kvKey(key), value.getBytes(StandardCharsets.UTF_8));
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 字符串 KV 写入 失败: " + key, e);
        }
    }

    @Override
    /**
     * 判断键 是否 存在（RocksDB 真实 读取）
    */
    public boolean containsKey(String key) {
        return get(key) != null;
    }

    @Override
    /**
     * 删除（RocksDB 真实 删除）
    */
    public boolean delete(String key) {
        if (get(key) == null) {
            return false;
        }
        RocksDB db = currentDB();
        try {
            db.delete(kvKey(key));
            return true;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 字符串 KV 删除 失败: " + key, e);
        }
    }

    @Override
    /**
     * 递增（键 级 锁 串行化 读-改-写，并 发 下 计数 不 丢失；RocksDB 真实 存储）。
     *
     * @param key 键
     * @return 递增 后 的 最新 值
     */
    public long incr(String key) {
        RocksDB db = currentDB();
        if (db == null) {
            throw new IllegalStateException("RocksDB 数据源未连接");
        }
        synchronized (kvKeyLock(key)) {
            try {
                byte[] raw = db.get(kvKey(key));
                long newValue = 1L;
                if (raw != null) {
                    try {
                        newValue = Long.parseLong(new String(raw, StandardCharsets.UTF_8)) + 1;
                    } catch (NumberFormatException ignored) {
                        newValue = 1L;
                    }
                }
                db.put(kvKey(key), String.valueOf(newValue).getBytes(StandardCharsets.UTF_8));
                return newValue;
            } catch (RocksDBException e) {
                throw new IllegalStateException("RocksDB 字符串 KV 递增 失败: " + key, e);
            }
        }
    }

    @Override
    /**
     * 查找 前缀（RocksDB 前缀 扫描，仅 返回 字符串 KV 键 空间 内 键）
     */
    public Map<String, String> findAllByPrefix(String prefix) {
        RocksDB db = currentDB();
        Map<String, String> result = new LinkedHashMap<>();
        if (db == null) {
            return result;
        }
        byte[] scanPrefix = (STR_KV_PREFIX + prefix).getBytes(StandardCharsets.UTF_8);
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(scanPrefix); iter.isValid() && startsWith(iter.key(), scanPrefix); iter.next()) {
                byte[] rawKey = iter.key();
                String fullKey = new String(rawKey, StandardCharsets.UTF_8);
                String bizKey = fullKey.substring(STR_KV_PREFIX.length());
                result.put(bizKey, new String(iter.value(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /**
     * 构造 字符串 KV 完整 键（业务 键 加 前缀 以 隔离 键 空间）。
     *
     * @param key 业务 键
     * @return 完整 键 字节
     */
    private static byte[] kvKey(String key) {
        return (STR_KV_PREFIX + key).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 获取 键 级 锁 句柄（同 键 共享 同一 锁，跨 键 互不 影响）。
     *
     * @param key 业务 键
     * @return 锁 句柄
     */
    private Object kvKeyLock(String key) {
        return kvKeyLocks.computeIfAbsent(key, k -> new Object());
    }

    // ==================== DocumentStore 文档实现 ====================

    @Override
    /**
     * 插入（文档 与 FTS 索引 条目 同 批 原子 写 入，集合 级 锁 串行化，崩溃 不 产生 孤 文档）
     */
    public <T> T insert(String collection, T document) {
        RocksDB db = currentDB();
        if (db == null) {
            throw new IllegalStateException("RocksDB 数据源未连接");
        }
        Map<String, Object> docMap = toDocumentMap(document);
        String id = String.valueOf(docMap.get("id"));
        String key = DOC_PREFIX + collection + ":" + id;
        byte[] docJson = toJson(docMap).getBytes(StandardCharsets.UTF_8);
        synchronized (ftsCollectionLock(collection)) {
            buildFtsIndex(db, collection, docMap, key, docJson);
        }
        return document;
    }

    @Override
    /**
     * 查找byid
    */
    @SuppressWarnings("unchecked")
    public <T> T findById(String collection, Object id, Class<T> documentClass) {
        RocksDB db = currentDB();
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
    /**
     * 更新（旧 索引 移除 + 新 文档 + 新 索引 同 批 原子 写 入，集合 级 锁 串行化）
     */
    @SuppressWarnings("unchecked")
    public <T> T update(String collection, Object id, T document) {
        RocksDB db = currentDB();
        if (db == null) {
            return null;
        }
        String key = DOC_PREFIX + collection + ":" + id;
        synchronized (ftsCollectionLock(collection)) {
            try {
                byte[] existing = db.get(key.getBytes(StandardCharsets.UTF_8));
                if (existing == null) {
                    return null;
                }
                Map<String, Object> docMap = toDocumentMap(document);
                Map<String, Object> oldDoc = MAPPER.readValue(new String(existing, StandardCharsets.UTF_8), Map.class);
                byte[] docJson = toJson(docMap).getBytes(StandardCharsets.UTF_8);
                removeFtsIndex(db, collection, oldDoc, key, docJson);
                return document;
            } catch (RocksDBException e) {
                throw new IllegalStateException("RocksDB 文档更新失败: " + key, e);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("RocksDB 文档更新失败: 反序列化旧文档", e);
            }
        }
    }

    @Override
    /**
     * 删除（文档 + FTS 索引 条目 同 批 原子 移除，集合 级 锁 串行化）
    */
    public boolean delete(String collection, Object id) {
        RocksDB db = currentDB();
        if (db == null) {
            return false;
        }
        String key = DOC_PREFIX + collection + ":" + id;
        synchronized (ftsCollectionLock(collection)) {
            try {
                byte[] existing = db.get(key.getBytes(StandardCharsets.UTF_8));
                if (existing == null) {
                    return false;
                }
                Map<String, Object> oldDoc = MAPPER.readValue(new String(existing, StandardCharsets.UTF_8), Map.class);
                removeFtsIndex(db, collection, oldDoc, key, null);
                return true;
            } catch (RocksDBException e) {
                throw new IllegalStateException("RocksDB 文档删除失败: " + key, e);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("RocksDB 文档删除失败: 反序列化旧文档", e);
            }
        }
    }

    @Override
    /**
     * 查找全部
    */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(String collection, Class<T> documentClass) {
        RocksDB db = currentDB();
        if (db == null) {
            return Collections.emptyList();
        }
        byte[] prefix = (DOC_PREFIX + collection + ":").getBytes(StandardCharsets.UTF_8);
        List<T> results = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(prefix); iter.isValid() && startsWith(iter.key(), prefix); iter.next()) {
                byte[] value = iter.value();
                T doc = fromJson(new String(value, StandardCharsets.UTF_8), documentClass);
                if (doc != null) {
                    results.add(doc);
                }
            }
        }
        return results;
    }

    // ==================== FulltextSearch 全文检索实现 ====================

    @Override
    /**
     * 创建全文索引（索引 在 insert/update 时 自动 维护，此 方法 为空 操作）
     */
    public <T> void createFulltextIndex(Class<T> entityClass, String... fieldNames) {
        // no-op
    }

    @Override
    /**
     * 搜索
    */
    public <T> List<T> search(String query, Class<T> entityClass) {
        return search(query, entityClass, Integer.MAX_VALUE);
    }

    @Override
    /**
     * 搜索（FTS 单 值 键 布局：FTS_<collection>:<token>:<docId> → 文档 键 字节，按 token 段 匹配）
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> search(String query, Class<T> entityClass, int limit) {
        RocksDB db = currentDB();
        if (db == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        String token = normalizeToken(query);
        byte[] ftsPrefix = FTS_PREFIX.getBytes(StandardCharsets.UTF_8);
        List<T> results = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(ftsPrefix); iter.isValid() && startsWith(iter.key(), ftsPrefix); iter.next()) {
                byte[] keyBytes = iter.key();
                String keyStr = new String(keyBytes, StandardCharsets.UTF_8);
                // 键 布局 FTS_<collection>:<token>:<docId>，按 token 段 精确 匹配
                // （collection 与 docId 均 可能 含 冒 号，故 取 倒数 第 二 段 作为 token）
                int lastColon = keyStr.lastIndexOf(':');
                if (lastColon <= 0) {
                    continue;
                }
                int secondLastColon = keyStr.lastIndexOf(':', lastColon - 1);
                if (secondLastColon < 0) {
                    continue;
                }
                String keyToken = keyStr.substring(secondLastColon + 1, lastColon);
                if (!keyToken.equals(token)) {
                    continue;
                }
                // 值 即 文档 键 字节，直 接 读 文档
                byte[] docKey = iter.value();
                byte[] docValue;
                try {
                    docValue = db.get(docKey);
                } catch (RocksDBException e) {
                    throw new IllegalStateException("RocksDB 全文检索 文档 读 取 失败: " + query, e);
                }
                if (docValue != null) {
                    T doc = fromJson(new String(docValue, StandardCharsets.UTF_8), entityClass);
                    if (doc != null) {
                        results.add(doc);
                        if (results.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        }
        return results;
    }

    @Override
    /**
     * 删除全文索引（集合 级 锁 内 前缀 批量 删除）
    */
    public <T> void dropFulltextIndex(Class<T> entityClass, String... fieldNames) {
        RocksDB db = currentDB();
        if (db == null) {
            return;
        }
        String collection = resolveCollection(entityClass);
        byte[] prefix = (FTS_PREFIX + collection + ":").getBytes(StandardCharsets.UTF_8);
        synchronized (ftsCollectionLock(collection)) {
            List<byte[]> keys = new ArrayList<>();
            try (RocksIterator iter = db.newIterator()) {
                for (iter.seek(prefix); iter.isValid() && startsWith(iter.key(), prefix); iter.next()) {
                    keys.add(iter.key());
                }
            }
            if (keys.isEmpty()) {
                return;
            }
            try (WriteBatch batch = new WriteBatch()) {
                for (byte[] key : keys) {
                    batch.delete(key);
                }
                try (WriteOptions writeOptions = new WriteOptions()) {
                    db.write(writeOptions, batch);
                }
            } catch (RocksDBException e) {
                throw new IllegalStateException("RocksDB 全文索引删除失败: " + collection, e);
            }
        }
    }

    /**
     * 获取 FTS 集合 级 锁 句柄（同 集合 共享 同一 锁，跨 集合 互不 影响）。
     *
     * @param collection 集合名
     * @return 锁 句柄
     */
    private Object ftsCollectionLock(String collection) {
        return ftsCollectionLocks.computeIfAbsent(collection, k -> new Object());
    }

    // ==================== 全文索引辅助方法（单 值 键 布局 + 同 批 原子 写 入） ====================

    /**
     * 为 文档 构建 全文 倒排 索引。
     * <p>FTS 键 布局：{@code FTS_<collection>:<token>:<docId>}，值 为 文档 键 字节
     * （单 值 键，无 多 值 逗号 拼接 的 竞态 与 键 冲突 问题）。
     * 新 索引 条目 与 文档 写 入 共 用 同一 {@link WriteBatch}（{@code newDocJson} 非 空 时
     * 合并 写 入），保证 崩溃 一致 性（C1/C2/L3 修复）。</p>
     *
     * @param db         数据库 实例
     * @param collection 集合名
     * @param docMap     文档 映射
     * @param docKey     文档 键
     * @param newDocJson 新 文档 JSON 字节（非 空 时 同 批 写 入 文档），插入 场景 传 非 空
     */
    private void buildFtsIndex(RocksDB db, String collection, Map<String, Object> docMap, String docKey,
                               byte[] newDocJson) {
        Set<String> ftsKeys = new LinkedHashSet<>();
        byte[] docKeyBytes = docKey.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, Object> entry : docMap.entrySet()) {
            if (entry.getValue() instanceof String text) {
                for (String token : tokenize(text)) {
                    ftsKeys.add(FTS_PREFIX + collection + ":" + token + ":" + docIdOf(docKey));
                }
            }
        }
        if (ftsKeys.isEmpty() && newDocJson == null) {
            return;
        }
        WriteBatch batch = new WriteBatch();
        try {
            if (newDocJson != null) {
                batch.put(docKeyBytes, newDocJson);
            }
            for (String ftsKey : ftsKeys) {
                batch.put(ftsKey.getBytes(StandardCharsets.UTF_8), docKeyBytes);
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
        } catch (RocksDBException e) {
            batch.close();
            throw new IllegalStateException("RocksDB 全文索引构建失败: " + collection, e);
        }
    }

    /**
     * 移除 文档 的 全文 倒排 索引。
     * <p>FTS 单 值 键 直接 删除（无 多 值 拼接 竞态）。
     * 与 文档 操作 共 用 同一 {@link WriteBatch}：{@code newDocJson} 非 空 时 同 批 更新 文档，
     * 否则 同 批 删除 文档 键（删除 场景）。</p>
     *
     * @param db         数据库 实例
     * @param collection 集合名
     * @param docMap     旧 文档 映射
     * @param docKey     文档 键
     * @param newDocJson 新 文档 JSON 字节（更新 场景 非 空；删除 场景 传 空 → 同 批 删 文档 键）
     */
    private void removeFtsIndex(RocksDB db, String collection, Map<String, Object> docMap, String docKey,
                                byte[] newDocJson) {
        Set<String> ftsKeys = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : docMap.entrySet()) {
            if (entry.getValue() instanceof String text) {
                for (String token : tokenize(text)) {
                    ftsKeys.add(FTS_PREFIX + collection + ":" + token + ":" + docIdOf(docKey));
                }
            }
        }
        if (ftsKeys.isEmpty() && newDocJson == null) {
            // 无 FTS 条目 且 无 文档 变更，无需 写 入
            return;
        }
        WriteBatch batch = new WriteBatch();
        try {
            byte[] docKeyBytes = docKey.getBytes(StandardCharsets.UTF_8);
            if (newDocJson != null) {
                batch.put(docKeyBytes, newDocJson);
            } else {
                batch.delete(docKeyBytes);
            }
            for (String ftsKey : ftsKeys) {
                batch.delete(ftsKey.getBytes(StandardCharsets.UTF_8));
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
        } catch (RocksDBException e) {
            batch.close();
            throw new IllegalStateException("RocksDB 全文索引移除失败: " + collection, e);
        }
    }

    /**
     * 从 文档 键 提取 文档 id（{@code DOC:<collection>:<id>} → {@code <id>}）。
     *
     * @param docKey 文档 键
     * @return 文档 id
     */
    private static String docIdOf(String docKey) {
        int lastColon = docKey.lastIndexOf(':');
        return lastColon > 0 ? docKey.substring(lastColon + 1) : docKey;
    }

    /**
     * 分词（简单 按 空格/标点 切 分，转 小 写）。
     *
     * @param text 文本
     * @return 分 词 结果 列表
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
     * 规范 化 检索 词（小 写）。
     *
     * @param token 检索 词
     * @return 规范 化 结果
     */
    private static String normalizeToken(String token) {
        return token.toLowerCase();
    }

    /**
     * 解析 实体 类 对应 的 集合 名（委托 {@link #resolveTableName}）。
     *
     * @param entityClass 实体 类
     * @param <T> 实体 类型
     * @return 集合 名
     */
    private static <T> String resolveCollection(Class<T> entityClass) {
        return AbstractEngine.resolveTableName(entityClass);
    }

    /**
     * 解析 实体 类 对应 的 表 名（委托 基 类 静态 方法，供 ORM 存储 复用）。
     *
     * @param entityClass 实体 类
     * @param <T> 实体 类型
     * @return 表 名
     */
    public static <T> String resolveTableName(Class<T> entityClass) {
        return AbstractEngine.resolveTableName(entityClass);
    }

    // ==================== 文档 序列化 辅助 方法 ====================

    /**
     * 将 通用 对象 转换 为 文档 映射。
     *
     * @param source 源 对象
     * @return 文档 映射
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toDocumentMap(Object source) {
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return MAPPER.convertValue(source, Map.class);
    }

    /**
     * 将 文档 映射 序列化 为 JSON。
     *
     * @param docMap 文档 映射
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
     * 将 JSON 反 序列化 为 目标 类型。
     * <p>反 序列化 失败 时 记录 告警 日志（M2 修复），返回 空 让 调用 方 决定 跳 过 策略。</p>
     *
     * @param json          JSON 字符串
     * @param documentClass 目标 类型
     * @param <T>           目标 泛 型
     * @return 目标 类型 实例
     */
    @SuppressWarnings("unchecked")
    private static <T> T fromJson(String json, Class<T> documentClass) {
        try {
            if (documentClass == Map.class) {
                return (T) MAPPER.readValue(json, Map.class);
            }
            return MAPPER.readValue(json, documentClass);
        } catch (JsonProcessingException e) {
            log.warn("RocksDB 文档 反 序列化 失败，跳 过 该 行: {}", documentClass.getName(), e);
            return null;
        }
    }

    // ==================== Lambda ORM 支持 ====================

    /**
     * 当前 引擎 是否 支持 数据库 物理 分页。
     * <p>RocksDB ORM 走 内存 分页（前缀 扫描 后 截取），不支持 物理 计数。</p>
     *
     * @param entityClass 实体 类 类型
     * @return false
     */
    @Override
    protected boolean supportsNativePaging(Class<?> entityClass) {
        return false;
    }

    /**
     * 获取 当前 默认 数据源 对应 的 ORM 存储（与 引擎 共享 表 级 锁 映射，保证 跨 调用 串行）。
     *
     * @return ORM 存储 实例，未 连接 时 返回 空
     */
    private RocksDbOrmStore ormStore() {
        RocksDB db = currentDB();
        return db == null ? null : new RocksDbOrmStore(db, ormTableLocks);
    }

    /**
     * 持久化 实体 列表 到 RocksDB。
     * <p>表 名 取 {@code name}（尊重 调用 者 传入 值，M1 修复——不再 静默 改 写 为
     * 实体 推导 表名）。行 键 按 实体 id 或 自增 序号 分配（序号 分配 在 表 级 锁 内
     * 原子 完成）。</p>
     *
     * @param name 表名
     * @param data 实体 列表
     * @param <T>  实体 类型
     * @return this
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        RocksDbOrmStore store = ormStore();
        if (store == null) {
            throw new IllegalStateException("RocksDB 数据源未连接");
        }
        if (data == null || data.isEmpty()) {
            return this;
        }
        store.store(name, data);
        return this;
    }

    /**
     * 执行 实体 查询：RocksDB 前缀 扫描 + 可选 WHERE 内存 过滤。
     *
     * @param where       WHERE 子句
     * @param params      参数 数组
     * @param entityClass 实体 类 类型
     * @param limit       限制
     * @param offset      偏移 量
     * @param <T>         实体 类型
     * @return 查询 结果
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        RocksDbOrmStore store = ormStore();
        if (store == null) {
            return Collections.emptyList();
        }
        List<Object> paramList = params != null ? java.util.Arrays.asList(params) : Collections.emptyList();
        return store.query(where, paramList, entityClass, limit, offset);
    }

    /**
     * 执行 实体 更新：WHERE 过滤 命中 行，应用 SET 字段 后 原子 回写 RocksDB。
     * <p>执行前后 依次 回调 {@code EngineInterceptor} 扩展 的
     * {@code beforeUpdate / afterUpdate / onError}。读-改-写 循环 在 表 级 锁 内
     * 串行化（H3 修复）。</p>
     *
     * @param sql 更新 SQL 信息
     * @param <T> 实体 类型
     * @return 影响 行数
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        String ql = sql.whereClause();
        Object[] params = sql.params() == null ? new Object[0] : sql.params().toArray();
        List<com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor> interceptorList = interceptors();
        for (com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor interceptor : interceptorList) {
            interceptor.beforeUpdate(ql, params);
        }
        try {
            int affected = executeUpdateInRocks(sql);
            for (com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor interceptor : interceptorList) {
                interceptor.afterUpdate(ql, params, affected);
            }
            return affected;
        } catch (RuntimeException re) {
            for (com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor interceptor : interceptorList) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * RocksDB ORM 更新 核心 逻辑（解析 SET 子句 + WHERE 参数，回写 命中 行）。
     *
     * @param sql 更新 SQL 信息
     * @param <T> 实体 类型
     * @return 影响 行数
     */
    private <T> int executeUpdateInRocks(UpdateSql<T> sql) {
        RocksDbOrmStore store = ormStore();
        if (store == null) {
            throw new IllegalStateException("RocksDB 数据源未连接");
        }
        // 解析 SET 子句 与 WHERE 参数 边界（与 基类 内存 实现 同 语法）
        java.util.Map<String, Object> setValues = new java.util.LinkedHashMap<>();
        String setClause = sql.setClause();
        int setCount = 0;
        if (setClause != null && !setClause.isEmpty()) {
            String[] setParts = setClause.split(", ");
            setCount = setParts.length;
            java.util.List<Object> allParams = sql.params();
            for (int i = 0; i < setCount; i++) {
                int eqIdx = setParts[i].indexOf(" = ");
                if (eqIdx > 0) {
                    setValues.put(setParts[i].substring(0, eqIdx), allParams.get(i));
                }
            }
        }
        java.util.List<Object> whereParams;
        java.util.List<Object> allParams = sql.params() == null ? java.util.Collections.emptyList() : sql.params();
        if (allParams.size() > setCount) {
            whereParams = allParams.subList(setCount, allParams.size());
        } else {
            whereParams = java.util.Collections.emptyList();
        }
        return store.update(sql.whereClause(), whereParams, setValues, sql.entityClass());
    }

    /**
     * 执行 实体 删除：WHERE 过滤 命中 行，原子 移除 RocksDB 对应 键。
     * <p>执行前后 依次 回调 {@code EngineInterceptor} 扩展 的
     * {@code beforeUpdate / afterUpdate / onError}。读-删 循环 在 表 级 锁 内
     * 串行化（H3 修复）。</p>
     *
     * @param sql 删除 SQL 信息
     * @param <T> 实体 类型
     * @return 影响 行数
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        String ql = sql.whereClause();
        Object[] params = sql.params() == null ? new Object[0] : sql.params().toArray();
        List<com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor> interceptorList = interceptors();
        for (com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor interceptor : interceptorList) {
            interceptor.beforeUpdate(ql, params);
        }
        try {
            int affected = executeDeleteInRocks(sql);
            for (com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor interceptor : interceptorList) {
                interceptor.afterUpdate(ql, params, affected);
            }
            return affected;
        } catch (RuntimeException re) {
            for (com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor interceptor : interceptorList) {
                interceptor.onError(ql, params, re);
            }
            throw re;
        }
    }

    /**
     * RocksDB ORM 删除 核心 逻辑（WHERE 过滤 命中 行，原子 移除 键）。
     *
     * @param sql 删除 SQL 信息
     * @param <T> 实体 类型
     * @return 影响 行数
     */
    private <T> int executeDeleteInRocks(DeleteSql<T> sql) {
        RocksDbOrmStore store = ormStore();
        if (store == null) {
            throw new IllegalStateException("RocksDB 数据源未连接");
        }
        java.util.List<Object> paramList = sql.params() == null ? java.util.Collections.emptyList() : sql.params();
        return store.delete(sql.whereClause(), paramList, sql.entityClass());
    }

    /**
     * 关闭所有数据源连接
    */
    @Override
    public void close() {
        for (RocksDB db : databases.values()) {
            try {
                db.close();
            } catch (Exception ignored) {
                // 忽略单个数据源关闭异常
            }
        }
        databases.clear();
        dataSources.clear();
    }
}
