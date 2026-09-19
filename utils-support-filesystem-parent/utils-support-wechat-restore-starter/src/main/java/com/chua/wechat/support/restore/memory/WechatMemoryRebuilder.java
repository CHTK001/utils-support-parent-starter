package com.chua.wechat.support.restore.memory;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 微信内存提取结果 → 明文 SQLite 库重建器。
 *
 * <p>把内存里捞出来的记录写回一个标准的明文 SQLite 文件。这样做有两个好处：</p>
 * <ol>
 *   <li>可以直接用任意 SQLite 客户端查询、检索；</li>
 *   <li>可以复用 {@code WechatJdbcExporter} 导出 CSV / SQL / Excel，不必重写导出逻辑。</li>
 * </ol>
 *
 * <h3>为什么所有列都声明成 TEXT</h3>
 *
 * <p>记录里的 NULL 与空串在提取阶段无法区分（都表现为空串），而 {@code Msg_*} 表的
 * {@code local_id} 是 {@code INTEGER PRIMARY KEY}（记录里存 NULL），按原始声明建表会让插入
 * 触发主键自增、破坏原始行号。统一用 TEXT 声明可以避免全部类型冲突，
 * 原始建表语句另存为 {@code schema.sql} 供参考。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatMemoryRebuilder {

    /**
     * 合并后的消息表名
     */
    public static final String MSG_TABLE = "Msg_All";

    /**
     * 每批提交的行数
     */
    private static final int BATCH_SIZE = 500;

    /**
     * 构造方法，创建 WechatMemoryRebuilder 实例。
     */
    private WechatMemoryRebuilder() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 把提取结果重建成明文 SQLite 库。
     *
     * @param result 提取结果
     * @param dbFile 目标库文件
     * @return 目标库文件
     * @throws Exception 重建失败
     */
    public static File rebuild(WechatMemoryExtractor.ExtractResult result, File dbFile) throws Exception {
        Files.deleteIfExists(dbFile.toPath());
        File parent = dbFile.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        // 表名 → 列名，用于插入
        Map<String, List<String>> columnsOf = new LinkedHashMap<>();
        // 分组键 (小写表名#列数) → 该组的记录。
        // SQLite 的表名大小写不敏感：Name2Id 与 name2id 会被当成同一张表，
        // 直接按原始名字建表会报「table "Name2Id" already exists」。
        // 按小写表名归并、列数不同再加数字后缀，才能把两个库的同名表都保住。
        Map<String, List<WechatMemoryExtractor.ExtractedRecord>> grouped = new LinkedHashMap<>();
        for (WechatMemoryExtractor.ExtractedRecord record : result.records()) {
            // 表名为 null / 空串表示「无法判定归属」，直接丢弃。
            // 不能让它进分组 —— 否则 base 为空串，第二组会退化成名为 "_2" 的表
            if (record.table() == null || record.table().trim().isEmpty()) {
                continue;
            }
            String key = record.table().toLowerCase(Locale.ROOT) + '#' + record.values().length;
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }
        Map<String, String> tableNames = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (String key : grouped.keySet()) {
            String base = key.substring(0, key.lastIndexOf('#'));
            String name = base;
            for (int i = 2; !used.add(name.toLowerCase(Locale.ROOT)); i++) {
                name = base + "_" + i;
            }
            tableNames.put(key, name);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            connection.setAutoCommit(false);
            for (Map.Entry<String, List<WechatMemoryExtractor.ExtractedRecord>> entry : grouped.entrySet()) {
                String table = tableNames.get(entry.getKey());
                List<String> columns = resolveColumns(entry.getKey(), entry.getValue(), result);
                if (columns.isEmpty()) {
                    continue;
                }
                String origin = entry.getKey().substring(0, entry.getKey().lastIndexOf('#'));
                int rowidColumn = rowidColumnIndex(findSchema(origin, result), columns);
                createTable(connection, table, columns);
                insert(connection, table, columns, rowidColumn, entry.getValue());
                columnsOf.put(table, columns);
            }
            connection.commit();
        }
        log.info("已重建明文库: {} （{} 张表 / {} 条记录）",
                dbFile.getAbsolutePath(), columnsOf.size(), result.records().size());
        return dbFile;
    }

    /**
     * 解析某张表的列名。
     *
     * <p>{@code Msg_*} 记录无法从单页区分具体是哪张会话表，统一并入 {@link #MSG_TABLE}，
     * 列名取任意一张 {@code Msg_*} 表的定义。</p>
     *
     * @param tableKey 分组键（小写表名#列数）
     * @param records  该组的记录
     * @param result   提取结果
     * @return 列名列表
     */
    private static List<String> resolveColumns(String tableKey,
                                               List<WechatMemoryExtractor.ExtractedRecord> records,
                                               WechatMemoryExtractor.ExtractResult result) {
        String table = tableKey.substring(0, tableKey.lastIndexOf('#'));
        WechatMemoryPageParser.TableSchema schema = findSchema(table, result);
        if (schema != null && schema.columns().size() == records.getFirst().values().length) {
            return schema.columns();
        }
        // 没有可用 schema 时退化为 column1..columnN，保证数据不丢
        int count = records.getFirst().values().length;
        List<String> fallback = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fallback.add("column" + (i + 1));
        }
        return fallback;
    }

    /**
     * 查找表结构；{@code Msg_*} 取任意一张会话表的结构。
     *
     * @param table  表名
     * @param result 提取结果
     * @return 表结构；未找到返回 null
     */
    private static WechatMemoryPageParser.TableSchema findSchema(
            String table, WechatMemoryExtractor.ExtractResult result) {
        for (Map.Entry<String, WechatMemoryPageParser.TableSchema> entry : result.schemas().entrySet()) {
            // 分组键是小写表名，这里必须大小写不敏感地匹配，否则 Msg_All 找不到列定义，
            // 会被降级成 column1..columnN
            if (entry.getKey().equalsIgnoreCase(table)) {
                return entry.getValue();
            }
        }
        if (MSG_TABLE.equalsIgnoreCase(table) || table.toLowerCase(Locale.ROOT).startsWith("msg_")) {
            for (WechatMemoryPageParser.TableSchema candidate : result.schemas().values()) {
                if (candidate.name().startsWith("Msg_")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 定位 rowid 别名列在导出列里的下标。
     *
     * <p>列名对不上时返回 {@code -1}：例如没有可用 schema 而退化成
     * {@code column1..columnN} 的兜底列，此时宁可不回填也不能猜。</p>
     *
     * @param schema  表结构，可为 null
     * @param columns 导出列名
     * @return 列下标；无法确定返回 -1
     */
    private static int rowidColumnIndex(WechatMemoryPageParser.TableSchema schema, List<String> columns) {
        String alias = WechatMemoryPageParser.rowidAlias(schema);
        if (alias == null) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (alias.equalsIgnoreCase(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 建表（所有列声明为 TEXT，见类注释）。
     *
     * @param connection 连接
     * @param table      表名
     * @param columns    列名
     * @throws SQLException 建表失败
     */
    private static void createTable(Connection connection, String table, List<String> columns)
            throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(quote(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quote(columns.get(i))).append(" TEXT");
        }
        sql.append(')');
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql.toString());
        }
    }

    /**
     * 批量插入记录。
     *
     * @param connection 连接
     * @param table      表名
     * @param columns    列名
     * @param records    记录
     * @param rowidColumn rowid 别名列下标，-1 表示无
     * @throws SQLException 插入失败
     */
    private static void insert(Connection connection, String table, List<String> columns, int rowidColumn,
                               List<WechatMemoryExtractor.ExtractedRecord> records)
            throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quote(table)).append(" (");
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                marks.append(", ");
            }
            sql.append(quote(columns.get(i)));
            marks.append('?');
        }
        sql.append(") VALUES (").append(marks).append(')');

        int pending = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (WechatMemoryExtractor.ExtractedRecord record : records) {
                String[] values = record.values();
                for (int i = 0; i < columns.size(); i++) {
                    String value = i < values.length ? values[i] : null;
                    if (i == rowidColumn && (value == null || value.isEmpty()) && record.rowid() > 0) {
                        value = String.valueOf(record.rowid());
                    }
                    statement.setString(i + 1, value);
                }
                statement.addBatch();
                if (++pending >= BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    /**
     * SQL 标识符加引号。
     *
     * @param name 名称
     * @return 加引号后的名称
     */
    private static String quote(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * 输出原始建表语句，供人工核对。
     *
     * @param result  提取结果
     * @param outFile 目标文件
     * @throws Exception 写文件失败
     */
    public static void writeSchema(WechatMemoryExtractor.ExtractResult result, File outFile)
            throws Exception {
        StringBuilder sb = new StringBuilder("-- 从微信进程内存还原出的原始建表语句\n\n");
        for (WechatMemoryPageParser.TableSchema schema : result.schemas().values()) {
            sb.append(schema.ddl()).append(";\n\n");
        }
        File parent = outFile.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.writeString(outFile.toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        log.info("已写出原始表结构: {} （{} 张表）",
                outFile.getAbsolutePath(), result.schemas().size());
    }

    /**
     * 输出扫描摘要。
     *
     * @param result  提取结果
     * @param outFile 目标文件
     * @throws Exception 写文件失败
     */
    public static void writeSummary(WechatMemoryExtractor.ExtractResult result, File outFile)
            throws Exception {
        Map<String, Integer> tableCount = new LinkedHashMap<>();
        for (WechatMemoryExtractor.ExtractedRecord record : result.records()) {
            tableCount.merge(record.table() == null ? "?unknown" : record.table(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("# 微信内存明文页提取报告\n\n");
        sb.append("## 进程扫描\n\n| 进程号 | 工作集 | 可读 | 严格页 | 宽松页 | 记录 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        for (WechatMemoryExtractor.ProcessScan scan : result.processes()) {
            sb.append(String.format(Locale.ROOT, "| %d | %.0fMB | %.0fMB | %d | %d | %d |%n",
                    scan.pid(), scan.workingSetBytes() / 1048576.0,
                    scan.readableBytes() / 1048576.0, scan.strictPages(),
                    scan.lenientPages(), scan.recordCount()));
        }
        sb.append("\n## 结果概览\n\n| 项目 | 数量 |\n| --- | --- |\n");
        sb.append("| 去重后记录 | ").append(result.records().size()).append(" |\n");
        sb.append("| 还原出的表结构 | ").append(result.schemas().size()).append(" |\n");
        sb.append("| Name2Id 库簇 | ").append(result.clusters().size()).append(" |\n");
        sb.append("\n## 各表记录数\n\n| 表 | 记录数 |\n| --- | --- |\n");
        tableCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(40)
                .forEach(e -> sb.append("| `").append(e.getKey()).append("` | ")
                        .append(e.getValue()).append(" |\n"));
        sb.append("\n> 数据来源：微信进程内存中 SQLCipher 的 pager cache 明文页，无需数据库密钥。\n")
                .append("> 因此**只包含微信当前缓存过的页**；要让更多聊天记录进入缓存，\n")
                .append("> 请在微信里打开/滚动对应聊天，然后重新扫描（务必扫描所有 Weixin.exe 进程）。\n");

        File parent = outFile.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.writeString(outFile.toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
