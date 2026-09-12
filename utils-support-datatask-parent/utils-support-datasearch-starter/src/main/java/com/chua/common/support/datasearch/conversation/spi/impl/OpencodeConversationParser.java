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
   * 打开编码 conversation parser.
 *
 * <p>OpenCode stores chat text in the {@code part} table of
 * {@code ~/.local/share/opencode/opencode.db}: each text block row joins to
   * its 父 {@code message} row for 角色/会话 归因.</p>
 *
 * <pre>{@code
 * part.data    = { "type": "text", "text": "...", "time": { "start": ms } }
 * message.data = { "role": "assistant", "modelID": "...", ... }
 * }</pre>
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeConversationParser implements ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(OpencodeConversationParser.class); // 日志

    private static final Path DB_PATH = resolveDbPath(); // db路径

    /**
     * resolvedb路径。
     * @return resolvedb路径的结果
     */
    private static Path resolveDbPath() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, "opencode", "opencode.db");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    private static final String SQL_TEXT_PARTS =
            "SELECT p.data AS part_data, m.data AS msg_data "
                    + "FROM part p JOIN message m ON p.message_id = m.id "
                    + "WHERE p.data LIKE '%\"type\":\"text\"%' "
                    + "ORDER BY p.rowid ASC";

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "opencode"}
     */
    @Override
    public String name() {
        return "opencode";
    }

    /**
     * 流式解析全部文本消息。
     * @param value 值
     * @return asStr的结果
     /**
      * 流消息。
      * @return 流消息的结果
      */
     */
    @Override
    public Flux<ConversationMessage> streamMessages() {
        if (!Files.exists(DB_PATH)) {
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("opencode-conv", DB_PATH.toString());
        return engine.query(SQL_TEXT_PARTS)
                .map(this::toMessage)
                .doOnComplete(() -> log.info("[opencode] conversation stream complete"));
    /**
     * 转为消息。
     * @param row row
     * @return 转为消息的结果
     */
    }

    private ConversationMessage toMessage(Map<String, Object> row) {
        JsonNode part = safeParse(asStr(row.get("part_data")));
        JsonNode msg = safeParse(asStr(row.get("msg_data")));

        String role = msg.isMissingValue() ? "unknown" : msg.get("role").toStringValue();
        String model = msg.isMissingValue() ? "" : msg.get("modelID").toStringValue();

        long timestamp = 0L;
        String text = "";
        if (!part.isMissingValue()) {
            text = part.get("text").toStringValue();
            JsonNode time = part.get("time");
            if (!time.isMissingValue()) {
                timestamp = time.get("start").toLongValue(0L);
            }
        }

        return ConversationMessage.builder()
                .provider("opencode")
                .sessionId("")
                .role(role)
                .contentType("text")
                .content(text)
                .model(model)
                .timestamp(timestamp > 0 ? timestamp : null)
                .build();
    /**
     * safe解析。
     * @param raw raw
     * @return safe解析的结果
     * @param value 值
     */
    }

    private JsonNode safeParse(String raw) {
        try {
            return Json.parse(raw);
        } catch (Exception e) {
            log.debug("[opencode] json parse failed: {}", e.getMessage());
            return Json.parse("{}");
        }
    }

    private static String asStr(Object value) {
        return value == null ? "" : value.toString();
    }
}
