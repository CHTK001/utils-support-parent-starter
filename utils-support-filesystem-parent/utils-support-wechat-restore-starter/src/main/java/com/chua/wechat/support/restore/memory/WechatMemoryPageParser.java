package com.chua.wechat.support.restore.memory;

import com.chua.wechat.support.restore.WechatBlobCodec;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 微信内存明文页解析器。
 *
 * <p>微信运行时，SQLCipher 会把解密后的明文页留在 SQLite 的 pager cache 里，
 * 这些页在内存中表现为标准的 SQLite b-tree 页。本类在任意字节缓冲区里
 * <b>不依赖页号滑窗识别叶子表页</b>（{@code 0x0D}），并把页里的单元格解析成记录。</p>
 *
 * <h3>页级严格校验（关键，否则会出大量伪页）</h3>
 *
 * <p>只校验「单元格指针落在内容区内」远远不够 —— 实测会出现 3 个页、每页 400 行，
 * 内容全是 {@code |blob[2]} 的伪页。必须按 SQLite 的 local-payload 公式算出每个单元格的
 * 真实长度，然后要求：</p>
 * <ol>
 *   <li>首格起点 == 内容区起点；</li>
 *   <li>相邻单元格无缝衔接（{@code off[k] + size[k] == off[k+1]}）；</li>
 *   <li>末格正好顶到可用区末尾（{@code USABLE}）。</li>
 * </ol>
 *
 * <p>三条都满足的记为「严格页」；只满足「互不重叠」的记为「宽松页」（页里有 freeblock，
 * 即有已删除行），由调用方决定是否采信。</p>
 *
 * <h3>记录级严格校验</h3>
 *
 * <p>序列类型数组必须<b>完全消费 payload</b>（{@code body == end}），至少 1 个非空列，
 * 遇到序列类型 10/11（SQLite 内部保留）直接判非法。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatMemoryPageParser {

    /**
     * 页大小
     */
    public static final int PAGE_SIZE = 4096;

    /**
     * SQLCipher 保留区（IV 16 字节 + HMAC 64 字节）
     */
    public static final int RESERVE = 80;

    /**
     * 页可用区大小
     */
    public static final int USABLE = PAGE_SIZE - RESERVE;

    /**
     * 单个单元格内联 payload 上限
     */
    public static final int MAX_LOCAL = USABLE - 35;

    /**
     * 溢出时保留的最小内联 payload
     */
    public static final int MIN_LOCAL = ((USABLE - 12) * 32 / 255) - 23;

    /**
     * 叶子表页类型字节
     */
    private static final int PAGE_TYPE_LEAF_TABLE = 0x0D;

    /**
     * 单页最多单元格数（防伪页）
     */
    private static final int MAX_CELLS = 1200;

    private WechatMemoryPageParser() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 叶子表页。
     *
     * @param address 该页在目标进程中的起始地址
     * @param strict  是否通过严格铺满校验
     * @param records 解析出的记录
     */
    public record LeafPage(long address, boolean strict, List<LeafRecord> records) {
    }

    /**
     * 一条记录。
     *
     * @param rowid       行号
     * @param values      各列的值（已转成字符串，NULL 为空串）
     * @param serialTypes SQLite 序列类型
     */
    public record LeafRecord(long rowid, String[] values, int[] serialTypes) {

        /**
         * 列数。
         *
         * @return 列数
         */
        public int columnCount() {
            return values.length;
        }
    }

    /**
     * 表结构（从 {@code sqlite_master} 的 CREATE TABLE 语句还原）。
     *
     * @param name         表名
     * @param columns      列名
     * @param declarations 列声明类型
     * @param ddl          原始 CREATE TABLE 语句（重建库时直接使用）
     */
    public record TableSchema(String name, List<String> columns, List<String> declarations,
                              String ddl) {
    }

    /**
     * 在缓冲区里滑窗扫描叶子表页。
     *
     * @param buffer      内存缓冲
     * @param baseAddress 缓冲区起始地址（用于回填页地址）
     * @param decodeBlob  是否解压 blob 列
     * @return 叶子页列表
     */
    public static List<LeafPage> scan(byte[] buffer, long baseAddress, boolean decodeBlob) {
        List<LeafPage> pages = new ArrayList<>();
        if (buffer == null || buffer.length < PAGE_SIZE) {
            return pages;
        }
        int limit = buffer.length - PAGE_SIZE;
        int[] cells = new int[1];
        int[] contentStart = new int[1];
        for (int i = 0; i <= limit; i++) {
            if ((buffer[i] & 0xFF) != PAGE_TYPE_LEAF_TABLE) {
                continue;
            }
            int kind = inspectPage(buffer, i, cells, contentStart);
            if (kind == 0) {
                continue;
            }
            List<LeafRecord> records = new ArrayList<>(cells[0]);
            int[] out = new int[2];
            for (int c = 0; c < cells[0]; c++) {
                int pointer = u16(buffer, i + 8 + c * 2);
                if (pointer < contentStart[0] || pointer >= USABLE) {
                    continue;
                }
                int size = cellSize(buffer, i + pointer, out);
                if (size <= 0) {
                    continue;
                }
                int[] pos = {i + pointer};
                long payloadLen = varint(buffer, pos);
                long rowid = varint(buffer, pos);
                if (payloadLen < 0 || rowid < 0) {
                    continue;
                }
                LeafRecord record = decodeRecord(buffer, pos[0], out[1], payloadLen, rowid, decodeBlob);
                if (record != null) {
                    records.add(record);
                }
            }
            if (!records.isEmpty()) {
                pages.add(new LeafPage(baseAddress + i, kind == 1, records));
            }
        }
        return pages;
    }

    /**
     * 检查偏移处是否是一个合法的叶子表页。
     *
     * @param buffer       缓冲
     * @param offset       页起始偏移
     * @param cellsOut     输出：单元格数
     * @param contentOut   输出：内容区起点
     * @return 0=非法；1=严格；2=宽松
     */
    private static int inspectPage(byte[] buffer, int offset, int[] cellsOut, int[] contentOut) {
        if (offset + PAGE_SIZE > buffer.length) {
            return 0;
        }
        int cells = u16(buffer, offset + 3);
        if (cells <= 0 || cells > MAX_CELLS) {
            return 0;
        }
        int contentStart = u16(buffer, offset + 5);
        if (contentStart < 8 + cells * 2 || contentStart > USABLE) {
            return 0;
        }
        // 首页（page 1）的 b-tree 页头在 offset 100，滑窗时无法区分；
        // 但要求碎片字节为 0 已足以过滤掉绝大多数伪页
        if ((buffer[offset + 7] & 0xFF) != 0) {
            return 0;
        }
        int firstFree = u16(buffer, offset + 1);
        if (firstFree != 0 && (firstFree < contentStart || firstFree >= USABLE)) {
            return 0;
        }

        int[] offsets = new int[cells];
        int[] sizes = new int[cells];
        Integer[] index = new Integer[cells];
        int[] out = new int[2];
        for (int c = 0; c < cells; c++) {
            int pointer = u16(buffer, offset + 8 + c * 2);
            if (pointer < contentStart || pointer >= USABLE) {
                return 0;
            }
            int size = cellSize(buffer, offset + pointer, out);
            if (size <= 0 || pointer + size > USABLE) {
                return 0;
            }
            offsets[c] = pointer;
            sizes[c] = size;
            index[c] = c;
        }
        Arrays.sort(index, (x, y) -> Integer.compare(offsets[x], offsets[y]));

        boolean strict = offsets[index[0]] == contentStart;
        int cursor = contentStart;
        for (int k = 0; k < cells && strict; k++) {
            if (offsets[index[k]] != cursor) {
                strict = false;
                break;
            }
            cursor += sizes[index[k]];
        }
        if (strict && cursor != USABLE) {
            strict = false;
        }
        if (!strict) {
            int previousEnd = contentStart;
            for (int k = 0; k < cells; k++) {
                if (offsets[index[k]] < previousEnd) {
                    return 0;
                }
                previousEnd = offsets[index[k]] + sizes[index[k]];
            }
        }
        cellsOut[0] = cells;
        contentOut[0] = contentStart;
        return strict ? 1 : 2;
    }

    /**
     * 按 SQLite 规则计算单元格总长度，并把内联 payload 长度写入 {@code out[1]}。
     *
     * @param buffer 缓冲
     * @param at     单元格起始偏移
     * @param out    输出：out[0]=payload 总长，out[1]=内联长度
     * @return 单元格字节数；-1 表示非法
     */
    private static int cellSize(byte[] buffer, int at, int[] out) {
        int[] pos = {at};
        long payload = varint(buffer, pos);
        if (payload < 0) {
            return -1;
        }
        long rowid = varint(buffer, pos);
        if (rowid < 0) {
            return -1;
        }
        int header = pos[0] - at;
        if (payload == 0 || payload > 0x7FFFFFFFL) {
            return -1;
        }
        int local;
        int overflowPointer = 0;
        if (payload <= MAX_LOCAL) {
            local = (int) payload;
        } else {
            long surplus = MIN_LOCAL + (payload - MIN_LOCAL) % (USABLE - 4);
            local = surplus <= MAX_LOCAL ? (int) surplus : MIN_LOCAL;
            overflowPointer = 4;
        }
        out[0] = (int) payload;
        out[1] = local;
        return header + local + overflowPointer;
    }

    /**
     * 解析一条记录。
     *
     * @param buffer     缓冲
     * @param at         payload 起始偏移
     * @param length     内联 payload 长度
     * @param payloadLen payload 总长（用于判断是否走溢出页）
     * @param rowid      行号
     * @param decodeBlob 是否解压 blob 列
     * @return 记录；非法返回 null
     */
    private static LeafRecord decodeRecord(byte[] buffer, int at, int length, long payloadLen,
                                           long rowid, boolean decodeBlob) {
        int end = at + length;
        if (length <= 0 || end > buffer.length) {
            return null;
        }
        int[] pos = {at};
        long headerSize = varint(buffer, pos);
        if (headerSize <= 0 || headerSize > length) {
            return null;
        }
        int headerEnd = at + (int) headerSize;
        if (headerEnd > end) {
            return null;
        }
        List<Long> types = new ArrayList<>(16);
        while (pos[0] < headerEnd) {
            long type = varint(buffer, pos);
            if (type < 0) {
                return null;
            }
            types.add(type);
        }
        if (pos[0] != headerEnd || types.isEmpty()) {
            return null;
        }

        int body = headerEnd;
        String[] values = new String[types.size()];
        int[] serialTypes = new int[types.size()];
        for (int i = 0; i < types.size(); i++) {
            long type = types.get(i);
            serialTypes[i] = (int) type;
            if (type == 0) {
                values[i] = "";
            } else if (type >= 1 && type <= 6) {
                int width = new int[]{0, 1, 2, 3, 4, 6, 8}[(int) type];
                if (body + width > end) {
                    return null;
                }
                long value = 0;
                for (int k = 0; k < width; k++) {
                    value = (value << 8) | (buffer[body + k] & 0xFF);
                }
                if (width == 8) {
                    values[i] = Long.toString(value);
                } else {
                    int shift = 64 - width * 8;
                    values[i] = Long.toString((value << shift) >> shift);
                }
                body += width;
            } else if (type == 7) {
                if (body + 8 > end) {
                    return null;
                }
                long bits = 0;
                for (int k = 0; k < 8; k++) {
                    bits = (bits << 8) | (buffer[body + k] & 0xFF);
                }
                values[i] = Double.toString(Double.longBitsToDouble(bits));
                body += 8;
            } else if (type == 8) {
                values[i] = "0";
            } else if (type == 9) {
                values[i] = "1";
            } else if (type >= 12) {
                int length2 = (int) ((type - 12) / 2);
                if (body + length2 > end) {
                    return null;
                }
                if ((type & 1) == 1) {
                    values[i] = WechatBlobCodec.sanitize(
                            new String(buffer, body, length2, StandardCharsets.UTF_8));
                } else {
                    values[i] = decodeBlob
                            ? WechatBlobCodec.describe(Arrays.copyOfRange(buffer, body, body + length2))
                            : ("blob[" + length2 + "]");
                }
                body += length2;
            } else {
                // 10 / 11 是 SQLite 内部保留序列类型，出现即说明这不是合法记录
                return null;
            }
        }
        // 严格：payload 必须被完全消费。payloadLen > MAX_LOCAL 的记录走溢出页，
        // 内联部分本来就被截断，此时跳过该检查
        if (payloadLen <= MAX_LOCAL && body != end) {
            return null;
        }
        int nonEmpty = 0;
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                nonEmpty++;
            }
        }
        if (nonEmpty == 0) {
            return null;
        }
        return new LeafRecord(rowid, values, serialTypes);
    }

    // ==================== schema 还原与表归属 ====================

    /**
     * 从记录里扫出 {@code sqlite_master} 的 CREATE TABLE 语句，建立表模型。
     *
     * @param records 全部记录
     * @return 表名 → 表结构
     */
    public static Map<String, TableSchema> buildSchema(Collection<LeafRecord> records) {
        Map<String, TableSchema> tables = new LinkedHashMap<>();
        for (LeafRecord record : records) {
            for (String value : record.values()) {
                if (value == null || value.length() < 16) {
                    continue;
                }
                String ddl = value.trim();
                if (!ddl.toUpperCase(Locale.ROOT).startsWith("CREATE TABLE ")) {
                    continue;
                }
                TableSchema schema = parseCreateTable(ddl);
                if (schema == null) {
                    continue;
                }
                TableSchema old = tables.get(schema.name());
                if (old == null || old.columns().size() < schema.columns().size()) {
                    tables.put(schema.name(), schema);
                }
            }
        }
        return tables;
    }

    /**
     * 解析 CREATE TABLE 语句。
     *
     * @param ddl 语句
     * @return 表结构；无法解析返回 null
     */
    private static TableSchema parseCreateTable(String ddl) {
        int leftParen = ddl.indexOf('(');
        int rightParen = ddl.lastIndexOf(')');
        if (leftParen < 0 || rightParen <= leftParen) {
            return null;
        }
        String head = ddl.substring(0, leftParen).trim();
        int nameAt = head.toUpperCase(Locale.ROOT).indexOf("CREATE TABLE ");
        if (nameAt < 0) {
            return null;
        }
        String name = head.substring(nameAt + 13).trim();
        if (name.toUpperCase(Locale.ROOT).startsWith("IF NOT EXISTS")) {
            name = name.substring(13).trim();
        }
        name = unquote(name);
        if (name.isEmpty()) {
            return null;
        }

        List<String> columns = new ArrayList<>(16);
        List<String> declarations = new ArrayList<>(16);
        for (String part : splitTopLevel(ddl.substring(leftParen + 1, rightParen))) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            String upper = item.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CONSTRAINT ") || upper.startsWith("PRIMARY KEY")
                    || upper.startsWith("UNIQUE") || upper.startsWith("CHECK")
                    || upper.startsWith("FOREIGN KEY")) {
                continue;
            }
            String[] tokens = item.split("\\s+", 2);
            String column = unquote(tokens[0]);
            if (column.isEmpty()) {
                continue;
            }
            String declaration = "";
            if (tokens.length > 1) {
                String rest = tokens[1].trim().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                if (rest.matches("[A-Z]+(\\([0-9,\\s]+\\))?")) {
                    declaration = rest;
                }
            }
            columns.add(column);
            declarations.add(declaration);
        }
        if (columns.isEmpty()) {
            return null;
        }
        return new TableSchema(name, columns, declarations, ddl);
    }

    /**
     * 按顶层逗号切分（忽略括号内、引号内的逗号）。
     *
     * @param text 文本
     * @return 片段列表
     */
    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>(16);
        int depth = 0;
        char quote = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                sb.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * 去掉标识符两侧的引号。
     *
     * @param text 文本
     * @return 去引号后的文本
     */
    private static String unquote(String text) {
        String result = text.trim();
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '`' && last == '`') || (first == '[' && last == ']')) {
                result = result.substring(1, result.length() - 1);
            }
        }
        return result;
    }

    /**
     * 判断记录属于哪张表。
     *
     * <p><b>冲突数是硬约束，打分只用于同冲突数时排序。</b>只按「列数匹配 + 类型相容度打分」
     * 会让列数恰好相同的表吞掉无关记录 —— 实测 14 列的记录<b>全部</b>被塞进
     * {@code transferTable}（485 行误报），改成以冲突数为硬约束后收敛到 1 行真实记录。</p>
     *
     * @param record  记录
     * @param schemas 表模型
     * @return 表名；无法判定返回 null
     */
    public static String attribute(LeafRecord record, Map<String, TableSchema> schemas) {
        String best = null;
        int bestConflicts = Integer.MAX_VALUE;
        int bestScore = -1;
        for (TableSchema schema : schemas.values()) {
            if (schema.columns().size() != record.columnCount()) {
                continue;
            }
            int score = 0;
            int conflicts = 0;
            for (int i = 0; i < record.columnCount(); i++) {
                int compatible = compat(schema.declarations().get(i), record.serialTypes()[i]);
                score += compatible;
                if (compatible == 0) {
                    conflicts++;
                }
            }
            if (conflicts < bestConflicts || (conflicts == bestConflicts && score > bestScore)) {
                bestConflicts = conflicts;
                bestScore = score;
                best = schema.name();
            }
        }
        if (best == null) {
            return null;
        }
        // 允许极少量冲突：SQLite 是动态类型，且微信会把压缩 blob 塞进 TEXT 列
        if (bestConflicts > Math.max(1, record.columnCount() / 10)) {
            return null;
        }
        // 同一 schema 的多张 Msg_xxx 表无法从单页区分（会话名在别的库里），
        // 统一归到 Msg_* 便于后续按 rowid 关联
        return best.startsWith("Msg_") ? "Msg_*" : best;
    }

    /**
     * 声明类型与实际序列类型的相容度。
     *
     * @param declaration 声明类型
     * @param type        序列类型
     * @return 2=吻合，1=可接受，0=冲突
     */
    private static int compat(String declaration, int type) {
        if (type == 0) {
            return 1;
        }
        boolean text = type >= 13 && (type & 1) == 1;
        boolean blob = type >= 12 && (type & 1) == 0;
        boolean integer = (type >= 1 && type <= 6) || type == 8 || type == 9;
        boolean real = type == 7;
        if (declaration == null || declaration.isEmpty()) {
            return 1;
        }
        String upper = declaration.toUpperCase(Locale.ROOT);
        if (upper.contains("TEXT") || upper.contains("CHAR") || upper.contains("CLOB")) {
            // 微信会把 zstd 压缩后的 blob 直接存进 TEXT 列（如 Msg.source），故 blob 不算冲突
            return text ? 2 : (blob ? 1 : 0);
        }
        if (upper.contains("BLOB")) {
            return blob ? 2 : (text ? 1 : 0);
        }
        if (upper.contains("INT")) {
            return integer ? 2 : (real ? 1 : 0);
        }
        if (upper.contains("REAL") || upper.contains("FLOA") || upper.contains("DOUB")) {
            return real ? 2 : (integer ? 1 : 0);
        }
        return 1;
    }

    // ==================== 基础读取 ====================

    /**
     * 读取 varint（SQLite 变长整数，最多 9 字节）。
     *
     * @param buffer 缓冲
     * @param pos    读写位置（会前移）
     * @return 值；越界或超长返回 -1
     */
    private static long varint(byte[] buffer, int[] pos) {
        long result = 0;
        for (int i = 0; i < 9; i++) {
            if (pos[0] >= buffer.length) {
                return -1;
            }
            int b = buffer[pos[0]++] & 0xFF;
            if (i == 8) {
                result = (result << 8) | b;
                return result;
            }
            result = (result << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        return -1;
    }

    /**
     * 读大端 16 位整数。
     *
     * @param buffer 缓冲
     * @param at     偏移
     * @return 值
     */
    private static int u16(byte[] buffer, int at) {
        return ((buffer[at] & 0xFF) << 8) | (buffer[at + 1] & 0xFF);
    }
}
