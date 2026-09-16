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

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("rocksdb_test");
        Files.createDirectories(dbPath);
        engine = new RocksDbEngine();
        engine.addDataSource("default", dbPath.toString());
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // ==================== 字节 KV ====================

    @Test
    void testPutAndGetBytes() {
        byte[] key = "k1".getBytes();
        byte[] value = "v1".getBytes();
        engine.putBytes("default", key, value);
        byte[] got = engine.getBytes("default", key);
        assertArrayEquals(value, got);
    }

    @Test
    void testGetBytesReturnsNullWhenMissing() {
        byte[] got = engine.getBytes("default", "missing".getBytes());
        assertNull(got);
    }

    @Test
    void testDeleteBytes() {
        engine.putBytes("default", "del".getBytes(), "x".getBytes());
        engine.deleteBytes("default", "del".getBytes());
        assertNull(engine.getBytes("default", "del".getBytes()));
    }

    @Test
    void testScanBytesWithPrefix() {
        engine.putBytes("default", "pfx:1".getBytes(), "a".getBytes());
        engine.putBytes("default", "pfx:2".getBytes(), "b".getBytes());
        engine.putBytes("default", "other:3".getBytes(), "c".getBytes());
        List<Map.Entry<byte[], byte[]>> rows = engine.scanBytes("default", "pfx:".getBytes());
        assertEquals(2, rows.size(), "前缀扫描应匹配 2 条");
    }

    @Test
    void testWriteBatch() {
        byte[] op0 = {(byte) 0};
        byte[][][] ops = new byte[][][] {
                {op0, "bk1".getBytes(), "bv1".getBytes()},
                {op0, "bk2".getBytes(), "bv2".getBytes()},
        };
        engine.writeBatch("default", List.of(ops));
        assertEquals("bv1", new String(engine.getBytes("default", "bk1".getBytes())));
        assertEquals("bv2", new String(engine.getBytes("default", "bk2".getBytes())));
    }

    // ==================== 字符串 KV（KvEngine） ====================

    @Test
    void testStringPutGet() {
        engine.put("sk", "sv");
        assertEquals("sv", engine.get("sk"));
    }

    @Test
    void testStringGetReturnsNullWhenMissing() {
        assertNull(engine.get("missing"));
    }

    @Test
    void testStringDelete() {
        engine.put("sd", "sv");
        assertTrue(engine.delete("sd"));
        assertFalse(engine.delete("sd"));
    }

    @Test
    void testIncr() {
        long v1 = engine.incr("counter");
        long v2 = engine.incr("counter");
        assertEquals(1, v1);
        assertEquals(2, v2);
    }

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

    @Test
    void testDocumentInsertAndFindById() {
        Map<String, Object> doc = Map.of("id", "doc1", "name", "Alice", "content", "hello world");
        engine.insert("articles", doc);

        @SuppressWarnings("unchecked")
        Map<String, Object> found = engine.findById("articles", "doc1", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertNotNull(found);
        assertEquals("Alice", found.get("name"));
    }

    @Test
    void testDocumentFindByIdReturnsNullWhenMissing() {
        assertNull(engine.findById("articles", "missing", (Class<Map<String, Object>>) (Class<?>) Map.class));
    }

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

    @Test
    void testDocumentDelete() {
        Map<String, Object> doc = Map.of("id", "doc1", "name", "Alice");
        engine.insert("articles", doc);
        assertTrue(engine.delete("articles", "doc1"));
        assertNull(engine.findById("articles", "doc1", (Class<Map<String, Object>>) (Class<?>) Map.class));
        assertFalse(engine.delete("articles", "doc1"));
    }

    @Test
    void testDocumentFindAll() {
        engine.insert("articles", Map.of("id", "d1", "name", "A"));
        engine.insert("articles", Map.of("id", "d2", "name", "B"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = engine.findAll("articles", (Class<Map<String, Object>>) (Class<?>) Map.class);
        assertEquals(2, all.size());
    }

    // ==================== 全文检索（FulltextSearch） ====================

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

        /** 设置name */
        public void setName(String name) {
            this.name = name;
        }

        /** 设置age */
        public void setAge(Integer age) {
            this.age = age;
        }
    }

    @Test
    void testOrmStoreAndQuery() {
        User u1 = new User();
        u1.id = 1;
        u1.name = "Alice";
        u1.age = 30;
        User u2 = new User();
        u2.id = 2;
        u2.name = "Bob";
        u2.age = 25;
        engine.store("user", List.of(u1, u2));

        List<User> all = engine.query(User.class).list();
        assertEquals(2, all.size(), "ORM 查询 应 返回 全部 2 条");

        List<User> filtered = engine.query(User.class).eq(User::getAge, 25).list();
        assertEquals(1, filtered.size(), "ORM 条件 查询 应 命中 1 条");
        assertEquals("Bob", filtered.getFirst().name);
    }

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

    // ==================== SPI 注册 ====================

    @Test
    void testSpiRegistration() {
        RocksDbEngine eng = ServiceProvider.of(RocksDbEngine.class).getExtension("rocksdb");
        assertNotNull(eng, "SPI 注册应返回 RocksDbEngine 实例");
    }

    // ==================== close ====================

    @Test
    void testClose() {
        assertDoesNotThrow(engine::close);
    }
}
