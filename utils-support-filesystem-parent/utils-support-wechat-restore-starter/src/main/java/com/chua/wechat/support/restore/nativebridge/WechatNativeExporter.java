package com.chua.wechat.support.restore.nativebridge;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.wechat.support.restore.WechatExportUtils;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信原生 FFM 导出管线。
 *
 * <p>通过 {@link WcdbNativeBridge} 直接读取微信 WCDB 数据库，遍历会话并分页拉取消息，
 * 完成 ZSTD 压缩消息体解压、发送者显示名解析后，按 {@link ExportFormat} 输出：</p>
 * <ul>
 *   <li>CSV / EXCEL — 每个会话一个文件，文件名取会话显示名（自动去重）</li>
 *   <li>SQL — 全部会话汇总为一张 {@code wechat_message} 表（含会话标识列），
 *       生成 CREATE TABLE + INSERT 脚本</li>
 * </ul>
 *
 * <h3>会话过滤配置项（options）</h3>
 * <ul>
 *   <li>{@code limit} — 每个会话最多导出条数，0 表示全部</li>
 *   <li>{@code whitelist} — 逗号分隔的会话标识/显示名白名单（非空时仅导出命中会话）</li>
 *   <li>{@code blacklist} — 逗号分隔的会话标识/显示名黑名单</li>
 *   <li>{@code skip.groups} — 是否跳过群聊（wxid 以 @chatroom 结尾），默认 false</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatNativeExporter {

    /**
     * SQL 导出默认表名
     */
    private static final String DEFAULT_TABLE_NAME = "wechat_message";

    /**
     * 消息分页大小（与 Wechat-Export 一致）
     */
    private static final int PAGE_SIZE = 500;

    /**
     * ZSTD 帧魔数（小端 0xFD2FB528）
     */
    private static final long ZSTD_MAGIC = 0xFD2FB528L;

    /**
     * ZSTD 解压输出上限（16 MB），防止异常数据耗尽内存
     */
    private static final int MAX_ZSTD_OUTPUT = 16 * 1024 * 1024;

    /**
     * 解压文本保留的最大长度（与 Wechat-Export 一致）
     */
    private static final int MAX_TEXT_LENGTH = 10000;

    /**
     * 可能携带 ZSTD 压缩内容的字段
     */
    private static final String[] COMPRESSED_FIELDS = {"message_content", "compress_content"};

    /**
     * 会话标识字段候选（按优先级）
     */
    private static final String[] SESSION_ID_KEYS = {"username", "wxid", "id"};

    /**
     * 会话显示名字段候选（按优先级）
     */
    private static final String[] SESSION_NAME_KEYS = {"display_name", "display", "name", "nickname", "remark"};

    /**
     * XML title 提取正则
     */
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);

    /**
     * 工具类禁止实例化。
     */
    private WechatNativeExporter() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 执行原生导出。
     *
     * @param runtimeDir  WCDB 原生库目录
     * @param sessionDb   微信 session.db 文件
     * @param myWxid      当前账号标识（用于标记 is_mine），可为 null
     * @param key         64 位十六进制数据库密钥
     * @param config      还原配置
     * @return 还原结果
     */
    public static DataRestoreResult export(File runtimeDir, File sessionDb, String myWxid,
                                           String key, DataRestoreConfig config) throws Exception {
        List<File> outputFiles = new ArrayList<>(32);
        ExportFormat format = config.getFormat() != null ? config.getFormat() : ExportFormat.CSV;
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : sessionDb.getParentFile();
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("创建输出目录失败: " + outputDir.getAbsolutePath());
        }

        // SQL 模式下汇总全部会话消息
        List<Map<String, Object>> sqlRows = format == ExportFormat.SQL
                ? new ArrayList<>(4096) : null;

        try (WcdbNativeBridge bridge = WcdbNativeBridge.load(runtimeDir)) {
            long handle = bridge.openAccount(sessionDb.getAbsolutePath(), key);
            try {
                List<SessionInfo> sessions = loadSessions(bridge, handle, config);
                log.info("微信待导出会话数: {}", sessions.size());

                Set<String> usedFileNames = new HashSet<>(sessions.size() * 2);
                for (SessionInfo session : sessions) {
                    List<Map<String, Object>> messages = loadMessages(bridge, handle, session, myWxid, config);
                    if (messages.isEmpty()) {
                        log.info("会话无消息，跳过: {}", session.displayName());
                        continue;
                    }

                    // SQL 模式只汇总，不产生单会话文件
                    if (format == ExportFormat.SQL) {
                        appendSqlRows(sqlRows, session, messages);
                        continue;
                    }

                    File outputFile = writeSessionFile(outputDir, usedFileNames, session, messages, format, config);
                    outputFiles.add(outputFile);
                    log.info("会话导出完成: {} -> {} ({} 行)",
                            session.displayName(), outputFile.getName(), messages.size());
                }
            } finally {
                bridge.closeAccount(handle);
            }
        }

        if (format == ExportFormat.SQL) {
            File sqlFile = writeSqlFile(outputDir, sqlRows, config);
            outputFiles.add(sqlFile);
        }

        if (outputFiles.isEmpty()) {
            return DataRestoreResult.failure("原生导出未生成任何文件（无匹配会话或无消息）");
        }
        long totalSize = outputFiles.stream().mapToLong(File::length).sum();
        return DataRestoreResult.success(outputFiles, totalSize, 0);
    }

    // ==================== 会话与消息加载 ====================

    /**
     * 加载并过滤会话列表。
     *
     * @param bridge 原生桥接器
     * @param handle 账号库句柄
     * @param config 还原配置
     * @return 过滤后的会话列表
     */
    private static List<SessionInfo> loadSessions(WcdbNativeBridge bridge, long handle,
                                                  DataRestoreConfig config) {
        String sessionsJson = bridge.getSessions(handle);
        if (sessionsJson == null || sessionsJson.isBlank()) {
            return List.of();
        }
        JsonArray rawSessions = Json.getJsonArray(sessionsJson);
        Set<String> whitelist = parseNameList(config, "whitelist");
        Set<String> blacklist = parseNameList(config, "blacklist");
        boolean skipGroups = Boolean.parseBoolean(
                String.valueOf(config.getOptions().getOrDefault("skip.groups", Boolean.FALSE)));

        List<SessionInfo> sessions = new ArrayList<>(rawSessions.size());
        for (Object item : rawSessions) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            String username = pickString(raw, SESSION_ID_KEYS);
            if (username == null || username.isBlank()) {
                continue;
            }
            String displayName = pickString(raw, SESSION_NAME_KEYS);
            if (displayName == null || displayName.isBlank()) {
                displayName = username;
            }
            if (skipGroups && WechatExportUtils.isGroupChat(username)) {
                continue;
            }
            if (!whitelist.isEmpty()
                    && !whitelist.contains(username) && !whitelist.contains(displayName)) {
                continue;
            }
            if (blacklist.contains(username) || blacklist.contains(displayName)) {
                continue;
            }
            sessions.add(new SessionInfo(username, displayName));
        }
        return sessions;
    }

    /**
     * 分页加载单会话全部消息，并完成显示名解析与压缩内容解压。
     *
     * @param bridge  原生桥接器
     * @param handle  账号库句柄
     * @param session 会话信息
     * @param myWxid  当前账号标识
     * @param config  还原配置
     * @return 消息行数据
     */
    private static List<Map<String, Object>> loadMessages(WcdbNativeBridge bridge, long handle,
                                                          SessionInfo session, String myWxid,
                                                          DataRestoreConfig config) {
        long limitOption = parseLongOption(config.getOptions().get("limit"), 0L);
        int total = bridge.getMessageCount(handle, session.username());
        if (total <= 0) {
            return List.of();
        }

        List<Map<String, Object>> messages = new ArrayList<>(Math.min(total, PAGE_SIZE * 8));
        int offset = 0;
        while (offset < total) {
            int pageLimit = PAGE_SIZE;
            if (limitOption > 0) {
                pageLimit = (int) Math.min(PAGE_SIZE, Math.max(0, limitOption - messages.size()));
                if (pageLimit == 0) {
                    break;
                }
            }
            String messagesJson = bridge.getMessages(handle, session.username(), pageLimit, offset);
            if (messagesJson == null || messagesJson.isBlank()) {
                break;
            }
            JsonArray page = Json.getJsonArray(messagesJson);
            if (page.isEmpty()) {
                break;
            }
            for (Object item : page) {
                if (item instanceof JsonObject row) {
                    messages.add(row);
                }
            }
            offset += page.size();
            if (page.size() < pageLimit) {
                break;
            }
        }

        enrichMessages(bridge, handle, session, messages, myWxid);
        return messages;
    }

    /**
     * 消息增强：ZSTD 解压、发送者显示名替换、本人消息标记。
     *
     * @param bridge   原生桥接器
     * @param handle   账号库句柄
     * @param session  会话信息
     * @param messages 消息列表（原地修改）
     * @param myWxid   当前账号标识
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void enrichMessages(WcdbNativeBridge bridge, long handle, SessionInfo session,
                                       List<Map<String, Object>> messages, String myWxid) {
        // 收集发送者标识
        Set<String> senders = new LinkedHashSet<>();
        for (Map<String, Object> message : messages) {
            Object sender = message.get("sender_username");
            if (sender != null && !String.valueOf(sender).isBlank()) {
                senders.add(String.valueOf(sender));
            }
        }
        senders.add(session.username());

        // 批量解析显示名（失败时保留原始标识）
        Map<String, String> displayNames = resolveDisplayNames(bridge, handle, senders);

        for (Map<String, Object> message : messages) {
            Object sender = message.get("sender_username");
            if (sender != null) {
                String senderId = String.valueOf(sender);
                String display = displayNames.get(senderId);
                if (display != null && !display.isBlank()) {
                    message.put("sender_username", display);
                }
                if (senderId.equals(myWxid)) {
                    message.put("is_mine", 1);
                }
            }
            // ZSTD 压缩消息体解压
            decompressCompressedFields(message);
        }
    }

    /**
     * 调用原生接口批量解析发送者显示名。
     *
     * @param bridge  原生桥接器
     * @param handle  账号库句柄
     * @param senders 发送者标识集合
     * @return 标识到显示名的映射
     */
    private static Map<String, String> resolveDisplayNames(WcdbNativeBridge bridge, long handle,
                                                           Set<String> senders) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonArray request = new JsonArray(senders);
            String responseJson = bridge.getDisplayNames(handle, request.toJSONString());
            if (responseJson == null || responseJson.isBlank()) {
                return result;
            }
            JsonObject response = Json.getJsonObject(responseJson);
            for (Map.Entry<String, Object> entry : response.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        } catch (Exception e) {
            log.debug("解析发送者显示名失败，保留原始标识: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 解压消息中的 ZSTD 压缩字段（与 wcdb_server.js 逻辑一致）。
     *
     * @param message 消息行
     */
    private static void decompressCompressedFields(Map<String, Object> message) {
        for (String field : COMPRESSED_FIELDS) {
            Object value = message.get(field);
            if (!(value instanceof String hex) || hex.isBlank()) {
                continue;
            }
            byte[] compressed = parseHex(hex);
            if (compressed == null || compressed.length < 4) {
                continue;
            }
            long magic = Integer.toUnsignedLong(readIntLe(compressed, 0));
            if (magic != ZSTD_MAGIC) {
                continue;
            }
            try {
                byte[] decompressed = zstdDecompress(compressed);
                String text = new String(decompressed, Charset.forName("UTF-8"))
                        .replace("\0", "").trim();
                Matcher matcher = TITLE_PATTERN.matcher(text);
                if (matcher.find()) {
                    text = matcher.group(1);
                }
                if (!text.isBlank() && text.length() < MAX_TEXT_LENGTH) {
                    message.put("message_content", text);
                    break;
                }
            } catch (Exception e) {
                log.debug("ZSTD 消息解压失败，保留原始内容: {}", e.getMessage());
            }
        }
    }

    // ==================== 文件输出 ====================

    /**
     * 写出单个会话的数据文件（CSV / EXCEL）。
     *
     * @param outputDir     输出目录
     * @param usedFileNames 已占用文件名集合（去重用）
     * @param session       会话信息
     * @param messages      消息行数据
     * @param format        输出格式
     * @param config        还原配置
     * @return 输出文件
     * @throws Exception 写入异常
     */
    private static File writeSessionFile(File outputDir, Set<String> usedFileNames,
                                         SessionInfo session, List<Map<String, Object>> messages,
                                         ExportFormat format, DataRestoreConfig config) throws Exception {
        List<String> headers = new ArrayList<>(WechatExportUtils.inferColumns(messages));
        String safeName = WechatExportUtils.sanitizeFileName(session.displayName());
        String extension = format == ExportFormat.EXCEL ? ".xlsx" : ".csv";
        String fileName = uniqueFileName(usedFileNames, safeName, extension);
        File outputFile = new File(outputDir, fileName);

        if (format == ExportFormat.EXCEL) {
            FileSystem excelFs = FileSystem.create("excel");
            excelFs.write(outputFile)
                    .withHeaders(headers)
                    .write(messages)
                    .finish();
        } else {
            WechatExportUtils.writeCsv(outputFile, headers, messages, Charset.forName(config.getCharset()));
        }
        return outputFile;
    }

    /**
     * 写出 SQL 汇总脚本。
     *
     * @param outputDir 输出目录
     * @param sqlRows   全部会话消息行
     * @param config    还原配置
     * @return SQL 文件
     * @throws Exception 写入异常
     */
    private static File writeSqlFile(File outputDir, List<Map<String, Object>> sqlRows,
                                     DataRestoreConfig config) throws Exception {
        if (sqlRows.isEmpty()) {
            throw new IllegalStateException("没有可导出的消息数据");
        }
        String tableName = config.getTargetTable();
        if (tableName == null || tableName.isBlank()) {
            tableName = DEFAULT_TABLE_NAME;
        }
        File sqlFile = new File(outputDir, tableName + ".sql");
        String sql = WechatExportUtils.buildSqlScript(sqlRows, tableName,
                config.getTargetSchema(), config.isIncludeStructure());
        Files.writeString(sqlFile.toPath(), sql, Charset.forName(config.getCharset()));
        log.info("微信数据 SQL 汇总完成: {} ({} 行)", sqlFile.getName(), sqlRows.size());
        return sqlFile;
    }

    /**
     * 向 SQL 汇总行集合追加单会话消息（附加会话标识列）。
     *
     * @param sqlRows  汇总行集合
     * @param session  会话信息
     * @param messages 消息行数据
     */
    private static void appendSqlRows(List<Map<String, Object>> sqlRows, SessionInfo session,
                                      List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            Map<String, Object> row = new LinkedHashMap<>(message.size() + 2);
            // 会话标识列放在最前，便于按会话过滤
            row.put("session_username", session.username());
            row.put("session_display", session.displayName());
            row.putAll(message);
            sqlRows.add(row);
        }
    }

    /**
     * 生成不重复的输出文件名。
     *
     * @param usedFileNames 已占用文件名集合
     * @param baseName      基础文件名
     * @param extension     扩展名（含点）
     * @return 唯一文件名
     */
    private static String uniqueFileName(Set<String> usedFileNames, String baseName, String extension) {
        String candidate = baseName + extension;
        int suffix = 1;
        while (usedFileNames.contains(candidate)) {
            candidate = baseName + "_" + suffix + extension;
            suffix++;
        }
        usedFileNames.add(candidate);
        return candidate;
    }

    // ==================== ZSTD 与编码工具 ====================

    /**
     * 解压 ZSTD 字节数组（帧头携带解压后大小时直接解码，否则走流式解码）。
     *
     * @param source ZSTD 压缩字节
     * @return 解压后的字节
     * @throws Exception 解压异常或输出超限时抛出
     */
    private static byte[] zstdDecompress(byte[] source) throws Exception {
        long expectedSize = Zstd.decompressedSize(source);
        if (expectedSize > 0 && expectedSize < MAX_ZSTD_OUTPUT) {
            return Zstd.decompress(source, (int) expectedSize);
        }
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(source));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
                if (output.size() > MAX_ZSTD_OUTPUT) {
                    throw new IllegalStateException("ZSTD 解压输出超过上限");
                }
            }
            return output.toByteArray();
        }
    }

    /**
     * 解析十六进制字符串为字节数组。
     *
     * @param hex 十六进制文本
     * @return 字节数组；格式非法返回 null
     */
    private static byte[] parseHex(String hex) {
        String text = hex.trim();
        if (text.length() % 2 != 0) {
            return null;
        }
        try {
            byte[] bytes = new byte[text.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int high = Character.digit(text.charAt(i * 2), 16);
                int low = Character.digit(text.charAt(i * 2 + 1), 16);
                if (high < 0 || low < 0) {
                    return null;
                }
                bytes[i] = (byte) ((high << 4) | low);
            }
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 小端读取 4 字节无符号整数。
     *
     * @param bytes 字节数组
     * @param offset 起始偏移
     * @return int 值
     */
    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    // ==================== 配置工具 ====================

    /**
     * 从 Map 中按候选键名取第一个非空字符串。
     *
     * @param map 原始 Map
     * @param keys 候选键名
     * @return 字符串值，全部缺失返回 null
     */
    private static String pickString(Map<?, ?> map, String[] keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * 解析 options 中的逗号分隔名单。
     *
     * @param config 还原配置
     * @param key    options 键名
     * @return 名单集合
     */
    private static Set<String> parseNameList(DataRestoreConfig config, String key) {
        Object value = config.getOptions().get(key);
        if (value == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String item : Arrays.asList(String.valueOf(value).split(","))) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 解析 long 类型 options 值。
     *
     * @param value        原始值
     * @param defaultValue 解析失败时的默认值
     * @return long 值
     */
    private static long parseLongOption(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                log.warn("options 数值解析失败，使用默认值 {}: {}", defaultValue, value);
            }
        }
        return defaultValue;
    }

    /**
     * 会话信息载体。
     *
     * @param username    会话标识（wxid / 群 id）
     * @param displayName 会话显示名
     */
    private record SessionInfo(String username, String displayName) {
    }
}
