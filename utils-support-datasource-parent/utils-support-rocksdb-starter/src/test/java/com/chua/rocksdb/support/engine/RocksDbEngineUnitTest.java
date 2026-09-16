package com.chua.rocksdb.support.engine;

import com.chua.common.support.lang.datasource.kv.KvEngine;
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

    /** 临时目录（JDK 25 原生 TempDir 支持） */
    @TempDir
    static Path tempDir;

    private RocksDbEngine engine;
    private Path dbPath;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("rocksdb_test");
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
    void testScanBytes() {
        engine.putBytes("default", "a".getBytes(), "1".getBytes());
        engine.putBytes("default", "b".getBytes(), "2".getBytes());
        List<Map.Entry<byte[], byte[]>> rows = engine.scanBytes("default");
        assertEquals(2, rows.size());
    }

    @Test
    void testScanBytesWithPrefix() {
        engine.putBytes("default", "pfx:1".getBytes(), "a".getBytes());
        engine.putBytes("default", "pfx:2".getBytes(), "b".getBytes());
        engine.putBytes("default", "other:3".getBytes(), "c".getBytes());
        List<Map.Entry<byte[], byte[]>> rows = engine.scanBytes("default", "pfx:".getBytes());
        assertEquals(2, rows.size());
    }

    @Test
    void testWriteBatch() {
        byte[][][] ops = new byte[][][] {
                {"0", "bk1".getBytes(), "bv1".getBytes()},
                {"0", "bk2".getBytes(), "bv2".getBytes()},
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

        Map<String, Object> found = engine.findById("articles", "doc1", Map.class);
        assertNotNull(found);
        assertEquals("Alice", found.get("name"));
    }

    @Test
    void testDocumentFindByIdReturnsNullWhenMissing() {
        assertNull(engine.findById("articles", "missing", Map.class));
    }

    @Test
    void testDocumentUpdate() {
        Map<String, Object> doc = Map.of("id", "doc1", "name", "Alice");
        engine.insert("articles", doc);

        Map<String, Object> updated = Map.of("id", "doc1", "name", "Bob");
        engine.update("articles", "doc1", updated);

        Map<String, Object> found = engine.findById("articles", "doc1", Map.class);
        assertEquals("Bob", found.get("name"));
    }

    @Test
    void testDocumentDelete() {
        Map<String, Object> doc = Map.of("id", "doc1", "name", "Alice");
        engine.insert("articles", doc);
        assertTrue(engine.delete("articles", "doc1"));
        assertNull(engine.findById("articles", "doc1", Map.class));
        assertFalse(engine.delete("articles", "doc1"));
    }

    @Test
    void testDocumentFindAll() {
        engine.insert("articles", Map.of("id", "d1", "name", "A"));
        engine.insert("articles", Map.of("id", "d2", "name", "B"));
        List<Map<String, Object>> all = engine.findAll("articles", Map.class);
        assertEquals(2, all.size());
    }

    // ==================== 全文检索（FulltextSearch） ====================

    @Test
    void testFulltextSearch() {
        engine.insert("articles", Map.of("id", "d1", "content", "hello world"));
        engine.insert("articles", Map.of("id", "d2", "content", "goodbye world"));

        List<Map<String, Object>> results = engine.search("world", Map.class);
        assertEquals(2, results.size());

        List<Map<String, Object>> noResults = engine.search("nonexistent", Map.class);
        assertTrue(noResults.isEmpty());
    }

    @Test
    void testDropFulltextIndex() {
        engine.insert("articles", Map.of("id", "d1", "content", "hello world"));
        assertDoesNotThrow(() -> engine.dropFulltextIndex(Map.class));
    }

    // ==================== ORM 语义拒绝 ====================

    @Test
    void testStoreThrowsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class,
                () -> engine.store("default", List.of("any")),
                "store() 应抛出 UnsupportedOperationException");
    }

    @Test
    void testQueryListThrowsUnsupportedOperation() {
        assertThrows(Exception.class,
                () -> engine.query(String.class).list(),
                "query().list() 应因 executeNewQuery 不支持而抛异常");
    }

    // ==================== SPI 注册 ====================

    @Test
    void testSpiRegistration() {
        KvEngine kv = ServiceProvider.of(RocksDbEngine.class).getExtension("rocksdb");
        assertInstanceOf(RocksDbEngine.class, kv);
    }

    // ==================== close ====================

    @Test
    void testClose() {
        assertDoesNotThrow(engine::close);
    }
}
