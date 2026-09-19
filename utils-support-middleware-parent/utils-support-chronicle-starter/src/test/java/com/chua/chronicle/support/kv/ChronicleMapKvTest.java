package com.chua.chronicle.support.kv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChronicleMap KV 单元测试（临时目录持久化 + 关闭语义）。
 *
 * @author CH
 */
public class ChronicleMapKvTest {

    /**
     * 临时目录
     */
    @TempDir
    Path tempDir;

    /**
     * 构造带唯一名称的 KV 实例（ChronicleMap 同 JVM 内名称唯一，避免测试间冲突）。
     *
     * @param file 持久化文件，可为 空（内存型）
     * @return KV 实例
     */
    private ChronicleMapKv newKv(Path file) {
        Properties props = new Properties();
        props.setProperty("name", "kv-" + UUID.randomUUID());
        if (file != null) {
            props.setProperty("file", file.toString());
        }
        return new ChronicleMapKv(props);
    }

    /**
     * 测试：写入读取删除往返。
     */
    @Test
    void putGetDeleteRoundtrip() {
        try (ChronicleMapKv kv = newKv(null)) {
            kv.put("k1", "v1");
            assertEquals("v1", kv.get("k1"));
            assertTrue(kv.containsKey("k1"));
            kv.put("big", "x".repeat(4096));
            assertEquals(4096, kv.get("big").length(), "超出均值字节数的长值应可写入（自动跨块重分配）");
            assertTrue(kv.delete("k1"));
            assertFalse(kv.delete("k1"));
            assertNull(kv.get("k1"));
        }
    }

    /**
     * 测试：null 值写入等效删除（KvEngine 契约，不 抛 NPE）。
     */
    @Test
    void putNullActsAsDelete() {
        try (ChronicleMapKv kv = newKv(null)) {
            kv.put("k", "v");
            kv.put("k", null);
            assertNull(kv.get("k"));
            assertFalse(kv.containsKey("k"));
        }
    }

    /**
     * 测试：递增计数，非数值旧值显式抛。
     */
    @Test
    void incrCountsAndRejectsNonNumeric() {
        try (ChronicleMapKv kv = newKv(null)) {
            assertEquals(1L, kv.incr("ctr"));
            assertEquals(2L, kv.incr("ctr"));
            kv.put("bad", "not-a-number");
            assertThrows(IllegalStateException.class, () -> kv.incr("bad"));
        }
    }

    /**
     * 测试：前缀扫描（空前缀返回全量）。
     */
    @Test
    void prefixScanIncludingEmptyPrefix() {
        try (ChronicleMapKv kv = newKv(null)) {
            kv.put("a:1", "x");
            kv.put("a:2", "y");
            kv.put("b:1", "z");
            Map<String, String> aOnly = kv.findAllByPrefix("a:");
            assertEquals(2, aOnly.size());
            assertEquals(3, kv.findAllByPrefix("").size(), "空前缀应返回全部键值对");
        }
    }

    /**
     * 测试：持久化文件重开后数据仍在，incr 计数不丢。
     */
    @Test
    void persistedAcrossReopen() {
        Path file = tempDir.resolve("kv.dat");
        try (ChronicleMapKv kv = newKv(file)) {
            kv.put("user:1", "Alice");
            kv.incr("ctr");
            kv.incr("ctr");
        }
        try (ChronicleMapKv kv = newKv(file)) {
            assertEquals("Alice", kv.get("user:1"));
            assertEquals("2", kv.get("ctr"));
        }
    }

    /**
     * 测试：关闭后拒绝读写，重复关闭无害。
     */
    @Test
    void closeRejectsFurtherOps() {
        ChronicleMapKv kv = newKv(null);
        kv.put("k", "v");
        kv.close();
        assertThrows(IllegalStateException.class, () -> kv.put("k2", "v2"));
        assertThrows(IllegalStateException.class, () -> kv.get("k"));
        assertDoesNotThrow(kv::close);
    }
}
