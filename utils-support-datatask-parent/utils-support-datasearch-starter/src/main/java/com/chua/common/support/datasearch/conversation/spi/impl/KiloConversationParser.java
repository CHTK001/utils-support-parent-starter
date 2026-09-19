package com.chua.common.support.datasearch.conversation.spi.impl;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import com.chua.common.support.datasearch.conversation.spi.ConversationParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Kilo conversation parser.
 *
 * <p>Kilo CLI stores chat text in the {@code part} table of
 * {@code ~/.local/share/kilo/kilo.db}; each text block joins to its parent
 * {@code message} row for role/model attribution:</p>
 *
 * <pre>{@code
 * part.data    = { "type": "text", "text": "Say ok" }
 * message.data = { "role": "user", "model": {...}, ... }
 * }</pre></pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("kilo")
public class KiloConversationParser implements ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(KiloConversationParser.class); // 日志

    private static final Path DB_PATH = resolveDbPath(); // db路径

    /**
     * resolvedb路径。
     * @return resolvedb路径的结果
     */
    private static Path resolveDbPath() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "kilo", "kilo.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "kilo", "kilo.db");
    }

    private static final String SQL_TEXT_PARTS =
            "SELECT p.data AS part_data, m.data AS msg_data "
                    + "FROM part p JOIN message m ON p.message_id = m.id "
                    + "WHERE p.data LIKE '%\"type\":\"text\"%' "
                    + "ORDER BY p.rowid ASC";

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "kilo"}
     */
    @Override
    public String name() {
        return "kilo";
    }

    /**
     * 流式解析全部文本消息。
     * @param value 值
     * @return asStr的结果
     /**
      * 流消息。
      * @return 流消息的结果
      * @param raw raw
      */
    @Override
    public Flux<ConversationMessage> streamMessages() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[kilo] database not found: {}", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("kilo-conv", DB_PATH.toString());
        return engine.query(SQL_TEXT_PARTS)
                .map(this::toMessage)
                /**
                 * 转为消息。
                 * @param row row
                 * @return 转为消息的结果
                 */
                .doOnComplete(() -> log.info("[kilo] conversation stream complete"));
    }

    /**
     * 转为消息。
     *
     * @param row 行，不允许为 null
     * @return Conversation消息 对象
     */
    private ConversationMessage toMessage(Map<String, Object> row) {
        JsonNode part = safeParse(asStr(row.get("part_data")));
        JsonNode msg = safeParse(asStr(row.get("msg_data")));

        String role = msg.isMissingValue() ? "unknown" : msg.get("role").toStringValue();

        String model = "";
        if (!msg.isMissingValue()) {
            JsonNode modelNode = msg.get("model");
            if (!modelNode.isMissingValue()) {
                String raw = modelNode.toStringValue();
                model = extractModelId(raw);
            }
        }

        String text = part.isMissingValue() ? "" : part.get("text").toStringValue();

        return ConversationMessage.builder()
                .provider("kilo")
                .sessionId("")
                .role(role)
                .contentType("text")
                .content(text)
                .model(model)
                /**
                 * extract模型id。
                 * @param raw raw
                 * @return extract模型id的结果
                 * @param value 值
                 */
                .build();
    }

    /**
     * extract模型ID。
     *
     * @param raw 方法入参 raw
     * @return 结果字符串
     */
    private String extractModelId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            JsonNode node = Json.parse(trimmed);
            String id = node.get("id").toStringValue();
            return id.isBlank() ? trimmed : id;
        } catch (Exception e) {
            return trimmed;
        }
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
            log.debug("[kilo] json parse failed: {}", e.getMessage());
            return Json.parse("{}");
        }
    }

    /**
     * as字符串。
     *
     * @param value 值，不允许为 null
     * @return 结果字符串
     */
    private static String asStr(Object value) {
        return value == null ? "" : value.toString();
    }
}
