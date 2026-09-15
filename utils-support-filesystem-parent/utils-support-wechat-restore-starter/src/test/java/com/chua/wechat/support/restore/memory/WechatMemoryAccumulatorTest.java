package com.chua.wechat.support.restore.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WechatMemoryAccumulator} 跨次扫描累积测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatMemoryAccumulatorTest {

    @TempDir
    File tempDir;

    @Test
    void mergeWithoutStoreShouldReturnCurrentRunOnly() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        WechatMemoryAccumulator.Snapshot snapshot =
                WechatMemoryAccumulator.merge(result(msg(100, 0x5000L, 1, "1000", "hello")), store, true);
        assertEquals(1, snapshot.messages().size());
        assertEquals(1, snapshot.merged().records().size());
        assertEquals(1, snapshot.addedMessages());
    }

    @Test
    void accumulateDisabledShouldIgnoreExistingStore() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        WechatMemoryAccumulator.save(
                WechatMemoryAccumulator.merge(result(msg(100, 0x5000L, 1, "1000", "old")), store, true),
                store);
        // accumulate=false：即便累积文件里有历史消息，也不合并进来
        WechatMemoryAccumulator.Snapshot snapshot =
                WechatMemoryAccumulator.merge(result(msg(100, 0x5000L, 1, "2000", "new")), store, false);
        assertEquals(1, snapshot.messages().size());
        assertEquals("new", snapshot.messages().get(0).content());
    }

    @Test
    void repeatedScanOfSamePageShouldNotDuplicate() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        WechatMemoryExtractor.ExtractResult run = result(msg(100, 0x5000L, 1, "1000", "hello"));
        WechatMemoryAccumulator.save(WechatMemoryAccumulator.merge(run, store, true), store);

        // 再扫一次，页地址变了（每次运行都会变），但内容相同 → 不应重复
        WechatMemoryAccumulator.Snapshot second = WechatMemoryAccumulator.merge(
                result(msg(100, 0x9000L, 1, "1000", "hello")), store, true);
        assertEquals(0, second.addedMessages());
        assertEquals(0, second.addedRecords());
        assertEquals(1, second.messages().size());
    }

    @Test
    void newMessagesShouldAppendToExisting() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        WechatMemoryAccumulator.save(WechatMemoryAccumulator.merge(
                result(msg(100, 0x5000L, 1, "1000", "第一条")), store, true), store);

        WechatMemoryAccumulator.Snapshot second = WechatMemoryAccumulator.merge(
                result(msg(100, 0x8000L, 1, "2000", "第二条")), store, true);
        assertEquals(1, second.addedMessages());
        assertEquals(2, second.messages().size());
        // 按时间升序
        assertEquals("第一条", second.messages().get(0).content());
        assertEquals("第二条", second.messages().get(1).content());
    }

    @Test
    void escapingShouldSurviveRoundTrip() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        String nasty = "第一行\n第二行\\反斜杠\u0001SOH ,\"逗号引号";
        WechatMemoryAccumulator.save(WechatMemoryAccumulator.merge(
                result(msg(100, 0x5000L, 1, "1000", nasty)), store, true), store);

        // 累积文件必须是「一行一条」，换行不能破坏行结构
        long lines = Files.readAllLines(store.toPath(), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("msg" + '\u0001')).count();
        assertEquals(1, lines);

        // 重新加载后内容应完全一致（含分隔符 SOH —— 它被转义成 \s，不能有损）
        WechatMemoryAccumulator.Snapshot reloaded = WechatMemoryAccumulator.merge(
                result(contact(100, 0x7000L)), store, true);
        assertEquals(1, reloaded.messages().size());
        assertEquals(nasty, reloaded.messages().get(0).content());
    }

    @Test
    void recordsShouldBeAccumulatedAndDeduplicated() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        WechatMemoryExtractor.ExtractResult run = result(contact(100, 0x7000L));
        WechatMemoryAccumulator.save(WechatMemoryAccumulator.merge(run, store, true), store);

        WechatMemoryAccumulator.Snapshot second = WechatMemoryAccumulator.merge(run, store, true);
        assertEquals(0, second.addedRecords());
        assertEquals(1, second.merged().records().size());
    }

    @Test
    void unknownTableShouldRoundTripAsNull() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        WechatMemoryAccumulator.save(WechatMemoryAccumulator.merge(
                result(record(100, 0x5000L, null, 11, "a"),
                        record(100, 0x5000L, null, 12, "b", "c")), store, true), store);

        WechatMemoryAccumulator.Snapshot reloaded = WechatMemoryAccumulator.merge(
                result(contact(100, 0x7000L)), store, true);
        int checked = 0;
        for (WechatMemoryExtractor.ExtractedRecord record : reloaded.merged().records()) {
            if (record.rowid() == 11 || record.rowid() == 12) {
                // 存盘后是空串，读回必须是 null，否则会被当成表名
                assertNull(record.table());
                checked++;
            }
        }
        assertEquals(2, checked);
    }

    @Test
    void rebuildShouldSkipRecordsWithUnknownTable() throws Exception {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(record(100, 0x5000L, null, 1, "a"));
        records.add(record(100, 0x5000L, "", 2, "b", "c"));
        records.add(contact(100, 0x7000L));
        WechatMemoryExtractor.ExtractResult result = new WechatMemoryExtractor.ExtractResult(
                new ArrayList<>(), records, Map.of(), new ArrayList<>());

        File db = new File(tempDir, "rebuild.db");
        WechatMemoryRebuilder.rebuild(result, db);

        List<String> tables = new ArrayList<>();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + db.getAbsolutePath());
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        // 不能凭空多出名为 ""、"_2" 的垃圾表
        assertEquals(List.of("contact"), tables);
    }

    @Test
    void describeShouldReportStoreSize() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        assertTrue(WechatMemoryAccumulator.describe(store).contains("无累积文件"));
        WechatMemoryAccumulator.save(WechatMemoryAccumulator.merge(
                result(msg(100, 0x5000L, 1, "1000", "hi")), store, true), store);
        assertTrue(WechatMemoryAccumulator.describe(store).contains("1 条消息"));
    }

    @Test
    void fingerprintShouldIgnorePageAddress() throws Exception {
        // 页地址每次运行都变，指纹里不能带它，否则累积会失效
        String first = WechatMemoryMessages.Message.fingerprintOf(record(100, 0x5000L, "Msg_All", 7,
                "1", "1000", "hello"));
        String second = WechatMemoryMessages.Message.fingerprintOf(record(100, 0x9000L, "Msg_All", 7,
                "1", "1000", "hello"));
        assertEquals(first, second);
    }

    @Test
    void unresolvedSenderShouldBeUpgradedByLaterScan() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);

        // 第一次扫描：消息页上的 sender_id=9 不被任何库簇覆盖（覆盖它的 Name2Id 页在别的进程里，
        // 这次还没扫到），于是发送者解析不出来 —— 逐进程落盘时这是常态
        WechatMemoryAccumulator.Snapshot first = WechatMemoryAccumulator.merge(
                result(cluster(100, 0x1000L, 1, 2, 3), msg(100, 0x5000L, 9, "1000", "hello")),
                store, true);
        assertEquals(1, first.messages().size());
        assertEquals("", first.messages().get(0).username());

        // 第二次扫描：同一个库簇这次带上了 id=9，同一条消息解析出了发送者。
        // 指纹相同 → 不能新增一条，而要「原地覆盖」掉那条没有发送者的，
        // 否则先入为主的空发送者版本会永远占着位置
        WechatMemoryAccumulator.Snapshot second = WechatMemoryAccumulator.merge(
                result(cluster(100, 0x1000L, 1, 2, 3, 4, 5, 6, 7, 8, 9),
                        msg(100, 0x9000L, 9, "1000", "hello")),
                store, true);
        assertEquals(0, second.addedMessages());
        assertEquals(1, second.messages().size());
        assertEquals("wxid_9", second.messages().get(0).username());
    }

    @Test
    void upgradingSenderShouldNotDuplicateMessages() throws Exception {
        File store = new File(tempDir, WechatMemoryAccumulator.FILE_NAME);
        List<WechatMemoryExtractor.IdCluster> narrow = cluster(100, 0x1000L, 1, 2, 3);
        List<WechatMemoryExtractor.IdCluster> wide = cluster(100, 0x1000L, 1, 2, 3, 9);
        try (WechatMemoryAccumulator accumulator = WechatMemoryAccumulator.open(store, true)) {
            // 逐进程落盘会连续 add 多次，中间夹着「发送者升级」与「按时间重排」。
            // 升级若按「列表下标」回写，下标会被重排冲掉：升级 P 时写到了 R 的位置，
            // 结果 R 被顶掉、P 出现两份（实测：359 行里只有 256 个唯一指纹）
            accumulator.add(result(narrow, msg(100, 0x5000L, 9, "1000", "P")));
            accumulator.add(result(narrow, msg(100, 0x5000L, 2, "5000", "Q")));
            accumulator.add(result(narrow, msg(100, 0x5000L, 9, "500", "R")));
            WechatMemoryAccumulator.Snapshot snapshot = accumulator.add(
                    result(wide, msg(100, 0x5000L, 9, "1000", "P")));

            assertEquals(3, snapshot.messages().size());
            assertEquals(List.of("R", "P", "Q"),
                    snapshot.messages().stream()
                            .map(WechatMemoryMessages.Message::content).toList());
            assertEquals("wxid_9", snapshot.messages().get(1).username());
        }
    }

    @Test
    void extractEachShouldEmitEmptyResultWhenNoProcess() throws Exception {
        // 一个进程都没扫到也必须回调一次，让调用方拿到「空结果」而不是 null
        List<WechatMemoryExtractor.ExtractResult> seen = new ArrayList<>();
        WechatMemoryExtractor.extractEach(List.of(), List.of(), false, seen::add);
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).records().isEmpty());
    }

    /**
     * 构造一条 {@code Msg_All} 记录。
     *
     * @param pid        进程号
     * @param page       页地址
     * @param senderId   {@code real_sender_id}
     * @param createTime 创建时间（秒）
     * @param content    消息正文
     * @return 提取记录
     */
    private static WechatMemoryExtractor.ExtractedRecord msg(int pid, long page, int senderId,
                                                            String createTime, String content) {
        // 必须凑满 17 列：real_sender_id=4 / create_time=5 / message_content=12
        String[] values = new String[17];
        values[0] = "";
        values[1] = "1";
        values[2] = "1";
        values[3] = "0";
        values[4] = Integer.toString(senderId);
        values[5] = createTime;
        values[12] = content;
        int[] types = new int[17];
        for (int i = 0; i < types.length; i++) {
            types[i] = 13;
        }
        return new WechatMemoryExtractor.ExtractedRecord(pid, page,
                WechatMemoryRebuilder.MSG_TABLE, senderId, values, types);
    }

    /**
     * 构造一条 {@code contact} 记录（用于构造「没有消息的本次扫描」）。
     *
     * @param pid  进程号
     * @param page 页地址
     * @return 提取记录
     */
    private static WechatMemoryExtractor.ExtractedRecord contact(int pid, long page) {
        return record(pid, page, "contact", 1, "1", "wxid_a", "张三");
    }

    /**
     * 构造一条提取记录。
     *
     * @param pid    进程号
     * @param page   页地址
     * @param table  表名
     * @param rowid  行号
     * @param values 各列值
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
     * 构造一个只含指定记录的提取结果（带一个覆盖 id 1..3 的库簇）。
     *
     * @param records 记录
     * @return 提取结果
     */
    private static WechatMemoryExtractor.ExtractResult result(
            WechatMemoryExtractor.ExtractedRecord... records) {
        List<WechatMemoryExtractor.ExtractedRecord> list = new ArrayList<>();
        for (WechatMemoryExtractor.ExtractedRecord record : records) {
            list.add(record);
        }
        Map<Integer, String> idMap = new LinkedHashMap<>();
        idMap.put(1, "wxid_self");
        idMap.put(2, "wxid_b");
        idMap.put(3, "wxid_c");
        List<Long> pages = new ArrayList<>();
        pages.add(0x1000L);
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(new WechatMemoryExtractor.IdCluster(100, 0x1000L, pages, idMap));
        return new WechatMemoryExtractor.ExtractResult(new ArrayList<>(), list, Map.of(), clusters);
    }

    /**
     * 构造一个只含指定记录的提取结果（自带指定库簇）。
     *
     * @param clusters 库簇
     * @param records  记录
     * @return 提取结果
     */
    private static WechatMemoryExtractor.ExtractResult result(
            List<WechatMemoryExtractor.IdCluster> clusters,
            WechatMemoryExtractor.ExtractedRecord... records) {
        List<WechatMemoryExtractor.ExtractedRecord> list = new ArrayList<>();
        for (WechatMemoryExtractor.ExtractedRecord record : records) {
            list.add(record);
        }
        return new WechatMemoryExtractor.ExtractResult(new ArrayList<>(), list, Map.of(), clusters);
    }

    /**
     * 构造一个覆盖指定 id 的库簇。
     *
     * @param pid  进程号
     * @param page 页地址
     * @param ids  id 空间
     * @return 只含该簇的列表
     */
    private static List<WechatMemoryExtractor.IdCluster> cluster(int pid, long page, int... ids) {
        Map<Integer, String> idMap = new LinkedHashMap<>();
        for (int id : ids) {
            idMap.put(id, id == 1 ? "wxid_self" : "wxid_" + id);
        }
        List<Long> pages = new ArrayList<>();
        pages.add(page);
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(new WechatMemoryExtractor.IdCluster(pid, page, pages, idMap));
        return clusters;
    }
}
