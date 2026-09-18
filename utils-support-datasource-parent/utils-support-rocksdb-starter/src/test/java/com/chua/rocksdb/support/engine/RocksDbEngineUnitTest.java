package com.chua.rocksdb.support.engine;

import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RocksDB 引擎单元测试。
 *
 * <p>基于本地临时目录，无需外部服务或 Docker 环境。
 * 覆盖 KV 字节/字符串 API、文档 CRUD、全文检索、
 * 以及 ORM 语义（store/executeNewQuery）的显式拒绝断言。</p>
 *
 * @author CH
 */
public class RocksDbEngineUnitTest {

    /** 临时目录 */
    @TempDir
    Path tempDir;

    /** 引擎实例 */
    private RocksDbEngine engine;

    /**
     * 设置Up。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("rocksdb_test");
        Files.createDirectories(dbPath);
        engine = new RocksDbEngine();
        engine.addDataSource("default", dbPath.toString());
    }

    /**
     * tearDown。
     */
    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // ==================== 字节 KV ====================

    /**
     * 测试：放入And获取字节数组。
     */
    @Test
    void testPutAndGetBytes() {
        byte[] key = "k1".getBytes();
        byte[] value = "v1".getBytes();
        engine.putBytes("default", key, value);
        byte[] got = engine.getBytes("default", key);
        assertArrayEquals(value, got);
    }

    /**
     * 测试：获取字节数组ReturnsNullWhenMissing。
     */
    @Test
    void testGetBytesReturnsNullWhenMissing() {
        byte[] got = engine.getBytes("default", "missing".getBytes());
        assertNull(got);
    }

    /**
     * 测试：删除字节数组。
     */
    @Test
    void testDeleteBytes() {
        engine.putBytes("default", "del".getBytes(), "x".getBytes());
        engine.deleteBytes("default", "del".getBytes());
        assertNull(engine.getBytes("default", "del".getBytes()));
    }

    /**
     * 测试：Scan字节数组With前缀。
     */
    @Test
    void testScanBytesWithPrefix() {
        engine.putBytes("default", "pfx:1".getBytes(), "a".getBytes());
        engine.putBytes("default", "pfx:2".getBytes(), "b".getBytes());
        engine.putBytes("default", "other:3".getBytes(), "c".getBytes());
        List<Map.Entry<byte[], byte[]>> rows = engine.scanBytes("default", "pfx:".getBytes());
        assertEquals(2, rows.size(), "前缀扫描应匹配 2 条");
    }

    /**
     * 测试：写入批次。
     */
    @Test
    void testWriteBatch() {
        byte[] op0 = {(byte) 0};
        byte[] op1 = {(byte) 1};
        byte[][][] ops = new byte[][][] {
                {op0, "bk1".getBytes(), "bv1".getBytes()},
                {op0, "bk2".getBytes(), "bv2".getBytes()},
                {op0, "bk3".getBytes(), "bv3".getBytes()},
                {op1, "bk3".getBytes(), null},
        };
        engine.writeBatch("default", List.of(ops));
        assertEquals("bv1", new String(engine.getBytes("default", "bk1".getBytes())));
        assertEquals("bv2", new String(engine.getBytes("default", "bk2".getBytes())));
        assertNull(engine.getBytes("default", "bk3".getBytes()), "writeBatch 的 delete 分支 应 移除 键");
    }

    /**
     * 测试：字节数组PersistAcross引擎Restart。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void testBytesPersistAcrossEngineRestart() throws Exception {
        // 字节 KV 真 的 落盘 验证：关 引擎 → 重开 同 目录 → 读 回
        Path dbDir = tempDir.resolve("rocksdb_bytes_restart");
        Files.createDirectories(dbDir);
        RocksDbEngine e1 = new RocksDbEngine();
        e1.addDataSource("default", dbDir.toString());
        e1.putBytes("default", "pk".getBytes(), "pv".getBytes());
        e1.deleteBytes("default", "pk".getBytes());
        e1.putBytes("default", "pk".getBytes(), "pv2".getBytes());
        e1.close();

        RocksDbEngine e2 = new RocksDbEngine();
        e2.addDataSource("default", dbDir.toString());
        try {
            assertEquals("pv2", new String(e2.getBytes("default", "pk".getBytes())),
                    "字节 值 应 持久化 到 RocksDB 文件");
        } finally {
            e2.close();
        }
    }

    /**
     * 测试：DocumentsPersistAcross引擎Restart。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void testDocumentsPersistAcrossEngineRestart() throws Exception {
        // 文档 真 的 落盘 验证：关 引擎 → 重开 同 目录 → 读 回
        Path dbDir = tempDir.resolve("rocksdb_doc_restart");
        Files.createDirectories(dbDir);
        RocksDbEngine e1 = new RocksDbEngine();
        e1.addDataSource("default", dbDir.toString());
        e1.insert("articles", Map.of("id", "d1", "title", "hello world"));
        e1.close();

        RocksDbEngine e2 = new RocksDbEngine();
        e2.addDataSource("default", dbDir.toString());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> found = e2.findById("articles", "d1", (Class<Map<String, Object>>) (Class<?>) Map.class);
            assertNotNull(found, "文档 应 持久化 到 RocksDB 文件");
            assertEquals("hello world", found.get("title"));
        } finally {
            e2.close();
        }
    }

    // ==================== 字符串 KV（KvEngine） ====================

    /**
     * 测试：字符串放入获取。
     */
    @Test
    void testStringPutGet() {
        engine.put("sk", "sv");
        assertEquals("sv", engine.get("sk"));
    }

    /**
     * 测试：字符串获取ReturnsNullWhenMissing。
     */
    @Test
    void testStringGetReturnsNullWhenMissing() {
        assertNull(engine.get("missing"));
    }

    /**
     * 测试：字符串删除。
     */
    @Test
    void testStringDelete() {
        engine.put("sd", "sv");
        assertTrue(engine.delete("sd"));
        assertFalse(engine.delete("sd"));
    }

    /**
     * 测试：Incr。
     */
    @Test
    void testIncr() {
        long v1 = engine.incr("counter");
        long v2 = engine.incr("counter");
        assertEquals(1, v1);
        assertEquals(2, v2);
    }

    /**
     * 测试：字符串KvPersistAcross引擎Restart。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void testStringKvPersistAcrossEngineRestart() throws Exception {
        // 字符串 KV 真 的 落盘 验证：关 引擎 → 重开 同 目录 → 读 回
        Path dbDir = tempDir.resolve("rocksdb_strkv_restart");
        Files.createDirectories(dbDir);
        RocksDbEngine e1 = new RocksDbEngine();
        e1.addDataSource("default", dbDir.toString());
        e1.put("user:1", "Alice");
        e1.put("user:2", "Bob");
        e1.incr("counter");
        e1.incr("counter");
        e1.close();

        RocksDbEngine e2 = new RocksDbEngine();
        e2.addDataSource("default", dbDir.toString());
        try {
            assertEquals("Alice", e2.get("user:1"), "字符串 KV 应 持久化 到 RocksDB 文件");
            assertEquals("Bob", e2.get("user:2"));
            assertEquals("2", e2.get("counter"), "incr 计数 应 已 持久化");
            Map<String, String> byPrefix = e2.findAllByPrefix("user:");
            assertEquals(2, byPrefix.size(), "前缀 扫描 应 命中 2 条 字符串 KV");
        } finally {
            e2.close();
        }
    }

    /**
     * 测试：查找全部By前缀。
     */
    @Test
    void testFindAllByPrefix() {
        engine.put("user:1", "a");
        engine.put("user:2", "b");
        engine.put("other:3", "c");
        Map<String, String> result = engine.findAllByPrefix("user:");
        assertEquals(2, result.size());
        assertTrue(result.containsKey("user:1"));
        assertTrue(result.containsKey("user:2"));
        assertFalse(result.containsKey("other:3"));
    }

    // ==================== 文档存储（DocumentStore） ====================

    /**
     * 测试：Document插入And查找ByID。
     */
    @Test
    void testDocumentInsertAndFindById() {
        Map<String, Object> doc = Map.of("id", "doc1", "name", "Alice", "content", "hello world");
        engine.insert("articles", doc);

        @SuppressWarnings("unchecked")
        Map<String, Object> found = engine.findById("articles", "doc1", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertNotNull(found);
        assertEquals("Alice", found.get("name"));
    }

    /**
     * 测试：Document查找ByIDReturnsNullWhenMissing。
     */
    @Test
    void testDocumentFindByIdReturnsNullWhenMissing() {
        assertNull(engine.findById("articles", "missing", (Class<Map<String, Object>>) (Class<?>) Map.class));
    }

    /**
     * 测试：Document更新。
     */
    @Test
    void testDocumentUpdate() {
        Map<String, Object> doc = Map.of("id", "doc1", "name", "Alice");
        engine.insert("articles", doc);

        Map<String, Object> updated = Map.of("id", "doc1", "name", "Bob");
        engine.update("articles", "doc1", updated);

        @SuppressWarnings("unchecked")
        Map<String, Object> found = engine.findById("articles", "doc1", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertEquals("Bob", found.get("name"));
    }

    /**
     * 测试：Document删除。
     */
    @Test
    void testDocumentDelete() {
        Map<String, Object> doc = Map.of("id", "doc1", "name", "Alice");
        engine.insert("articles", doc);
        assertTrue(engine.delete("articles", "doc1"));
        assertNull(engine.findById("articles", "doc1", (Class<Map<String, Object>>) (Class<?>) Map.class));
        assertFalse(engine.delete("articles", "doc1"));
    }

    /**
     * 测试：Document查找全部。
     */
    @Test
    void testDocumentFindAll() {
        engine.insert("articles", Map.of("id", "d1", "name", "A"));
        engine.insert("articles", Map.of("id", "d2", "name", "B"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = engine.findAll("articles", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertEquals(2, all.size());
    }

    // ==================== 全文检索（FulltextSearch） ====================

    /**
     * 测试：Fulltext搜索。
     */
    @Test
    void testFulltextSearch() {
        Map<String, Object> article = Map.of("id", "d1", "title", "hello world news");
        Map<String, Object> article2 = Map.of("id", "d2", "title", "goodbye world news");
        engine.insert("articles", article);
        engine.insert("articles", article2);

        List<Map<String, Object>> results = engine.search("world", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertEquals(2, results.size(), "全文检索 'world' 应匹配 2 条");

        List<Map<String, Object>> noResults = engine.search("nonexistent", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertTrue(noResults.isEmpty());
    }

    /**
     * 测试：删除Fulltext索引。
     */
    @Test
    void testDropFulltextIndex() {
        engine.insert("articles", Map.of("id", "d1", "content", "hello world"));
        assertDoesNotThrow(() -> engine.dropFulltextIndex(Map.class));
    }

    // ==================== ORM 支持 ====================

    /** 测试 用 实体 */
    public static class User {
        /** 标识 */
        public Integer id;
        /** 名称 */
        public String name;
        /** 年龄 */
        public Integer age;
        /** 部门编号（多 词 驼峰 字段，验证 snake → camel 映射） */
        public Integer deptId;

        /** 空 构造 器 */
        public User() {
        }

        /** 获取id */
        public Integer getId() {
            return id;
        }

        /** 获取name */
        public String getName() {
            return name;
        }

        /** 获取age */
        public Integer getAge() {
            return age;
        }

        /** 获取deptId */
        public Integer getDeptId() {
            return deptId;
        }

        /** 设置name */
        public void setName(String name) {
            this.name = name;
        }

        /** 设置age */
        public void setAge(Integer age) {
            this.age = age;
        }

        /** 设置deptId */
        public void setDeptId(Integer deptId) {
            this.deptId = deptId;
        }
    }

    /**
     * 测试：OrmStoreAnd查询。
     */
    @Test
    void testOrmStoreAndQuery() {
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        u1.deptId = 100;
        User u2 = new User();
        u2.id = 2;
        u2.name = "Bob";
        u2.age = 25;
        u2.deptId = 200;
        engine.store("user", List.of(u1, u2));

        List<User> all = engine.query(User.class).list();
        assertEquals(2, all.size(), "ORM 查询 应 返回 全部 2 条");

        List<User> filtered = engine.query(User.class).eq(User::getAge, 25).list();
        assertEquals(1, filtered.size(), "ORM 条件 查询 应 命中 1 条");
        assertEquals("Bob", filtered.getFirst().name);
    }

    /**
     * 测试：Orm查询WithSnakeCase列。
     */
    @Test
    void testOrmQueryWithSnakeCaseColumn() {
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        u1.deptId = 100;
        User u2 = new User();
        u2.id = 2;
        u2.name = "Bob";
        u2.age = 25;
        u2.deptId = 200;
        engine.store("user", List.of(u1, u2));

        // lambda 渲染 的 WHERE 列 为 下划线 dept_id，应 映射 回 实体 字段 deptId
        List<User> filtered = engine.query(User.class).eq(User::getDeptId, 200).list();
        assertEquals(1, filtered.size(), "snake_case 列 名 dept_id 应 命中 实体 字段 deptId");
        assertEquals("Bob", filtered.getFirst().name);
    }

    /**
     * 测试：Orm更新WithSnakeCase列。
     */
    @Test
    void testOrmUpdateWithSnakeCaseColumn() {
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        u1.deptId = 100;
        engine.store("user", List.of(u1));

        int affected = engine.update(User.class).set(User::getDeptId, 999).eq(User::getDeptId, 100).update();
        assertEquals(1, affected, "ORM 更新 应 影响 1 行");

        List<User> all = engine.query(User.class).list();
        assertEquals(1, all.size());
        assertEquals(999, all.getFirst().deptId, "更新 后 deptId 应 为 999");
    }

    /**
     * 测试：Orm更新。
     */
    @Test
    void testOrmUpdate() {
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        engine.store("user", List.of(u1));

        int affected = engine.update(User.class).set(User::getAge, 31).eq(User::getId, 1).update();
        assertEquals(1, affected, "ORM 更新 应 影响 1 行");

        List<User> all = engine.query(User.class).list();
        assertEquals(1, all.size());
        assertEquals(31, all.getFirst().age, "更新 后 age 应 为 31");
    }

    /**
     * 测试：Orm删除。
     */
    @Test
    void testOrmDelete() {
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        User u2 = new User();
        u2.id = 2;
        u2.name = "Bob";
        u2.age = 25;
        engine.store("user", List.of(u1, u2));

        int affected = engine.delete(User.class).eq(User::getId, 1).remove();
        assertEquals(1, affected, "ORM 删除 应 影响 1 行");

        List<User> remaining = engine.query(User.class).list();
        assertEquals(1, remaining.size(), "删除 后 应 剩 1 条");
        assertEquals("Bob", remaining.getFirst().name);
    }

    /**
     * 测试：OrmPersistenceAcross查询。
     */
    @Test
    void testOrmPersistenceAcrossQuery() {
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        engine.store("user", List.of(u1));

        List<User> first = engine.query(User.class).eq(User::getId, 1).list();
        assertEquals(1, first.size());
        assertEquals("Alice", first.getFirst().name, "RocksDB 持久化 数据 应 被 再次 查询 命中");
    }

    /**
     * 测试：OrmRealPersistenceAcross引擎Restart。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void testOrmRealPersistenceAcrossEngineRestart() throws Exception {
        // 验证 ORM 数据 真正 落盘 到 RocksDB（而非 仅 内存）：
        // 写入 → 关闭 引擎 → 重新 打开 同 目录 引擎 → 查询 仍 命中
        Path dbDir = tempDir.resolve("rocksdb_orm_restart");
        Files.createDirectories(dbDir);
        RocksDbEngine e1 = new RocksDbEngine();
        e1.addDataSource("default", dbDir.toString());
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        u1.deptId = 100;
        e1.store("user", List.of(u1));
        e1.update(User.class).set(User::getAge, 31).eq(User::getId, 1).update();
        e1.close();

        // 同 目录 重新 打开，数据 仍 在
        RocksDbEngine e2 = new RocksDbEngine();
        e2.addDataSource("default", dbDir.toString());
        try {
            List<User> all = e2.query(User.class).list();
            assertEquals(1, all.size(), "重新 打开 RocksDB 后 应 命中 已 持久化 行");
            assertEquals("Alice", all.getFirst().name);
            assertEquals(31, all.getFirst().age, "更新 值 应 已 持久化");
            assertEquals(100, all.getFirst().deptId);
        } finally {
            e2.close();
        }
    }

    // ==================== 并发 回归（竞态 修复 验证） ====================

    /**
     * 测试：IncrConcurrent编号LostUpdates。
     *
     * @throws InterruptedException 当执行过程不满足前置条件时
     */
    @Test
    void testIncrConcurrentNoLostUpdates() throws InterruptedException {
        int threads = 8;
        int perThread = 500;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        engine.incr("ctr");
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        for (Thread w : workers) {
            w.join();
        }
        assertEquals((long) threads * perThread, Long.parseLong(engine.get("ctr")),
                "并发 incr 不 应 丢失 计数（H1 修复 验证）");
    }

    /**
     * 测试：OrmStoreAutoSeqConcurrent编号DuplicateKeys。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void testOrmStoreAutoSeqConcurrentNoDuplicateKeys() throws Exception {
        // 实体 无 id 字段 → 走 自增 序号；并 发 写入 不 应 产生 重复 行 键（H2 修复 验证）
        // 表名 使用 实体 自身 表名，保证 后续 query 能 命中
        int threads = 8;
        int perThread = 50;
        String table = RocksDbOrmStore.resolveEntityTableName(NoIdDoc.class);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int base = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        NoIdDoc d = new NoIdDoc();
                        d.name = "d" + base + "-" + j;
                        engine.store(table, List.of(d));
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        // 字节 级 验证：ORM 前缀 下 实体 行 键 数量 应 为 400（排除 __seq__ 计数 键）
        List<java.util.Map.Entry<byte[], byte[]>> rawRows = engine.scanBytes("default", "ORM:noiddoc:".getBytes());
        long entityRows = rawRows.stream()
                .filter(e -> !new String(e.getKey(), java.nio.charset.StandardCharsets.UTF_8).endsWith("__seq__"))
                .count();
        assertEquals(threads * perThread, entityRows,
                "并 发 自增 序号 写入 应 产生 " + threads * perThread + " 条 不 重复 实体 行 键，实际 " + entityRows);
        assertEquals(threads * perThread, engine.query(NoIdDoc.class).list().size(),
                "并 发 自增 序号 写入 不 应 产生 重复 行 键 导致 行 丢失（H2 修复 验证）");
    }

    /**
     * 测试：FtsConcurrent插入编号LostDocs。
     *
     * @throws InterruptedException 当执行过程不满足前置条件时
     */
    @Test
    void testFtsConcurrentInsertNoLostDocs() throws InterruptedException {
        // 并 发 插入 含 相同 token 的 文档，FTS 不 应 丢 任 何 文档（C1 修复 验证）
        int threads = 4;
        int perThread = 25;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int base = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        engine.insert("concurrent_docs",
                                Map.of("id", "c" + base + "-" + j, "content", "shared world token"));
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = engine.search("shared", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertEquals(threads * perThread, hits.size(),
                "并 发 插入 后 FTS 不 应 丢 任 何 文档（C1 修复 验证）");
    }

    /** 无 id 字段 的 测试 实体（验证 自增 序号 路径） */
    public static class NoIdDoc {
        /** 名称 */
        public String name;

        /** 空 构造 器 */
        public NoIdDoc() {
        }

        /** 获取name */
        public String getName() {
            return name;
        }

        /** 设置name */
        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * 测试：Orm查询上限偏移量Paging。
     */
    @Test
    void testOrmQueryLimitOffsetPaging() {
        // 分页 验证：limit/offset 由 基类 processQueryResult 统一 截取
        for (int i = 1; i <= 10; i++) {
            User u = new User();
            u.id = i;
            u.name = "u" + i;
            u.age = i;
            engine.store("user", List.of(u));
        }
        List<User> page2 = engine.query(User.class).limit(3).offset(4).list();
        assertEquals(3, page2.size(), "limit(3).offset(4) 应 返回 3 条");
        // 显式 id 键 按 字面 量 字典 序 排 列：1,10,2,3,4,5,6,7,8,9，位 置 4 对 应 id=4
        assertEquals("u4", page2.getFirst().name, "字典 序 位 置 4 对 应 id=4");
        assertEquals("u5", page2.get(1).name);
        assertEquals("u6", page2.getLast().name);
    }

    /**
     * 测试：Orm查询上限偏移量Beyond结束。
     */
    @Test
    void testOrmQueryLimitOffsetBeyondEnd() {
        // offset 超 出 总 行数 时 返回 空 列表（不 抛 异常）
        User u1 = new User();
        u1.id = 1;
        u1.name = "A";
        u1.age = 30;
        engine.store("user", List.of(u1));
        List<User> beyond = engine.query(User.class).limit(5).offset(99).list();
        assertTrue(beyond.isEmpty(), "offset 超 出 总 行数 应 返回 空");
    }

    /**
     * 测试：Orm查询数量。
     */
    @Test
    void testOrmQueryCount() {
        // count() 终端 方法 验证
        for (int i = 1; i <= 5; i++) {
            User u = new User();
            u.id = i;
            u.name = "u" + i;
            u.age = i;
            engine.store("user", List.of(u));
        }
        long total = engine.query(User.class).count();
        assertEquals(5, total, "count() 应 返回 总 行数");
        long filtered = engine.query(User.class).eq(User::getAge, 3).count();
        assertEquals(1, filtered, "count() 带 条件 应 命中 1 行");
    }

    // ==================== SPI 注册 ====================

    /**
     * 测试：SpiRegistration。
     */
    @Test
    void testSpiRegistration() {
        RocksDbEngine eng = ServiceProvider.of(RocksDbEngine.class).getExtension("rocksdb");
        assertNotNull(eng, "SPI 注册应返回 RocksDbEngine 实例");
    }

    // ==================== close ====================

    /**
     * 测试：关闭。
     */
    @Test
    void testClose() {
        assertDoesNotThrow(engine::close);
    }
}
