package com.chua.wechat.support.restore.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨次扫描的累积器。
 *
 * <h3>为什么必须累积</h3>
 *
 * <p>内存明文页路线有一个绕不开的限制：<b>只能看到微信当前缓存过的页</b>。
 * pager cache 会被换出，一次扫描拿到的永远只是「用户最近翻过的那部分聊天」。
 * 所以正确的用法是<b>反复扫描、不断累积</b> —— 在微信里翻一段、扫一次，
 * 输出目录里的结果就长一段，最终逼近全量。</p>
 *
 * <h3>怎么去重</h3>
 *
 * <p>页地址（{@code pid#地址}）每次运行都会变，<b>不能</b>用来判重。真正稳定的身份是：</p>
 * <ul>
 *   <li>记录：{@code rowid + SOH + 各列值}（{@code rowid} 就是表内的 {@code local_id}，稳定）；</li>
 *   <li>消息：同一条记录的指纹（见 {@link WechatMemoryMessages.Message#fingerprint()}）。</li>
 * </ul>
 *
 * <p>累积结果落在一个自描述的 TSV 文件里（字段用 SOH {@code \u0001} 分隔，
 * 换行与反斜杠做转义，因此正文里的换行不会破坏行结构）。</p>
 *
 * <h3>消息为什么要单独存</h3>
 *
 * <p>发送者名字是靠「页 → 库簇」的覆盖度在<b>扫描当时</b>解析出来的。上一次扫描的页在本次
 * 运行里已经不在内存里，无法重新解析，所以累积的是<b>已解析好的消息</b>，
 * 而不是原始记录 —— 否则历史消息的发送者会全部丢失。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatMemoryAccumulator implements AutoCloseable {

    /**
    * 累积文件名
    */
    public static final String FILE_NAME = "memory_accumulated.tsv";

    /**
    * 字段分隔符。绝不能用 {@code |} 或制表符 —— 解压出来的 XML 正文里满是竖线
    */
    private static final char SEP = '\u0001';

    /**
    * 记录行标记
    */
    private static final String TAG_RECORD = "rec";

    /**
    * 消息行标记
    */
    private static final String TAG_MESSAGE = "msg";

    /**
    * 累积文件
    */
    private final File store;

    /**
    * 是否落盘
    */
    private final boolean persist;

    /**
    * 已累积的内容
    */
    private final Store state;

    private WechatMemoryAccumulator(File store, boolean persist, Store state) {
        this.store = store;
        this.persist = persist;
        this.state = state;
    }

    /**
    * 打开一个累积会话。
    *
    * @param store      累积文件
    * @param accumulate 是否累积；false 时既不读也不写累积文件
    * @return 累积会话
    * @throws Exception 读文件失败
    */
    public static WechatMemoryAccumulator open(File store, boolean accumulate) throws Exception {
        Store state = accumulate && store != null && store.isFile() ? load(store) : new Store();
        return new WechatMemoryAccumulator(store, accumulate, state);
    }

    /**
    * 一次累积后的完整快照。
    *
    * @param merged   合并后的提取结果（用于重建库与各表导出）
    * @param messages 合并后的可读消息（按时间升序）
    * @param addedRecords  本次新增的记录数
    * @param addedMessages 本次新增的消息数
    */
    public record Snapshot(WechatMemoryExtractor.ExtractResult merged,
                           List<WechatMemoryMessages.Message> messages,
                           int addedRecords, int addedMessages) {
    }

    /**
    * 合并一批结果并<b>立即落盘</b>。
    *
    * <p>以「进程」为单位调用。内存扫描随时可能被外部打断 —— 本机实测约 1/3 的运行会在
    * 任意时刻被静默杀掉（没有异常、没有 hs_err、连导出后的最后一行日志都可能丢）。
    * 每扫完一个进程就落盘一次，被打断最多损失当前这一个进程，重跑即可接着累积。</p>
    *
    * @param one 一个进程的扫描结果
    * @return 合并后的快照
    * @throws Exception 读写失败
    */
    public Snapshot add(WechatMemoryExtractor.ExtractResult one) throws Exception {
        List<WechatMemoryMessages.Message> resolved = WechatMemoryMessages.resolve(one);
        int addedRecords = 0;
        for (WechatMemoryExtractor.ExtractedRecord record : one.records()) {
            if (state.fingerprints.add(fingerprintOf(record))) {
                state.records.add(record);
                addedRecords++;
            }
        }
        int addedMessages = 0;
        for (WechatMemoryMessages.Message message : resolved) {
            WechatMemoryMessages.Message existing =
                    state.messageByFingerprint.get(message.fingerprint());
            if (existing == null) {
                state.messageByFingerprint.put(message.fingerprint(), message);
                addedMessages++;
            } else if (isRicher(message, existing)) {
                // 同一条消息：本次扫描解析出了发送者，覆盖掉之前那条没解析出名字的
                state.messageByFingerprint.put(message.fingerprint(), message);
            }
        }
        // 进程统计与库簇「以最后一次为准」：每次 add 拿到的都是「本次运行到目前为止」的完整视图，
        // 累积起来会把同一个 pid 重复算进去，直接替换才是对的
        state.processes = new ArrayList<>(one.processes());
        state.clusters = new ArrayList<>(one.clusters());

        log.info("已合并 pid {}：新增 记录 {} / 消息 {}，累计 记录 {} / 消息 {}",
                one.processes().isEmpty()
                        ? "?" : Integer.toString(one.processes().get(one.processes().size() - 1).pid()),
                addedRecords, addedMessages, state.records.size(), state.messageByFingerprint.size());
        Snapshot snapshot = snapshot(addedRecords, addedMessages);
        if (persist) {
            save(snapshot, store);
        }
        return snapshot;
    }

    /**
    * 判断新消息是否比已累积的那条信息更全。
    *
    * <p>发送者名字是靠「页 → 库簇」的覆盖度在<b>扫描当时</b>解析的。先扫到的进程可能还没有
    * 覆盖这条消息的库簇（库簇的页在别的进程里），于是解析不出名字；等扫到持有该库簇的进程时
    * 才解析得出来。这时必须用新版本<b>原地覆盖</b>，否则会被指纹去重挡掉，
    * 留下一条永远没有发送者的记录。</p>
    *
    * @param candidate 新消息
    * @param existing  已累积的消息
    * @return 新消息更全返回 true
    */
    private static boolean isRicher(WechatMemoryMessages.Message candidate,
                                    WechatMemoryMessages.Message existing) {
        if (existing == null) {
            return true;
        }
        if (isBlank(existing.displayName()) && !isBlank(candidate.displayName())) {
            return true;
        }
        return isBlank(existing.username()) && !isBlank(candidate.username());
    }

    /**
    * 判断文本是否为空。
    *
    * @param value 文本
    * @return 空返回 true
    */
    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /**
    * 当前累计快照。
    *
    * @return 快照
    */
    public Snapshot snapshot() {
        return snapshot(0, 0);
    }

    /**
    * 构造快照。
    *
    * @param addedRecords  本次新增记录数
    * @param addedMessages 本次新增消息数
    * @return 快照
    */
    private Snapshot snapshot(int addedRecords, int addedMessages) {
        WechatMemoryExtractor.ExtractResult merged = new WechatMemoryExtractor.ExtractResult(
                state.processes, state.records, WechatMemoryExtractor.rebuildSchema(state.records),
                state.clusters);
        List<WechatMemoryMessages.Message> messages =
                new ArrayList<>(state.messageByFingerprint.values());
        messages.sort(Comparator.comparingLong(m -> parseLong(m.createTime(), Long.MAX_VALUE)));
        return new Snapshot(merged, messages, addedRecords, addedMessages);
    }

    /**
    * 便利方法：合并一次扫描结果。
    *
    * @param current    本次扫描结果
    * @param store      累积文件
    * @param accumulate 是否累积
    * @return 合并后的快照
    * @throws Exception 读写失败
    */
    public static Snapshot merge(WechatMemoryExtractor.ExtractResult current, File store,
                                 boolean accumulate) throws Exception {
        try (WechatMemoryAccumulator accumulator = open(store, accumulate)) {
            return accumulator.add(current);
        }
    }

    /**
    * 关闭会话。
    *
    * <p>每次 {@link #add} 都已经落盘，这里不再写文件；保留 {@code AutoCloseable}
    * 是为了让调用方用 try-with-resources 表达「会话」语义。</p>
    */
    @Override
    public void close() {
        // 无需释放资源：落盘已在 add 里完成
    }

    /**
    * 把快照写回累积文件。
    *
    * @param snapshot 快照
    * @param store    累积文件
    * @throws Exception 写文件失败
    */
    public static void save(Snapshot snapshot, File store) throws Exception {
        File parent = store.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("# table").append(SEP).append("rowid").append(SEP).append("v0..vN").append('\n');
        Set<String> seen = new HashSet<>();
        for (WechatMemoryExtractor.ExtractedRecord record : snapshot.merged().records()) {
            if (!seen.add(fingerprintOf(record))) {
                continue;
            }
            sb.append(TAG_RECORD).append(SEP).append(esc(record.table())).append(SEP)
                    .append(record.rowid());
            for (String value : record.values()) {
                sb.append(SEP).append(esc(value));
            }
            sb.append('\n');
        }
        sb.append("# message").append(SEP).append("fingerprint").append(SEP)
                .append("page").append(SEP).append("cluster").append(SEP).append("senderId")
                .append(SEP).append("username").append(SEP).append("displayName").append(SEP)
                .append("createTime").append(SEP).append("localType").append(SEP)
                .append("content").append('\n');
        for (WechatMemoryMessages.Message message : snapshot.messages()) {
            sb.append(TAG_MESSAGE).append(SEP).append(esc(message.fingerprint())).append(SEP)
                    .append(esc(message.page())).append(SEP).append(esc(message.cluster())).append(SEP)
                    .append(esc(message.senderId())).append(SEP).append(esc(message.username())).append(SEP)
                    .append(esc(message.displayName())).append(SEP).append(esc(message.createTime())).append(SEP)
                    .append(esc(message.localType())).append(SEP).append(esc(message.content())).append('\n');
        }
        Files.writeString(store.toPath(), sb.toString(), StandardCharsets.UTF_8);
        log.info("已写回累积文件: {} （{} 条记录 / {} 条消息）",
                store.getAbsolutePath(), snapshot.merged().records().size(),
                snapshot.messages().size());
    }

    /**
    * 读取累积文件。
    *
    * @param store 累积文件
    * @return 已累积的内容
    * @throws Exception 读文件失败
    */
    private static Store load(File store) throws Exception {
        Store result = new Store();
        for (String line : Files.readAllLines(store.toPath(), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] parts = split(line);
            if (parts.length < 2) {
                continue;
            }
            if (TAG_RECORD.equals(parts[0]) && parts.length >= 4) {
                String table = unesc(parts[1]);
                // 无法判定归属的记录（table == null）存盘后是空串，读回时必须还原成 null，
                // 否则重建库时会凭空多出名为 ""、"_2"、"_3"… 的垃圾表（实测踩过）
                if (table.isEmpty()) {
                    table = null;
                }
                long rowid = parseLong(parts[2], 0L);
                String[] values = new String[parts.length - 3];
                for (int i = 3; i < parts.length; i++) {
                    values[i - 3] = unesc(parts[i]);
                }
                WechatMemoryExtractor.ExtractedRecord record = new WechatMemoryExtractor.ExtractedRecord(
                        0, 0L, table, rowid, values, new int[values.length]);
                if (result.fingerprints.add(fingerprintOf(record))) {
                    result.records.add(record);
                }
            } else if (TAG_MESSAGE.equals(parts[0]) && parts.length >= 10) {
                WechatMemoryMessages.Message message = new WechatMemoryMessages.Message(
                        unesc(parts[2]), unesc(parts[3]), unesc(parts[4]), unesc(parts[5]),
                        unesc(parts[6]), unesc(parts[7]), unesc(parts[8]), unesc(parts[9]),
                        unesc(parts[1]));
                WechatMemoryMessages.Message existing =
                        result.messageByFingerprint.get(message.fingerprint());
                if (existing == null) {
                    result.messageByFingerprint.put(message.fingerprint(), message);
                } else if (isRicher(message, existing)) {
                    result.messageByFingerprint.put(message.fingerprint(), message);
                }
            }
        }
        return result;
    }

    /**
    * 已累积的内容。
    */
    private static final class Store {
        /**
        * 全部记录
        */
        private final List<WechatMemoryExtractor.ExtractedRecord> records = new ArrayList<>();

        /**
        * 记录指纹（表名 + rowid + 各列值）
        */
        private final Set<String> fingerprints = new HashSet<>();

        /**
        * 全部消息：指纹 → 消息。
        *
        * <p><b>必须用映射而不是「列表 + 下标」</b>。列表方案需要按下标回写以支持
        * 「解析出更全发送者时原地升级」，但每次 {@code add} 后都要按时间重排，
        * 排序会让所有已存下标<b>整体失效</b>，后续的升级就会覆盖到别的消息上，
        * 并把消息写重（实测：359 行里只有 256 个唯一指纹）。</p>
        */
        private final Map<String, WechatMemoryMessages.Message> messageByFingerprint =
                new LinkedHashMap<>();

        /**
        * 本次运行的进程扫描统计（以最后一次为准，始终是完整视图）
        */
        private List<WechatMemoryExtractor.ProcessScan> processes = new ArrayList<>();

        /**
        * 本次运行的 Name2Id 库簇（以最后一次为准）
        */
        private List<WechatMemoryExtractor.IdCluster> clusters = new ArrayList<>();
    }

    /**
    * 计算记录指纹。
    *
    * @param record 记录
    * @return 指纹
    */
    static String fingerprintOf(WechatMemoryExtractor.ExtractedRecord record) {
        StringBuilder sb = new StringBuilder(64)
                .append(record.table() == null ? "" : record.table()).append(SEP)
                .append(record.rowid()).append(SEP);
        String[] values = record.values();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(values[i] == null ? "" : values[i]);
        }
        return sb.toString();
    }

    /**
    * 按分隔符切分（不丢弃空字段）。
    *
    * @param line 行
    * @return 字段数组
    */
    private static String[] split(String line) {
        List<String> parts = new ArrayList<>(16);
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == SEP) {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[0]);
    }

    /**
    * 转义字段值。
    *
    * <p>两件事必须做对：换行要转义（否则会破坏「一行一条」的结构）；
    * <b>分隔符本身也要转义</b> —— 消息指纹就是用 SOH 拼出来的，
    * 直接把它替换成空格会让指纹在写回后对不上，累积去重整个失效（实测踩过）。</p>
    *
    * @param value 原值
    * @return 转义后的值
    */
    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case SEP:
                    sb.append("\\s");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /**
    * 反转义。
    *
    * @param value 转义后的值
    * @return 原值
    */
    private static String unesc(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 's':
                    sb.append(SEP);
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                default:
                    sb.append('\\').append(next);
                    break;
            }
        }
        return sb.toString();
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
    * 累积文件里记录数与消息数的摘要（供日志与报告使用）。
    *
    * @param store 累积文件
    * @return 摘要文本
    * @throws Exception 读文件失败
    */
    public static String describe(File store) throws Exception {
        if (store == null || !store.isFile()) {
            return "（无累积文件）";
        }
        Store loaded = load(store);
        Map<String, Integer> byTable = new LinkedHashMap<>();
        for (WechatMemoryExtractor.ExtractedRecord record : loaded.records) {
            byTable.merge(record.table() == null ? "?unknown" : record.table(), 1, Integer::sum);
        }
        return String.format("累积 %d 条记录 / %d 条消息 / %d 张表 / %.1f MB",
                loaded.records.size(), loaded.messageByFingerprint.size(), byTable.size(),
                store.length() / 1048576.0);
    }
}
