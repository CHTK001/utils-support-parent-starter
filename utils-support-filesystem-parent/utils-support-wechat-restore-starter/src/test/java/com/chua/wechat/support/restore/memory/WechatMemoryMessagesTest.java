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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WechatMemoryMessages} 消息视图测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatMemoryMessagesTest {

    @TempDir
    File tempDir;

    @Test
    void shouldResolveSenderNameByCoverage() throws Exception {
        // 库簇 id 空间 1..3；消息页只出现 1 和 3 → 应全部解析
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(msg(100, 0x5000L, 1, "1000", "你增加了我的工作量"));
        records.add(msg(100, 0x5000L, 3, "2000", "什么，你吃了我的饭"));
        WechatMemoryExtractor.ExtractResult result = result(records,
                cluster(100, 0x1000L, idMap(1, "wxid_self", 2, "wxid_b", 3, "wxid_c")));

        int rows = WechatMemoryMessages.write(result, tempDir);
        assertEquals(2, rows);

        List<String> lines = Files.readAllLines(
                new File(tempDir, WechatMemoryMessages.FILE_NAME).toPath(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("page,cluster,sender_id,sender_username,sender_name,"));
        // 按时间升序
        assertTrue(lines.get(1).contains("wxid_self"));
        assertTrue(lines.get(1).contains("你增加了我的工作量"));
        assertTrue(lines.get(2).contains("wxid_c"));
    }

    @Test
    void shouldLeaveSenderEmptyWhenNoClusterCovers() throws Exception {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(msg(100, 0x5000L, 99, "1000", "孤儿消息"));
        WechatMemoryExtractor.ExtractResult result = result(records,
                cluster(100, 0x1000L, idMap(1, "wxid_self", 2, "wxid_b")));

        assertEquals(1, WechatMemoryMessages.write(result, tempDir));
        String csv = Files.readString(
                new File(tempDir, WechatMemoryMessages.FILE_NAME).toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("孤儿消息"));
        // sender_username 与 sender_name 都应为空
        String row = csv.split("\n")[1];
        String[] cells = row.split(",", -1);
        assertEquals("99", cells[2]);
        assertEquals("", cells[3]);
    }

    @Test
    void shouldPreferSameProcessCluster() throws Exception {
        // 两个库簇 id 空间相同、分属不同进程；页在 pid 100 → 必须选 pid 100 的那个
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(msg(100, 0x5000L, 1, "1000", "hello"));
        List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
        clusters.add(cluster(200, 0x1000L, idMap(1, "wxid_other")));
        clusters.add(cluster(100, 0x9000000L, idMap(1, "wxid_mine")));
        WechatMemoryExtractor.ExtractResult result = result(records, clusters);

        WechatMemoryMessages.write(result, tempDir);
        String csv = Files.readString(
                new File(tempDir, WechatMemoryMessages.FILE_NAME).toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("wxid_mine"));
        assertFalse(csv.contains("wxid_other"));
    }

    @Test
    void shouldEscapeCommaAndQuoteInContent() throws Exception {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(msg(100, 0x5000L, 1, "1000", "a,b\"c"));
        WechatMemoryExtractor.ExtractResult result = result(records,
                cluster(100, 0x1000L, idMap(1, "wxid_self")));

        WechatMemoryMessages.write(result, tempDir);
        String csv = Files.readString(
                new File(tempDir, WechatMemoryMessages.FILE_NAME).toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"a,b\"\"c\""));
    }

    @Test
    void shouldWriteIdMap() throws Exception {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(msg(100, 0x5000L, 1, "1000", "hi"));
        WechatMemoryExtractor.ExtractResult result = result(records,
                cluster(100, 0x1000L, idMap(1, "wxid_self", 2, "wxid_b")));

        WechatMemoryMessages.write(result, tempDir);
        String csv = Files.readString(
                new File(tempDir, WechatMemoryMessages.ID_MAP_FILE_NAME).toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("cluster,pid,page,sender_id,username\n"));
        assertTrue(csv.contains("wxid_b"));
    }

    @Test
    void shouldWriteSelfContainedHtmlReport() throws Exception {
        List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();
        records.add(msg(100, 0x5000L, 1, "1000", "自己发的"));
        records.add(msg(100, 0x5000L, 2, "2000", "</script><b>x</b>"));
        WechatMemoryExtractor.ExtractResult result = result(records,
                cluster(100, 0x1000L, idMap(1, "wxid_self", 2, "wxid_b")));

        File html = new File(tempDir, WechatMemoryMessages.REPORT_FILE_NAME);
        assertEquals(2, WechatMemoryMessages.writeHtml(result, html));

        String text = Files.readString(html.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("<!DOCTYPE html>"));
        assertTrue(text.contains("const DATA = ["));
        // 无外部资源引用
        assertFalse(text.contains("src=\"http"));
        assertFalse(text.contains("<link "));
        // 正文里的 </script> 必须被转义，不能提前闭合脚本块
        assertFalse(text.contains("</script><b>x</b>"));
        assertTrue(text.contains("u003c/script"));
        // 本人消息应被标为 self
        assertTrue(text.contains("\"s\":1"));
    }

    @Test
    void shouldFormatTime() {
        // 不锁定具体时区：只校验格式与非法输入降级
        assertTrue(WechatMemoryMessages.formatTime("1788937213")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertEquals("", WechatMemoryMessages.formatTime(""));
        assertEquals("", WechatMemoryMessages.formatTime("abc"));
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
     * 构造一个库簇。
     *
     * @param pid  进程号
     * @param page 页地址
     * @param map  id → 用户名
     * @return 库簇
     */
    private static WechatMemoryExtractor.IdCluster cluster(int pid, long page, Map<Integer, String> map) {
        List<Long> pages = new ArrayList<>();
        pages.add(page);
        return new WechatMemoryExtractor.IdCluster(pid, page, pages, map);
    }

    /**
     * 构造提取结果。
     *
     * @param records  记录
     * @param clusters 库簇
     * @return 提取结果
     */
    private static WechatMemoryExtractor.ExtractResult result(
            List<WechatMemoryExtractor.ExtractedRecord> records,
            WechatMemoryExtractor.IdCluster... clusters) {
        List<WechatMemoryExtractor.IdCluster> list = new ArrayList<>();
        for (WechatMemoryExtractor.IdCluster cluster : clusters) {
            list.add(cluster);
        }
        return result(records, list);
    }

    /**
     * 构造提取结果。
     *
     * @param records  记录
     * @param clusters 库簇
     * @return 提取结果
     */
    private static WechatMemoryExtractor.ExtractResult result(
            List<WechatMemoryExtractor.ExtractedRecord> records,
            List<WechatMemoryExtractor.IdCluster> clusters) {
        return new WechatMemoryExtractor.ExtractResult(new ArrayList<>(), records, Map.of(), clusters);
    }

    /**
     * 构造 id → 用户名 映射。
     *
     * @param pairs id 与用户名交替
     * @return 映射
     */
    private static Map<Integer, String> idMap(Object... pairs) {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Integer) pairs[i], (String) pairs[i + 1]);
        }
        return map;
    }
}
