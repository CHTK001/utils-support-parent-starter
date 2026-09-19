package com.chua.ibd.support.innodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 从 SDI 页里读表结构。
 *
 * <p>MySQL 8.0 把数据字典（表名、列、索引、默认值…）以 JSON 形式写进了每张表的
 * {@code .ibd} 里，这就是 SDI（Serialized Dictionary Information）。它不需要
 * {@code .frm}、也不需要连上 MySQL Server，因此「拿到表结构」这一步是可以完全离线的。</p>
 *
 * <h3>SDI 记录长什么样</h3>
 * <pre>
 *   sdi_type(4) + sdi_id(8) + 事务信息(13) + 解压后长度(4) + 压缩后长度(4) + zlib 数据
 * </pre>
 * <p>{@code sdi_type} 为 {@code 1} 是表定义、{@code 2} 是表空间定义。JSON 用 zlib
 * 压缩，直接 {@link Inflater} 解开即可（不需要任何第三方压缩库）。</p>
 *
 * <h3>两个反直觉的点</h3>
 * <ul>
 *   <li>SDI 页的页类型是 {@code 17853}（{@code FIL_PAGE_SDI}），不是普通索引页的
 *       {@code 17855}；但页内记录的组织方式与索引页完全一致，可以直接复用
 *       {@link IbdRecordCursor}。</li>
 *   <li>{@code ENUM} / {@code SET} 的候选值在 JSON 里是 <b>base64</b> 编码的
 *       （{@code PG} 存成 {@code UEc=}），不解码就会得到一串乱码。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdSdiReader {

    /**
     * SDI 记录类型：表定义。
     */
    public static final int SDI_TYPE_TABLE = 1;

    /**
     * SDI 记录类型：表空间定义。
     */
    public static final int SDI_TYPE_TABLESPACE = 2;

    /**
     * SDI 记录里压缩数据之前的固定头长度：{@code sdi_type(4) + sdi_id(8) + 事务信息(13)}。
     */
    private static final int SDI_RECORD_PREFIX = 12 + 13;

    /**
     * 列隐藏类型的「可见」取值。
     */
    private static final int HIDDEN_VISIBLE = 1;

    /**
     * JSON 解析器。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 工具类，禁止实例化。
     */
    private IbdSdiReader() {
    }

    /**
     * 一条 SDI 记录。
     *
     * @param type 记录类型（1 表 / 2 表空间）
     * @param id   对象 id
     * @param json 解压后的 JSON 字节
     * @return 结果值
     */
    public record SdiRecord(int type, long id, byte[] json) {

        /**
         * 取 JSON 文本。
         *
         * @return JSON 文本
         */
        public String text() {
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    /**
     * 读出表空间里所有 SDI 记录。
     *
     * @param tablespace 表空间
     * @return SDI 记录列表
     * @throws IOException 读取或解压失败
     */
    public static List<SdiRecord> readRecords(IbdTablespace tablespace) throws IOException {
        List<SdiRecord> records = new ArrayList<>();
        int pageSize = tablespace.pageSize();
        for (long pageNo : tablespace.sdiPages()) {
            byte[] page = tablespace.readPage(pageNo);
            for (IbdRecordCursor.Header header : IbdRecordCursor.chain(page, pageSize)) {
                if (!header.userRecord()) {
                    continue;
                }
                SdiRecord record = parseRecord(page, header.origin());
                if (record != null) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    /**
     * 读出表定义。
     *
     * <p>一个 {@code .ibd} 里只会有一张表（分区表除外），所以取第一条表类型的 SDI 记录。</p>
     *
     * @param tablespace 表空间
     * @return 表定义
     * @throws IOException 读取或解析失败
     */
    public static IbdTableDefinition readTableDefinition(IbdTablespace tablespace) throws IOException {
        for (SdiRecord record : readRecords(tablespace)) {
            if (record.type() == SDI_TYPE_TABLE) {
                return parseTableDefinition(record.json());
            }
        }
        throw new IOException("表空间里没有找到表定义（SDI 记录）：" + tablespace.path().getFileName()
                + "。可能该文件不是 MySQL 8.0 及以后生成的 .ibd，或者页已损坏");
    }

    /**
     * 解析一条 SDI 记录的定长头与 zlib 数据。
     *
     * @param page   所在页
     * @param origin 记录数据起点
     * @return SDI 记录；数据不完整时返回 {@code null}
     * @throws IOException 解压失败
     */
    private static SdiRecord parseRecord(byte[] page, int origin) throws IOException {
        int needed = origin + SDI_RECORD_PREFIX + 8;
        if (origin < 0 || needed > page.length) {
            return null;
        }
        int type = (int) IbdTablespace.readUnsignedInt(page, origin);
        long id = IbdTablespace.readLong(page, origin + 4);
        int uncompressedLength = (int) IbdTablespace.readUnsignedInt(page, origin + SDI_RECORD_PREFIX);
        int compressedLength = (int) IbdTablespace.readUnsignedInt(page, origin + SDI_RECORD_PREFIX + 4);
        int dataStart = origin + SDI_RECORD_PREFIX + 8;
        if (compressedLength <= 0 || dataStart + compressedLength > page.length) {
            return null;
        }
        byte[] compressed = new byte[compressedLength];
        System.arraycopy(page, dataStart, compressed, 0, compressedLength);
        byte[] json = inflate(compressed, uncompressedLength);
        return new SdiRecord(type, id, json);
    }

    /**
     * zlib 解压。
     *
     * @param data               压缩数据
     * @param uncompressedLength 期望的解压后长度
     * @return 解压结果
     * @throws IOException 解压失败
     */
    static byte[] inflate(byte[] data, int uncompressedLength) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] out = new byte[uncompressedLength > 0 ? uncompressedLength : Math.max(1024, data.length * 8)];
            int written = 0;
            while (!inflater.finished()) {
                if (written == out.length) {
                    byte[] bigger = new byte[out.length * 2];
                    System.arraycopy(out, 0, bigger, 0, written);
                    out = bigger;
                }
                int n = inflater.inflate(out, written, out.length - written);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                written += n;
            }
            byte[] result = new byte[written];
            System.arraycopy(out, 0, result, 0, written);
            return result;
        } catch (DataFormatException e) {
            throw new IOException("SDI 数据解压失败: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    /**
     * 把 SDI 的 JSON 转成表定义。
     *
     * @param json SDI JSON 字节
     * @return 表定义
     * @throws IOException JSON 结构不符合预期
     */
    public static IbdTableDefinition parseTableDefinition(byte[] json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode dd = root.get("dd_object");
        if (dd == null || dd.isNull()) {
            throw new IOException("SDI JSON 里缺少 dd_object 节点");
        }
        List<IbdColumn> columns = new ArrayList<>();
        for (JsonNode node : arrayOf(dd, "columns")) {
            columns.add(parseColumn(node));
        }
        List<IbdIndex> indexes = new ArrayList<>();
        for (JsonNode node : arrayOf(dd, "indexes")) {
            indexes.add(parseIndex(node, columns));
        }
        return new IbdTableDefinition(
                text(dd, "name"),
                text(dd, "schema_ref"),
                rowFormatName(intValue(dd, "row_format", 0)),
                longValue(dd, "collation_id", 0L),
                longValue(dd, "mysql_version_id", 0L),
                columns,
                indexes);
    }

    /**
     * 解析列定义。
     *
     * @param node {@code columns[]} 里的一个元素
     * @return 列定义
     */
    private static IbdColumn parseColumn(JsonNode node) {
        int hidden = intValue(node, "hidden", HIDDEN_VISIBLE);
        boolean hasDefault = !boolValue(node, "default_value_utf8_null", true);
        String defaultOption = text(node, "default_option");
        String defaultValue = defaultOption.isEmpty() ? text(node, "default_value_utf8") : defaultOption;
        List<String> elements = new ArrayList<>();
        for (JsonNode element : arrayOf(node, "elements")) {
            elements.add(base64Decode(text(element, "name")));
        }
        return new IbdColumn(
                text(node, "name"),
                IbdColumnType.of(intValue(node, "type", 0)),
                boolValue(node, "is_nullable", false),
                boolValue(node, "is_unsigned", false),
                longValue(node, "char_length", 0L),
                longValue(node, "collation_id", 0L),
                intValue(node, "datetime_precision", 0),
                intValue(node, "numeric_precision", 0),
                intValue(node, "numeric_scale", 0),
                elements,
                text(node, "column_type_utf8"),
                boolValue(node, "is_auto_increment", false),
                hidden != HIDDEN_VISIBLE,
                defaultValue,
                hasDefault,
                text(node, "comment"));
    }

    /**
     * 解析索引定义。
     *
     * @param node    {@code indexes[]} 里的一个元素
     * @param columns 表的全部列（按 {@code ordinal_position} 排列，供 {@code column_opx} 定位）
     * @return 索引定义
     */
    private static IbdIndex parseIndex(JsonNode node, List<IbdColumn> columns) {
        List<IbdColumn> indexColumns = new ArrayList<>();
        for (JsonNode element : arrayOf(node, "elements")) {
            int ordinal = intValue(element, "column_opx", -1);
            if (ordinal >= 0 && ordinal < columns.size()) {
                indexColumns.add(columns.get(ordinal));
            }
        }
        String privateData = text(node, "se_private_data");
        return new IbdIndex(
                text(node, "name"),
                intValue(node, "type", IbdIndex.TYPE_MULTIPLE),
                longValue(parsePrivate(privateData), "id", 0L),
                longValue(parsePrivate(privateData), "root", 0L),
                longValue(parsePrivate(privateData), "table_id", 0L),
                indexColumns,
                boolValue(node, "hidden", false),
                boolValue(node, "is_visible", true),
                intValue(node, "algorithm", 0));
    }

    /**
     * 解析 {@code se_private_data} 这种 {@code k=v;k=v;} 形式的字符串。
     *
     * @param raw 原始字符串
     * @return 键值对
     */
    private static JsonNode parsePrivate(String raw) {
        var object = MAPPER.createObjectNode();
        if (raw == null || raw.isEmpty()) {
            return object;
        }
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                try {
                    object.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1).trim()));
                } catch (NumberFormatException ignored) {
                    object.put(part.substring(0, eq), 0L);
                }
            }
        }
        return object;
    }

    /**
     * 把行格式编号转成名字。
     *
     * @param code {@code dd::Table::enum_row_format}
     * @return {@code FIXED} / {@code DYNAMIC} / {@code COMPRESSED} / {@code REDUNDANT} / {@code PAGED}
     */
    private static String rowFormatName(int code) {
        switch (code) {
            case 1:
                return "FIXED";
            case 3:
                return "COMPRESSED";
            case 4:
                return "REDUNDANT";
            case 5:
                return "PAGED";
            default:
                return "DYNAMIC";
        }
    }

    /**
     * base64 解码（SDI 里 ENUM / SET 候选值的编码方式）。
     *
     * @param value 原始字符串
     * @return 解码后的文本；解码失败时原样返回
     */
    static String base64Decode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    /**
     * 取数组节点。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 数组元素；缺失时返回空列表
     */
    private static Iterable<JsonNode> arrayOf(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isArray() ? node : List.of();
    }

    /**
     * 取字符串字段。
     *
     * @param parent 父节点
     * @param field  字段名
     * @return 文本；缺失时返回空串
     */
    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    /**
     * 取整数字段。
     *
     * @param parent       父节点
     * @param field        字段名
     * @param defaultValue 缺省值
     * @return 数值
     */
    private static int intValue(JsonNode parent, String field, int defaultValue) {
        JsonNode node = parent.get(field);
        return node == null || !node.isNumber() ? defaultValue : node.asInt(defaultValue);
    }

    /**
     * 取长整数字段。
     *
     * @param parent       父节点
     * @param field        字段名
     * @param defaultValue 缺省值
     * @return 数值
     */
    private static long longValue(JsonNode parent, String field, long defaultValue) {
        JsonNode node = parent.get(field);
        return node == null || !node.isNumber() ? defaultValue : node.asLong(defaultValue);
    }

    /**
     * 取布尔字段。
     *
     * @param parent       父节点
     * @param field        字段名
     * @param defaultValue 缺省值
     * @return 布尔值
     */
    private static boolean boolValue(JsonNode parent, String field, boolean defaultValue) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? defaultValue : node.asBoolean(defaultValue);
    }
}
