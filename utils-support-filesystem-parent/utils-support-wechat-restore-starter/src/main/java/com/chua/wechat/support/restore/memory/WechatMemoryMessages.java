package com.chua.wechat.support.restore.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 微信内存提取结果 → 可读消息视图（CSV + 自包含 HTML）。
 *
 * <p>{@code Msg_*} 表的 {@code real_sender_id} 是<b>库内自增 id</b>，取值含义依赖它所属的那个库
 * （每库的 id 空间独立，id=1 恒为「自己」）。所有 {@code Msg_*} 记录在重建时被合并进
 * {@link WechatMemoryRebuilder#MSG_TABLE} 一张表，因此<b>光看 id 无法还原发送者</b>。</p>
 *
 * <h3>解法：按「页」归属库簇</h3>
 *
 * <p>SQLite 的叶子页不会跨表，一页里的记录必然属于同一个库。于是：</p>
 * <ol>
 *   <li>取一个消息页里<b>全部</b> {@code real_sender_id}；</li>
 *   <li>对每个 {@link WechatMemoryExtractor.IdCluster} 算<b>覆盖度</b>
 *       （这些 id 有多少比例落在该簇的 id 空间里）；</li>
 *   <li>取覆盖度为 100% 的簇；若有多个，按「<b>同进程优先</b>，再比页地址距离」取最优的一个。</li>
 * </ol>
 *
 * <p>覆盖度是<b>硬约束</b>，地址距离只是<b>次级排序</b>：实测同一库的 Name2Id 页与消息页可以
 * 相距 190MB 以上，而三个相距不到 1MB 的页却分属两个库 —— <b>地址会交错，id 空间不会骗人</b>。</p>
 *
 * <p>库簇的 id 空间是按进程隔离的（不同进程虚拟地址空间互不相通），所以同进程优先；
 * 只有当同一张表的页被多个进程各缓存一部分时（见
 * {@link WechatMemoryExtractor#mergeContiguous(List)}）才退化为跨进程匹配。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatMemoryMessages {

    /**
    * 输出的消息文件名
    */
    public static final String FILE_NAME = "messages.csv";

    /**
    * 输出的 id 映射文件名
    */
    public static final String ID_MAP_FILE_NAME = "id_map.csv";

    /**
    * 输出的 HTML 报告文件名
    */
    public static final String REPORT_FILE_NAME = "report.html";

    /**
    * CSV 表头
    */
    private static final String CSV_HEADER =
            "page,cluster,sender_id,sender_username,sender_name,create_time,local_type,message_content";

    /**
    * {@code Msg_*} 表里 {@code real_sender_id} 的列序号
    */
    private static final int COL_REAL_SENDER_ID = 4;

    /**
    * {@code Msg_*} 表里 {@code create_time} 的列序号
    */
    private static final int COL_CREATE_TIME = 5;

    /**
    * {@code Msg_*} 表里 {@code local_type} 的列序号
    */
    private static final int COL_LOCAL_TYPE = 2;

    /**
    * {@code Msg_*} 表里 {@code message_content} 的列序号
    */
    private static final int COL_CONTENT = 12;

    /**
    * {@code contact} 表里 {@code username} 的列序号
    */
    private static final int CONTACT_USERNAME = 1;

    /**
    * {@code contact} 表里 {@code remark} 的列序号
    */
    private static final int CONTACT_REMARK = 8;

    /**
    * {@code contact} 表里 {@code nick_name} 的列序号
    */
    private static final int CONTACT_NICK_NAME = 11;

    /**
    * HTML 报告里单条正文的最大长度（防止个别超长 XML 把页面撑爆）
    */
    private static final int MAX_CONTENT = 4000;

    /**
     * 构造方法，创建 WechatMemoryMessages 实例。
     */
    private WechatMemoryMessages() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
    * 解析出的一条可读消息。
    *
    * @param page        来源页标识（{@code pid#地址}；同一页属于同一张会话表）
    * @param cluster     归属库簇标识
    * @param senderId    库内自增的 {@code real_sender_id}
    * @param username    发送者用户名（未解析出来为空串）
    * @param displayName 显示名（备注优先，其次昵称）
    * @param createTime  秒级时间戳
    * @param localType   消息类型
    * @param content     消息正文
    * @param fingerprint 去重指纹（{@code rowid + SOH + 各列}），跨次扫描累积时用它判重。
    *                    注意<b>不含页地址</b> —— 页地址每次运行都会变，不能作为身份
    */
    public record Message(String page, String cluster, String senderId, String username,
                          String displayName, String createTime, String localType, String content,
                          String fingerprint) {

        /**
        * 计算消息指纹。
        *
        * @param record 原始记录
        * @return 指纹
        */
        static String fingerprintOf(WechatMemoryExtractor.ExtractedRecord record) {
            StringBuilder sb = new StringBuilder(64).append(record.rowid()).append('\u0001');
            String[] values = record.values();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sb.append('\u0001');
                }
                sb.append(values[i] == null ? "" : values[i]);
            }
            return sb.toString();
        }
    }

    /**
    * 输出可读消息视图（CSV）与 id 映射表。
    *
    * @param result  提取结果
    * @param outDir  输出目录
    * @return 写出的消息行数
    * @throws Exception 写文件失败
    */
    public static int write(WechatMemoryExtractor.ExtractResult result, File outDir) throws Exception {
        Files.createDirectories(outDir.toPath());
        List<Message> messages = resolve(result);
        writeCsv(messages, new File(outDir, FILE_NAME));
        writeIdMap(result, new File(outDir, ID_MAP_FILE_NAME));
        log.info("已写出消息视图: {} （{} 条消息 / {} 个消息页 / {} 条未解析出发送者）",
                new File(outDir, FILE_NAME).getAbsolutePath(), messages.size(),
                distinctPages(messages), countUnresolved(messages));
        return messages.size();
    }

    /**
    * 把已解析好的消息写成 CSV。
    *
    * @param messages 消息列表（调用方保证已排序）
    * @param outFile  目标文件
    * @return 写出的消息行数
    * @throws Exception 写文件失败
    */
    public static int writeCsv(List<Message> messages, File outFile) throws Exception {
        File parent = outFile.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (Message message : messages) {
            csv.append(csvRow(new String[]{message.page(), message.cluster(), message.senderId(),
                    message.username(), message.displayName(), message.createTime(),
                    message.localType(), message.content()}));
        }
        Files.writeString(outFile.toPath(), csv.toString(), StandardCharsets.UTF_8);
        return messages.size();
    }

    /**
    * 输出自包含的 HTML 聊天记录报告（无需任何外部资源，双击即可看）。
    *
    * @param result  提取结果
    * @param outFile 目标 HTML 文件
    * @return 报告里的消息条数
    * @throws Exception 写文件失败
    */
    public static int writeHtml(WechatMemoryExtractor.ExtractResult result, File outFile)
            throws Exception {
        return writeHtml(resolve(result), outFile);
    }

    /**
    * 把已解析好的消息写成自包含 HTML 报告。
    *
    * @param messages 消息列表
    * @param outFile  目标 HTML 文件
    * @return 报告里的消息条数
    * @throws Exception 写文件失败
    */
    public static int writeHtml(List<Message> messages, File outFile) throws Exception {
        File parent = outFile.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.writeString(outFile.toPath(), renderHtml(messages), StandardCharsets.UTF_8);
        log.info("已写出聊天记录报告: {} （{} 条消息 / {} 条未解析出发送者）",
                outFile.getAbsolutePath(), messages.size(), countUnresolved(messages));
        return messages.size();
    }

    /**
    * 解析出全部可读消息（按时间升序，时间缺失的排在最后）。
    *
    * @param result 提取结果
    * @return 消息列表
    */
    static List<Message> resolve(WechatMemoryExtractor.ExtractResult result) {
        Map<String, String> displayNames = collectDisplayNames(result);

        // 消息页 → 该页全部 real_sender_id
        Map<String, Set<Integer>> sendersOfPage = new LinkedHashMap<>();
        // 消息页 → 该页的记录
        Map<String, List<WechatMemoryExtractor.ExtractedRecord>> recordsOfPage = new LinkedHashMap<>();
        for (WechatMemoryExtractor.ExtractedRecord record : result.records()) {
            if (!WechatMemoryRebuilder.MSG_TABLE.equals(record.table())) {
                continue;
            }
            String key = record.pid() + "#" + record.pageAddress();
            recordsOfPage.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            Integer sender = intValue(record.values(), COL_REAL_SENDER_ID);
            if (sender != null) {
                sendersOfPage.computeIfAbsent(key, k -> new HashSet<>()).add(sender);
            }
        }

        // 消息页 → 库簇
        Map<String, WechatMemoryExtractor.IdCluster> ownerOfPage = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : sendersOfPage.entrySet()) {
            WechatMemoryExtractor.IdCluster cluster =
                    resolveCluster(entry.getKey(), entry.getValue(), result.clusters());
            if (cluster != null) {
                ownerOfPage.put(entry.getKey(), cluster);
            }
        }

        List<Message> messages = new ArrayList<>(recordsOfPage.size() * 4);
        for (Map.Entry<String, List<WechatMemoryExtractor.ExtractedRecord>> entry
                : recordsOfPage.entrySet()) {
            WechatMemoryExtractor.IdCluster cluster = ownerOfPage.get(entry.getKey());
            String clusterKey = cluster == null ? "" : cluster.pid() + "@0x"
                    + Long.toHexString(cluster.pageAddress());
            for (WechatMemoryExtractor.ExtractedRecord record : entry.getValue()) {
                Integer senderId = intValue(record.values(), COL_REAL_SENDER_ID);
                String username = senderId == null || cluster == null
                        ? null : cluster.idMap().get(senderId);
                messages.add(new Message(entry.getKey(), clusterKey,
                        senderId == null ? "" : Integer.toString(senderId),
                        username == null ? "" : username,
                        displayNames.getOrDefault(username, ""),
                        text(record.values(), COL_CREATE_TIME),
                        text(record.values(), COL_LOCAL_TYPE),
                        text(record.values(), COL_CONTENT),
                        Message.fingerprintOf(record)));
            }
        }
        messages.sort(Comparator.comparingLong(m -> parseLong(m.createTime(), Long.MAX_VALUE)));
        return messages;
    }

    /**
    * 统计去重后的消息页数。
    *
    * @param messages 消息列表
    * @return 页数
    */
    private static long distinctPages(List<Message> messages) {
        Set<String> pages = new HashSet<>();
        for (Message message : messages) {
            pages.add(message.page());
        }
        return pages.size();
    }

    /**
    * 统计未解析出发送者的条数。
    *
    * @param messages 消息列表
    * @return 条数
    */
    private static long countUnresolved(List<Message> messages) {
        long count = 0;
        for (Message message : messages) {
            if (message.username().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
    * 为一个消息页挑选库簇。
    *
    * @param pageKey  页标识（{@code pid#地址}）
    * @param senders  该页全部 {@code real_sender_id}
    * @param clusters 全部库簇
    * @return 最匹配的库簇；无法判定返回 null
    */
    private static WechatMemoryExtractor.IdCluster resolveCluster(
            String pageKey, Set<Integer> senders,
            List<WechatMemoryExtractor.IdCluster> clusters) {
        if (senders.isEmpty()) {
            return null;
        }
        int pid = pidOf(pageKey);
        long address = addressOf(pageKey);
        WechatMemoryExtractor.IdCluster best = null;
        long bestRank = Long.MAX_VALUE;
        for (WechatMemoryExtractor.IdCluster cluster : clusters) {
            // 覆盖度是硬约束：该页出现的所有 sender id 必须全部落在同一个簇的 id 空间里。
            // 某页最大 sender id 为 146 时，只有 id 空间 1..220 的簇容得下。
            boolean covered = true;
            for (Integer sender : senders) {
                if (!cluster.idMap().containsKey(sender)) {
                    covered = false;
                    break;
                }
            }
            if (!covered) {
                continue;
            }
            long distance = Long.MAX_VALUE;
            for (long page : cluster.pages()) {
                distance = Math.min(distance, Math.abs(page - address));
            }
            // 同进程优先：不同进程的虚拟地址空间互不相通，同进程的归属更可信。
            // 跨进程只在没有同进程候选时才使用（同一张表的页确实可能被多个进程各缓存一部分）。
            long rank = (cluster.pid() == pid ? 0L : 1L) * (Long.MAX_VALUE / 2) + distance;
            if (best == null || rank < bestRank) {
                best = cluster;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
    * 从 {@code contact} 表收集显示名。
    *
    * @param result 提取结果
    * @return username → 显示名（备注优先，其次昵称）
    */
    private static Map<String, String> collectDisplayNames(WechatMemoryExtractor.ExtractResult result) {
        Map<String, String> names = new LinkedHashMap<>();
        for (WechatMemoryExtractor.ExtractedRecord record : result.records()) {
            if (record.table() == null || !"contact".equalsIgnoreCase(record.table())) {
                continue;
            }
            String username = text(record.values(), CONTACT_USERNAME);
            if (username.isEmpty()) {
                continue;
            }
            String remark = text(record.values(), CONTACT_REMARK);
            String nick = text(record.values(), CONTACT_NICK_NAME);
            String display = !remark.isEmpty() ? remark : nick;
            if (!display.isEmpty()) {
                names.putIfAbsent(username, display);
            }
        }
        return names;
    }

    /**
    * 输出各库簇的 id → username 映射，便于人工核对归属是否正确。
    *
    * @param result  提取结果
    * @param outFile 目标文件
    * @throws Exception 写文件失败
    */
    public static void writeIdMap(WechatMemoryExtractor.ExtractResult result, File outFile)
            throws Exception {
        StringBuilder csv = new StringBuilder("cluster,pid,page,sender_id,username\n");
        for (WechatMemoryExtractor.IdCluster cluster : result.clusters()) {
            String key = cluster.pid() + "@0x" + Long.toHexString(cluster.pageAddress());
            for (Map.Entry<Integer, String> entry : cluster.idMap().entrySet()) {
                csv.append(csvRow(new String[]{key, Integer.toString(cluster.pid()),
                        "0x" + Long.toHexString(cluster.pageAddress()),
                        Integer.toString(entry.getKey()), entry.getValue()}));
            }
        }
        Files.writeString(outFile.toPath(), csv.toString(), StandardCharsets.UTF_8);
    }

    /**
    * 渲染自包含的 HTML 报告。
    *
    * @param messages 消息列表
    * @return HTML 文本
    */
    private static String renderHtml(List<Message> messages) {
        String self = detectSelf(messages);
        StringBuilder data = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String name = message.displayName().isEmpty() ? message.username()
                    : message.displayName();
            String time = formatTime(message.createTime());
            String content = message.content();
            if (content.length() > MAX_CONTENT) {
                content = content.substring(0, MAX_CONTENT) + "…";
            }
            if (i > 0) {
                data.append(',');
            }
            data.append('{')
                    .append("\"t\":").append(json(time)).append(',')
                    .append("\"d\":").append(json(time.length() >= 10 ? time.substring(0, 10) : "")).append(',')
                    .append("\"u\":").append(json(message.username())).append(',')
                    .append("\"n\":").append(json(name)).append(',')
                    .append("\"c\":").append(json(content)).append(',')
                    .append("\"s\":").append(self != null && self.equals(message.username()) ? 1 : 0)
                    .append('}');
        }
        data.append(']');
        return TEMPLATE.replace("__DATA__", data.toString());
    }

    /**
    * 找出「本人」的用户名：id=1 且形如 {@code wxid_} 的那个。
    *
    * <p>每个库的 id=1 都是「自己」，但不同库的 id 空间独立，所以要按用户名而不是 id 来判定。</p>
    *
    * @param messages 消息列表
    * @return 本人用户名；找不到返回 null
    */
    private static String detectSelf(List<Message> messages) {
        for (Message message : messages) {
            if ("1".equals(message.senderId()) && message.username().startsWith("wxid_")) {
                return message.username();
            }
        }
        return null;
    }

    /**
    * 取整型列值。
    *
    * @param values 值数组
    * @param index  列序号
    * @return 整型值；越界或非数字返回 null
    */
    private static Integer intValue(String[] values, int index) {
        String value = text(values, index);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
    * 取字符串列值。
    *
    * @param values 值数组
    * @param index  列序号
    * @return 列值；越界返回空串
    */
    private static String text(String[] values, int index) {
        if (values == null || index < 0 || index >= values.length || values[index] == null) {
            return "";
        }
        return values[index];
    }

    /**
    * 解析长整型。
    *
    * @param value        文本
    * @param defaultValue 缺省值
    * @return 解析结果
    */
    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
    * 组装一行 CSV。
    *
    * @param values 各列
    * @return CSV 行（含换行）
    */
    private static String csvRow(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(values[i]));
        }
        return sb.append('\n').toString();
    }

    /**
    * 转义 CSV 字段。消息正文常含逗号、引号与换行，必须整体加引号。
    *
    * @param value 原值
    * @return 转义后的值
    */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replace('\r', ' ');
        if (sanitized.indexOf(',') < 0 && sanitized.indexOf('"') < 0
                && sanitized.indexOf('\n') < 0) {
            return sanitized;
        }
        return '"' + sanitized.replace("\"", "\"\"") + '"';
    }

    /**
    * 把字符串编码成 JSON 字面量（用于内嵌到 HTML 的 {@code <script>} 里）。
    *
    * @param value 原值
    * @return JSON 字面量
    */
    private static String json(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '<':
                    // 避免正文里的 </script> 提前闭合脚本块
                    sb.append("\\u003c");
                    break;
                case '>':
                    sb.append("\\u003e");
                    break;
                case '&':
                    sb.append("\\u0026");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.append('"').toString();
    }

    /**
    * 解析页标识里的进程号。
    *
    * @param key 页标识
    * @return 进程号
    */
    private static int pidOf(String key) {
        int at = key.indexOf('#');
        return at < 0 ? 0 : Integer.parseInt(key.substring(0, at));
    }

    /**
    * 解析页标识里的地址。
    *
    * @param key 页标识
    * @return 地址
    */
    private static long addressOf(String key) {
        int at = key.indexOf('#');
        return at < 0 ? 0 : Long.parseLong(key.substring(at + 1));
    }

    /**
    * 把时间戳格式化成可读文本（缺省保留原值）。
    *
    * @param epochSeconds 秒级时间戳
    * @return 可读时间；非法返回空串
    */
    static String formatTime(String epochSeconds) {
        long seconds = parseLong(epochSeconds, -1L);
        if (seconds <= 0) {
            return "";
        }
        return java.time.Instant.ofEpochSecond(seconds)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",
                        Locale.ROOT));
    }

    /**
    * 自包含 HTML 模板（无外部资源，双击即可查看）。
    *
    * <p>样式与脚本都内联；数据通过 {@code __DATA__} 占位符注入。</p>
    */
    private static final String TEMPLATE = String.join("\n",
            "<!DOCTYPE html>",
            "<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">",
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
            "<title>微信聊天记录还原报告</title><style>",
            ":root{--bg:#f5f6f8;--card:#fff;--line:#e3e6ea;--txt:#1f2328;--dim:#6b7280;"
                    + "--me:#d3f0c8;--accent:#07c160}",
            "*{box-sizing:border-box}",
            "body{margin:0;background:var(--bg);color:var(--txt);font:14px/1.6 "
                    + "-apple-system,BlinkMacSystemFont,\"Segoe UI\",\"Microsoft YaHei\",sans-serif}",
            "header{position:sticky;top:0;z-index:9;background:rgba(255,255,255,.92);"
                    + "backdrop-filter:blur(8px);border-bottom:1px solid var(--line);padding:14px 20px}",
            "h1{margin:0 0 6px;font-size:17px;font-weight:600}",
            ".meta{color:var(--dim);font-size:12.5px}",
            ".stats{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}",
            ".stat{background:#eef1f4;border-radius:6px;padding:3px 10px;font-size:12px;color:#374151}",
            ".stat b{color:#111}",
            "main{max-width:1080px;margin:0 auto;padding:18px 20px 60px}",
            ".bar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px}",
            "input[type=search]{flex:1;min-width:220px;padding:8px 12px;border:1px solid var(--line);"
                    + "border-radius:8px;font-size:13px;outline:none}",
            "input[type=search]:focus{border-color:var(--accent)}",
            "select{padding:8px 10px;border:1px solid var(--line);border-radius:8px;font-size:13px;"
                    + "background:#fff}",
            ".day{margin:22px 0 8px;text-align:center}",
            ".day span{background:#dadfe4;color:#4b5563;font-size:12px;border-radius:10px;padding:2px 12px}",
            ".msg{display:flex;gap:10px;margin:10px 0;align-items:flex-start}",
            ".msg.self{flex-direction:row-reverse}",
            ".av{width:34px;height:34px;flex:0 0 34px;border-radius:6px;background:#c9ced6;color:#fff;"
                    + "font-size:13px;font-weight:600;display:flex;align-items:center;justify-content:center}",
            ".msg.self .av{background:var(--accent)}",
            ".bub{max-width:min(72%,720px)}",
            ".nm{font-size:11.5px;color:var(--dim);margin:0 2px 3px}",
            ".msg.self .nm{text-align:right}",
            ".body{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:8px 11px;"
                    + "white-space:pre-wrap;word-break:break-word}",
            ".msg.self .body{background:var(--me);border-color:#bde3ad}",
            ".body.sys{color:var(--dim);font-size:12.5px;background:#f0f2f5}",
            ".body.xml{font-family:ui-monospace,Consolas,monospace;font-size:12px;color:#4b5563}",
            ".tm{font-size:11px;color:#9aa1ab;margin:3px 2px 0}",
            ".msg.self .tm{text-align:right}",
            ".empty{color:var(--dim);text-align:center;padding:40px 0}",
            "</style></head><body>",
            "<header><h1>微信聊天记录还原报告</h1>",
            "<div class=\"meta\">数据来源：微信进程内存中 SQLCipher 的 pager cache 明文页"
                    + "（无需数据库密钥，只含微信已缓存过的页）</div>",
            "<div class=\"stats\" id=\"stats\"></div></header>",
            "<main><div class=\"bar\">",
            "<input type=\"search\" id=\"q\" placeholder=\"搜索正文 / 发送者…\">",
            "<select id=\"who\"><option value=\"\">全部发送者</option></select></div>",
            "<div id=\"list\"></div></main>",
            "<script>",
            "const DATA = __DATA__;",
            "const list=document.getElementById('list'),q=document.getElementById('q'),",
            "  who=document.getElementById('who'),stats=document.getElementById('stats');",
            "(function(){",
            "  const names=[...new Set(DATA.map(m=>m.n||m.u).filter(Boolean))]",
            "    .sort((a,b)=>a.localeCompare(b,'zh'));",
            "  for(const n of names){const o=document.createElement('option');o.value=n;o.textContent=n;who.appendChild(o);}",
            "  const days=new Set(DATA.map(m=>m.d)).size;",
            "  const span=DATA.length?DATA[0].t.slice(0,10)+' ~ '+DATA[DATA.length-1].t.slice(0,10):'-';",
            "  stats.innerHTML=`<span class=\"stat\">消息 <b>${DATA.length}</b></span>`",
            "    +`<span class=\"stat\">发送者 <b>${names.length}</b></span>`",
            "    +`<span class=\"stat\">天数 <b>${days}</b></span>`",
            "    +`<span class=\"stat\">时间跨度 <b>${span}</b></span>`;",
            "  render();",
            "})();",
            "function kind(t){t=(t||'').trim();if(!t)return 'sys';if(t[0]==='<')return 'xml';",
            "  if(/^blob\\[/.test(t))return 'sys';return '';}",
            "function esc(s){return (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));}",
            "function av(n){return esc((n||'?').replace(/^(wxid_|gh_)/,'').slice(0,2).toUpperCase());}",
            "function render(){",
            "  const kw=q.value.trim().toLowerCase(),pick=who.value;",
            "  let out='',last='',shown=0;",
            "  for(const m of DATA){",
            "    const name=m.n||m.u||'(未知)';",
            "    if(pick&&name!==pick)continue;",
            "    if(kw&&!(((m.c||'')+' '+name+' '+(m.u||'')).toLowerCase().includes(kw)))continue;",
            "    if(m.d!==last){out+=`<div class=\"day\"><span>${m.d}</span></div>`;last=m.d;}",
            "    const k=kind(m.c);",
            "    out+=`<div class=\"msg${m.s===1?' self':''}\"><div class=\"av\">${av(name)}</div>`",
            "      +`<div class=\"bub\"><div class=\"nm\">${esc(name)}</div>`",
            "      +`<div class=\"body ${k}\">${esc(m.c)||'<span style=\"color:#9aa1ab\">（空消息 / 非文本类型）</span>'}</div>`",
            "      +`<div class=\"tm\">${esc(m.t)}</div></div></div>`;",
            "    shown++;",
            "  }",
            "  list.innerHTML=out||'<div class=\"empty\">没有匹配的消息</div>';",
            "}",
            "q.addEventListener('input',render);who.addEventListener('change',render);",
            "</script></body></html>");
}
