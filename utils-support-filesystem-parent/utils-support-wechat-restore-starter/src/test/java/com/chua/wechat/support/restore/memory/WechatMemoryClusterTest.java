package com.chua.wechat.support.restore.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void nonUserTwoColumnTableShouldBeIgnored() {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(record(100, 0x3000L, "name2id", 1, "key_a", "v_a"));
        records.add(record(100, 0x3000L, "name2id", 2, "key_b", "v_b"));
        assertTrue(WechatMemoryExtractor.buildClusters(records).isEmpty());
    }

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

    @Test
    void overlappingIdSpacesShouldNotMerge() {
        // 每个库的 id 都从 1 开始，重叠即异库
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(cluster(100, 0x1000L, "wxid_self", 1, 2, 3));
        clusters.add(cluster(100, 0x9000000L, "wxid_other", 2, 3, 4));
        assertEquals(2, WechatMemoryExtractor.mergeContiguous(clusters).size());
    }

    @Test
    void nonContiguousIdSpacesShouldNotMerge() {
        // 合起来不是完整的 1..max（中间有空洞），说明不是同一张表被切碎
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(cluster(100, 0x1000L, "wxid_self", 1, 2, 3));
        clusters.add(cluster(100, 0x9000000L, "wxid_other", 10, 11, 12));
        assertEquals(2, WechatMemoryExtractor.mergeContiguous(clusters).size());
    }

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
