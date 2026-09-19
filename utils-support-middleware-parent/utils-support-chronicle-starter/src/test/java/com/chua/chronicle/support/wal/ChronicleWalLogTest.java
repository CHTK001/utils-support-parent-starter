package com.chua.chronicle.support.wal;

import com.chua.common.support.wal.WalConfig;
import com.chua.common.support.wal.WalRecord;
import com.chua.common.support.wal.WalSegmentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chronicle WAL 单元测试：真实分片统计、checkpoint 持久化、purge 护栏。
 *
 * @author CH
 */
public class ChronicleWalLogTest {

    /**
     * 临时目录
     */
    @TempDir
    Path tempDir;

    /**
     * 构造指向临时目录的 WAL 配置。
     *
     * @param namespace 命名空间
     * @return 配置
     */
    private WalConfig config(String namespace) {
        return WalConfig.builder()
                .walDir(tempDir)
                .namespace(namespace)
                .build();
    }

    /**
     * 测试：追加回放与分片真实统计。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void appendReplayAndRealSegments() throws Exception {
        try (ChronicleWalLog wal = new ChronicleWalLog(config("t1"))) {
            assertEquals(1L, wal.append((byte) 1, "p1".getBytes(StandardCharsets.UTF_8)));
            assertEquals(2L, wal.append((byte) 1, "p2".getBytes(StandardCharsets.UTF_8)));
            assertEquals(3L, wal.append((byte) 2, "p3".getBytes(StandardCharsets.UTF_8)));

            WalSegmentInfo seg = wal.currentSegment();
            assertTrue(seg.active(), "单 cycle 队列末分片即活跃分片");
            assertEquals(1L, seg.firstLsn(), "firstLsn 应为真实扫描值");
            assertEquals(3L, seg.lastLsn(), "lastLsn 应为真实扫描值");
            assertTrue(seg.recordCount() >= 3, "recordCount 应统计真实记录数");
            assertFalse(wal.listSegments().isEmpty());

            List<Long> seen = new ArrayList<>();
            wal.replay((lsn, op, payload) -> {
                seen.add(lsn);
                return true;
            });
            assertEquals(List.of(1L, 2L, 3L), seen);

            Optional<WalRecord> rec = wal.findByLsn(2);
            assertTrue(rec.isPresent());
            assertArrayEquals("p2".getBytes(StandardCharsets.UTF_8), rec.get().payload());
        }
    }

    /**
     * 测试：checkpoint 与 LSN 跨重开恢复，活跃分片不被 purge 误删。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void checkpointSurvivesReopenAndPurgeGuardsActiveCycle() throws Exception {
        WalConfig cfg = config("t2");
        try (ChronicleWalLog wal = new ChronicleWalLog(cfg)) {
            wal.append((byte) 1, "a".getBytes(StandardCharsets.UTF_8));
            wal.append((byte) 1, "b".getBytes(StandardCharsets.UTF_8));
            wal.markCheckpoint(2L);
            // 单 cycle（活跃）场景：purge 不应删除任何分片
            assertEquals(0, wal.purgeCheckpointed(1), "活跃 cycle 永不删除");
        }
        try (ChronicleWalLog wal = new ChronicleWalLog(cfg)) {
            assertEquals(2L, wal.currentLsn(), "重开后应扫描恢复最大 LSN");
            assertEquals(2L, wal.loadCheckpoint().checkpointLsn(), "重开后应恢复 checkpoint");
            List<Long> replayed = new ArrayList<>();
            wal.replay((lsn, op, payload) -> {
                replayed.add(lsn);
                return true;
            });
            assertTrue(replayed.isEmpty(), "默认从 checkpoint+1 回放，已 checkpoint 记录不再回放");
        }
    }

    /**
     * 测试：链式追加返回末条 LSN。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void appendChainReturnsLastLsn() throws Exception {
        try (ChronicleWalLog wal = new ChronicleWalLog(config("t3"))) {
            long last = wal.appendChain(chain -> chain
                    .add((byte) 1, "x")
                    .add((byte) 2, "y")
                    .add((byte) 3));
            assertEquals(3L, last);
            assertEquals(3L, wal.currentLsn());
        }
    }
}
