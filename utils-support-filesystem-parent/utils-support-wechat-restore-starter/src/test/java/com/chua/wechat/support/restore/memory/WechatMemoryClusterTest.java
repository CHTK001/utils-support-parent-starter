package com.chua.wechat.support.restore.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WechatMemoryExtractor} 的 Name2Id 库簇构建与合并测试。
 *
 * <p>锁住两个实测踩过的坑：</p>
 * <ol>
 *   <li>真正的 {@code Name2Id} 是<b>1 列</b>表（{@code name2id(username TEXT PRIMARY KEY)}），
 *       只认 2 列会把整张表丢掉；</li>
 *   <li>同一张表会被 SQLite 切成多个叶子页，而页可能被<b>不同进程</b>各缓存一部分，
 *       此时必须按「id 空间互补且恰好拼成 1..max」合并，不能依赖地址邻近。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatMemoryClusterTest {

    /**
     * one列Name2ID应当FormCluster。
     */
    @Test
    void oneColumnName2IdShouldFormCluster() {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            records.add(record(100, 0x1000L, "name2id", id, "wxid_user" + id));
        }
        List<WechatMemoryExtractor.IdCluster> clusters = WechatMemoryExtractor.buildClusters(records);
        assertEquals(1, clusters.size());
        assertEquals(3, clusters.getFirst().idMap().size());
        assertEquals("wxid_user2", clusters.getFirst().idMap().get(2));
    }

    /**
     * two列Name2ID应当AlsoFormCluster。
     */
    @Test
    void twoColumnName2IdShouldAlsoFormCluster() {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(record(100, 0x2000L, "Name2Id", 1, "wxid_self", "0"));
        records.add(record(100, 0x2000L, "Name2Id", 2, "gh_official", "1"));
        List<WechatMemoryExtractor.IdCluster> clusters = WechatMemoryExtractor.buildClusters(records);
        assertEquals(1, clusters.size());
        assertEquals(2, clusters.getFirst().idMap().size());
        assertEquals("gh_official", clusters.getFirst().idMap().get(2));
    }

    /**
     * non用户Two列表应当BeIgnored。
     */
    @Test
    void nonUserTwoColumnTableShouldBeIgnored() {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(record(100, 0x3000L, "name2id", 1, "key_a", "v_a"));
        records.add(record(100, 0x3000L, "name2id", 2, "key_b", "v_b"));
        assertTrue(WechatMemoryExtractor.buildClusters(records).isEmpty());
    }

    /**
     * 未进入库簇的 name2id 记录应被撤销归属，其余记录保持不变。
     */
    @Test
    void unclusteredName2IdRecordsShouldBeUnattributed() {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        // 真正的 Name2Id 页：名字像用户标识
        for (int id = 1; id <= 3; id++) {
            records.add(record(100, 0x1000L, "name2id", id, "wxid_user" + id));
        }
        // 误归属页：单列 MD5，列数恰好撞上另一种 name2id 的 schema
        records.add(record(100, 0x9000000L, "name2id", 1, "efc4e520416ec7b73c1fe517329a828b"));
        records.add(record(100, 0x9000000L, "name2id", 2, "2026-07"));
        // 同页的其它表不受影响
        records.add(record(100, 0x9000000L, "contact", 7, "wxid_real"));

        List<WechatMemoryExtractor.ExtractedRecord> kept =
                WechatMemoryExtractor.pruneUntrustedName2id(records);

        assertEquals(6, kept.size());
        for (int id = 1; id <= 3; id++) {
            assertEquals("name2id", kept.get(id - 1).table());
        }
        // 误归属页整页置 null，重建器会按「无法判定归属」丢弃
        assertNull(kept.get(3).table());
        assertNull(kept.get(4).table());
        assertEquals("contact", kept.get(5).table());
    }

    /**
     * 分布在不同进程的两个合法 Name2Id 页都应保留表名。
     *
     * <p>合并后的库簇只保留第一个簇的 pid，因此可信度不能按「pid + 页地址」从库里簇反推 ——
     * 那样第二个进程的真实 Name2Id 页会被当成误归属删掉。</p>
     */
    @Test
    void legitSingleColumnName2IdPageShouldSurvivePrune() {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(record(100, 0x1000L, "name2id", 1, "wxid_self"));
        records.add(record(200, 0x8000000L, "Name2Id", 2, "47652409291@chatroom"));

        List<WechatMemoryExtractor.ExtractedRecord> kept =
                WechatMemoryExtractor.pruneUntrustedName2id(records);

        assertEquals(2, kept.size());
        assertEquals("name2id", kept.get(0).table());
        assertEquals("Name2Id", kept.get(1).table());
    }

    /**
     * complementaryIDSpaces应当合并EvenWhenPagesAreFarApart。
     */
    @Test
    void complementaryIdSpacesShouldMergeEvenWhenPagesAreFarApart() {
        // 地址相距极远（>2MB），第一遍按地址邻近归并不会合并，必须靠 id 空间互补合并
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(cluster(100, 0x1000L, "wxid_self", 1, 2, 3));
        clusters.add(cluster(200, 0x8000000L, "wxid_other", 4, 5, 6));
        List<WechatMemoryExtractor.IdCluster> merged = WechatMemoryExtractor.mergeContiguous(clusters);
        assertEquals(1, merged.size());
        assertEquals(6, merged.getFirst().idMap().size());
        // 合并后 id 空间连续覆盖 1..6，两端的名字都在
        assertEquals("wxid_self", merged.getFirst().idMap().get(1));
        assertEquals("user6", merged.getFirst().idMap().get(6));
        assertEquals(2, merged.getFirst().pages().size());
    }

    /**
     * overlappingIDSpaces应当Not合并。
     */
    @Test
    void overlappingIdSpacesShouldNotMerge() {
        // 每个库的 id 都从 1 开始，重叠即异库
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(cluster(100, 0x1000L, "wxid_self", 1, 2, 3));
        clusters.add(cluster(100, 0x9000000L, "wxid_other", 2, 3, 4));
        assertEquals(2, WechatMemoryExtractor.mergeContiguous(clusters).size());
    }

    /**
     * nonContiguousIDSpaces应当Not合并。
     */
    @Test
    void nonContiguousIdSpacesShouldNotMerge() {
        // 合起来不是完整的 1..max（中间有空洞），说明不是同一张表被切碎
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(cluster(100, 0x1000L, "wxid_self", 1, 2, 3));
        clusters.add(cluster(100, 0x9000000L, "wxid_other", 10, 11, 12));
        assertEquals(2, WechatMemoryExtractor.mergeContiguous(clusters).size());
    }

    /**
     * chain合并应当JoinThreeFragments。
     */
    @Test
    void chainMergeShouldJoinThreeFragments() {
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(cluster(100, 0x1000L, "wxid_self", 1, 2));
        clusters.add(cluster(200, 0x8000000L, "wxid_b", 3, 4));
        clusters.add(cluster(300, 0xF000000L, "wxid_c", 5, 6));
        List<WechatMemoryExtractor.IdCluster> merged = WechatMemoryExtractor.mergeContiguous(clusters);
        assertEquals(1, merged.size());
        assertEquals(6, merged.getFirst().idMap().size());
    }

    /**
     * nearbyPagesWithOverlappingIDSpaces应当StaySeparate。
     */
    @Test
    void nearbyPagesWithOverlappingIdSpacesShouldStaySeparate() {
        // 实测三个相距不到 1MB 的页却分属两个库 —— 地址会交错，id 空间不会骗人
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(record(100, 0x1000L, "name2id", 1, "wxid_self"));
        records.add(record(100, 0x1010L, "name2id", 1, "47652409291@chatroom"));
        records.add(record(100, 0x1020L, "name2id", 2, "gh_official"));
        List<WechatMemoryExtractor.IdCluster> clusters = WechatMemoryExtractor.buildClusters(records);
        assertEquals(2, clusters.size());
    }

    /**
     * 构造一条提取记录。
     *
     * @param pid     进程号
     * @param page    页地址
     * @param table   表名
     * @param rowid   行号
     * @param values  各列值
     * @return 提取记录
     */
    private static WechatMemoryExtractor.ExtractedRecord record(int pid, long page, String table,
                                                               long rowid, String... values) {
        int[] types = new int[values.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = 13;
        }
        return new WechatMemoryExtractor.ExtractedRecord(pid, page, table, rowid, values, types);
    }

    /**
     * 构造一个库簇。
     *
     * @param pid      进程号
     * @param page     页地址
     * @param selfName id=1 对应的名字
     * @param ids      id 列表
     * @return 库簇
     */
    private static WechatMemoryExtractor.IdCluster cluster(int pid, long page, String selfName,
                                                          Integer... ids) {
        Map<Integer, String> idMap = new LinkedHashMap<>();
        for (Integer id : ids) {
            idMap.put(id, id == 1 ? selfName : "user" + id);
        }
        List<Long> pages = new ArrayList<>();
        pages.add(page);
        return new WechatMemoryExtractor.IdCluster(pid, page, pages, idMap);
    }
}
