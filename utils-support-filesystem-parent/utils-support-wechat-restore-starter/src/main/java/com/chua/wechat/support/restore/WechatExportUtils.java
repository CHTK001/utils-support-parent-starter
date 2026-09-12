package com.chua.wechat.support.restore;

import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信数据还原公共工具类。
 *
 * <p>收敛原生 FFM 导出路径与 Python 工具编排路径共用的能力：
 * 文件名清洗、CSV 转义与写入、session.db 定位、SQL 脚本生成、导出产物收集等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatExportUtils {

    /**
     * 数据文件扩展名集合（收集导出结果时优先匹配）
     */
    private static final Set<String> DATA_FILE_EXTENSIONS = Set.of("csv", "xlsx", "json");

    /**
     * 导出结果收集的时间容差（毫秒），避免文件时间戳精度问题漏收
     */
    private static final long COLLECT_TIME_TOLERANCE_MILLIS = 1000L;

    /**
     * 会话数据库文件名（微信 4.x）
     */
    private static final String SESSION_DB_NAME = "session.db";

    /**
     * 账号目录前缀
     */
    private static final String ACCOUNT_DIR_PREFIX = "wxid_";

    /**
     * 微信账号标识目录前缀（群聊判定之外的账号识别）
     */
    private static final String GROUP_SUFFIX = "@chatroom";

    /**
     * 工具类禁止实例化。
     */
    private WechatExportUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 清洗 Windows 非法文件名字符。
     *
     * @param name 原始名称
     * @return 可安全用作文件名的名称（最长 50 字符）
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        String result = name.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
        if (result.isBlank()) {
            return "unknown";
        }
        int maxLength = 50;
        if (result.length() > maxLength) {
            result = result.substring(0, maxLength);
        }
        return result;
    }

    /**
     * 判断会话标识是否为群聊。
     *
     * @param username 会话标识
     * @return 群聊返回 true
     */
    public static boolean isGroupChat(String username) {
        return username != null && username.endsWith(GROUP_SUFFIX);
    }

    /**
     * 递归查找微信会话数据库 session.db（排除 WAL 临时文件）。
     *
     * @param root 搜索根目录（微信数据目录）
     * @return session.db 文件，未找到返回 null
     */
    public static File findSessionDb(File root) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findSessionDb(child);
                if (found != null) {
                    return found;
                }
                continue;
            }
            String absolutePath = child.getAbsolutePath().replace('\\', '/');
            if (SESSION_DB_NAME.equals(child.getName()) && !absolutePath.contains("-wal")) {
                return child;
            }
        }
        return null;
    }

    /**
     * 从 session.db 路径向上推导账号目录（wxid_ 开头的目录）。
     *
     * @param sessionDb session.db 文件
     * @return 账号目录，未找到返回 null
     */
    public static File deriveAccountDir(File sessionDb) {
        if (sessionDb == null) {
            return null;
        }
        File dir = sessionDb.getParentFile();
        while (dir != null && dir.getParentFile() != null) {
            if (dir.getName().startsWith(ACCOUNT_DIR_PREFIX)) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * 转义 CSV 字段（含逗号、引号、换行时用双引号包裹）。
     *
     * @param value 原始值
     * @return 转义后的字段文本
     */
    public static String escapeCsvField(Object value) {
        if (value == null) {
            return "";
        }
        String str = String.valueOf(value);
        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
            str = str.replace("\"", "\"\"");
            return "\"" + str + "\"";
        }
        return str;
    }

    /**
     * 将行数据写入 CSV 文件。
     *
     * @param csvFile  目标 CSV 文件
     * @param headers  表头列名
     * @param rows     行数据
     * @param charset  字符集
     * @throws IOException 写入异常
     */
    public static void writeCsv(File csvFile, List<String> headers, List<Map<String, Object>> rows,
                                Charset charset) throws IOException {
        try (Writer writer = Files.newBufferedWriter(csvFile.toPath(), charset)) {
            writer.write(String.join(",", headers));
            writer.write("\n");
            for (Map<String, Object> row : rows) {
                List<String> values = new ArrayList<>(headers.size());
                for (String header : headers) {
                    values.add(escapeCsvField(row.get(header)));
                }
                writer.write(String.join(",", values));
                writer.write("\n");
            }
        }
    }

    /**
     * 收集输出目录中指定时间戳之后生成的数据文件。
     *
     * <p>优先收集 csv / xlsx / json 数据文件；若一个都没有则收集全部新生成文件。</p>
     *
     * @param outputDir  输出目录
     * @param startStamp 起始时间戳（毫秒）
     * @return 生成的文件列表
     * @throws IOException 遍历异常
     */
    public static List<File> collectGeneratedFiles(File outputDir, long startStamp) throws IOException {
        List<File> dataFiles = new ArrayList<>(16);
        List<File> allFiles = new ArrayList<>(16);
        long stamp = startStamp - COLLECT_TIME_TOLERANCE_MILLIS;
        collectFilesRecursively(outputDir, stamp, dataFiles, allFiles);
        return dataFiles.isEmpty() ? allFiles : dataFiles;
    }

    /**
     * 递归遍历目录并按时间戳筛选文件。
     *
     * @param dir        当前目录
     * @param startStamp 起始时间戳（毫秒）
     * @param dataFiles  数据文件收集器
     * @param allFiles   全部文件收集器
     */
    private static void collectFilesRecursively(File dir, long startStamp, List<File> dataFiles,
                                                List<File> allFiles) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFilesRecursively(child, startStamp, dataFiles, allFiles);
                continue;
            }
            if (child.lastModified() < startStamp) {
                continue;
            }
            allFiles.add(child);
            String name = child.getName().toLowerCase();
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex >= 0 && DATA_FILE_EXTENSIONS.contains(name.substring(dotIndex + 1))) {
                dataFiles.add(child);
            }
        }
    }

    /**
     * 解析 Wechat-Export 导出的 JSON 文件为消息行数据。
     *
     * <p>兼容三种结构：顶层数组、包含 messages/rows/data/items 键的对象、单个消息对象。</p>
     *
     * @param jsonFiles JSON 文件列表
     * @return 消息行数据列表
     * @throws IOException 读取异常
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<Map<String, Object>> parseJsonToRows(List<File> jsonFiles) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>(256);
        for (File jsonFile : jsonFiles) {
            String content = Files.readString(jsonFile.toPath(), Charset.forName("UTF-8"));
            String trimmed = content.trim();
            if (trimmed.startsWith("[")) {
                List<?> list = Json.getJsonArray(trimmed);
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        rows.add((Map) map);
                    }
                }
            } else if (trimmed.startsWith("{")) {
                Map<?, ?> map = Json.getJsonObject(trimmed);
                List<Object> messages = extractNestedArray(map);
                if (messages != null) {
                    for (Object item : messages) {
                        if (item instanceof Map<?, ?> itemMap) {
                            rows.add((Map) itemMap);
                        }
                    }
                } else {
                    rows.add((Map) map);
                }
            }
        }
        return rows;
    }

    /**
     * 从 JSON 对象中提取嵌套的消息数组。
     *
     * @param map JSON 对象
     * @return 消息数组，未找到返回 null
     */
    @SuppressWarnings("unchecked")
    private static List<Object> extractNestedArray(Map<?, ?> map) {
        for (String key : new String[]{"messages", "rows", "data", "items"}) {
            Object value = map.get(key);
            if (value instanceof List<?> list) {
                return (List<Object>) list;
            }
        }
        return null;
    }

    /**
     * 将消息行数据转换为 SQL 脚本（CREATE TABLE + INSERT）。
     *
     * @param rows        消息行数据
     * @param tableName   目标表名
     * @param targetSchema 目标库名，可为 null
     * @param includeStructure 是否包含建表语句
     * @return SQL 脚本文本
     */
    public static String buildSqlScript(List<Map<String, Object>> rows, String tableName,
                                        String targetSchema, boolean includeStructure) {
        Set<String> columns = inferColumns(rows);
        StringBuilder sql = new StringBuilder(rows.size() * 64);

        // 目标库
        if (targetSchema != null && !targetSchema.isBlank()) {
            sql.append("USE `").append(targetSchema).append("`;\n\n");
        }

        // 建表语句
        if (includeStructure) {
            sql.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("` (\n");
            sql.append("  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,\n");
            int index = 0;
            int lastIndex = columns.size() - 1;
            for (String column : columns) {
                sql.append("  `").append(column).append("` ").append(inferSqlType(rows, column));
                if (index < lastIndex) {
                    sql.append(",");
                }
                sql.append("\n");
                index++;
            }
            sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n\n");
        }

        // 插入语句
        List<String> columnList = new ArrayList<>(columns);
        for (Map<String, Object> row : rows) {
            sql.append("INSERT INTO `").append(tableName).append("` (");
            sql.append(String.join(", ", columnList.stream().map(c -> "`" + c + "`").toList()));
            sql.append(") VALUES (");
            List<String> values = new ArrayList<>(columnList.size());
            for (String column : columnList) {
                values.add(toSqlValue(row.get(column)));
            }
            sql.append(String.join(", ", values));
            sql.append(");\n");
        }
        return sql.toString();
    }

    /**
     * 推断所有消息行的列名并集（保持首次出现顺序）。
     *
     * @param rows 消息行数据
     * @return 列名集合
     */
    public static Set<String> inferColumns(List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>(32);
        for (Map<String, Object> row : rows) {
            columns.addAll(row.keySet());
        }
        return columns;
    }

    /**
     * 根据列值样本推断 SQL 列类型。
     *
     * @param rows   消息行数据
     * @param column 列名
     * @return SQL 类型名
     */
    private static String inferSqlType(List<Map<String, Object>> rows, String column) {
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            if (value instanceof Number number) {
                return number instanceof Float || number instanceof Double ? "DOUBLE" : "BIGINT";
            }
        }
        return "TEXT";
    }

    /**
     * 将 Java 值转换为 SQL 字面量。
     *
     * @param value 原始值
     * @return SQL 字面量
     */
    public static String toSqlValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        // 字符串：单引号转义
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }
}
