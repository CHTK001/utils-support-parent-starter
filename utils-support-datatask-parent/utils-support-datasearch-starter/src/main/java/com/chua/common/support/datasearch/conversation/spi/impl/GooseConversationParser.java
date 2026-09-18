package com.chua.common.support.datasearch.conversation.spi.impl;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import com.chua.common.support.datasearch.conversation.spi.ConversationParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
* Goose conversation parser.
*
* <p>Goose stores every exchange in the {@code messages} table of
* {@code %APPDATA%\Block\goose\data\sessions\sessions.db}; content is a JSON
* array 的 类型 blocks:</p>
*
* <pre>{@code
* {
*   "message_id": "msg_...", "session_id": "...", "role": "user",
*   "content_json": [ { "type": "text", "text": "Say ok" } ],
*   "created_timestamp": 1787641406
* }
* }</pre>787641406
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("goose")
public class GooseConversationParser implements ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(GooseConversationParser.class); // 日志

    private static final Path DB_PATH = Path.of(System.getenv("APPDATA"),
            "Block", "goose", "data", "sessions", "sessions.db");

    private static final String SQL_MESSAGES =
            "SELECT message_id, session_id, role, content_json, created_timestamp "
                    + "FROM messages ORDER BY created_timestamp ASC";

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "goose"}
    */
    @Override
    public String name() {
        return "goose";
    }

    /**
    * 流式解析全部会话消息：JDBC 游标逐行发射。
    * @param value 值
    * @param fallback 降级
     /**
    * 流消息。
    * @return 流消息的结果
    * @return 第一个nonblank的结果
    * @param raw raw
    */
    @Override
    public Flux<ConversationMessage> streamMessages() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[goose] database not found: {}", DB_PATH);
            return Flux.empty();
        }
        return Flux.<ConversationMessage>create(sink -> {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                 PreparedStatement stmt = conn.prepareStatement(SQL_MESSAGES);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next() && !sink.isCancelled()) {
                    for (ConversationMessage m : toMessages(rs)) {
                        sink.next(m);
                    }
                }
                sink.complete();
                log.info("[goose] conversation stream complete");
            } catch (SQLException e) {
                log.warn("[goose] parse failed: {}", e.getMessage(), e);
                sink.complete();
            /**
            * 转为消息。
            * @param rs R
            * @return 转为消息的结果
            * @param value 值
            * @param fallback 降级
            */
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 转为Messages。
     *
     * @param rs 方法入参 rs
     * @return 结果列表，无数据时为空列表
     * @throws SQLException 当执行过程不满足前置条件时
     */
    private List<ConversationMessage> toMessages(ResultSet rs) throws SQLException {
        String messageId = rs.getString("message_id");
        String sessionId = rs.getString("session_id");
        String role = rs.getString("role");
        long ts = rs.getLong("created_timestamp") * 1000L;

        List<ConversationMessage> result = new ArrayList<>();
        JsonNode content = safeParse(rs.getString("content_json"));
        if (content.isMissingValue() || !content.isArray()) {
            return result;
        }
        for (int i = 0; i < content.size(); i++) {
            JsonNode block = content.get(i);
            String blockType = firstNonBlank(block.get("type").toStringValue(), "text");
            ConversationMessage.ConversationMessageBuilder b = ConversationMessage.builder()
                    .provider("goose")
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .role(role)
                    .contentType(blockType)
                    .timestamp(ts > 0 ? ts : null);
            if ("text".equals(blockType)) {
                result.add(b.content(block.get("text").toStringValue()).build());
            } else {
                result.add(b.build());
            }
        }
        return result;
    /**
    * safe解析。
    * @param raw raw
    * @return safe解析的结果
    */
    }

    /**
     * safe解析。
     *
     * @param raw 方法入参 raw
     * @return Json节点 对象
     */
    private JsonNode safeParse(String raw) {
        try {
            return Json.parse(raw);
        } catch (Exception e) {
            log.debug("[goose] json parse failed: {}", e.getMessage());
            return Json.parse("[]");
        }
    }


    /**
     * 首个NonBlank。
     *
     * @param value 值，不允许为 null
     * @param fallback 方法入参 fallback
     * @return 结果字符串
     */
    private static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }
}
