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
* Crush conversation parser.
*
* <p>Crush keeps per-project SQLite databases at {@code &lt;project&gt;/.crush/crush.db}
* (索引 by {@code %LOCALAPPDATA%\crush\projects.json}). The {@code messages}
* table 存储 角色-attributed parts with 嵌套 文本 payloads:</p>
*
* <pre>{@code
* {
*   "id": "...", "session_id": "...", "role": "user",
*   "model": "gemini-...", "provider": "google",
*   "parts": [
*     { "type": "text", "data": { "text": "Say ok" } },
*     { "type": "finish", "data": { "reason": "stop" } }
*   ],
*   "created_at": 1787699317
* }
* }</pre> 1787699317
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("crush")
public class CrushConversationParser implements ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(CrushConversationParser.class); // 日志

    private static final Path PROJECTS_INDEX = Path.of(
            System.getProperty("user.home"), "AppData", "Local", "crush", "projects.json");

    private static final String SQL_MESSAGES =
            "SELECT id, session_id, role, parts, model, provider, created_at "
                    + "FROM messages ORDER BY created_at ASC";

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "crush"}
    */
    @Override
    public String name() {
        return "crush";
    }

    /**
    * 流式解析全部项目的会话消息。
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
        List<Path> databases = listProjectDatabases();
        if (databases.isEmpty()) {
            log.debug("[crush] no project databases found");
            return Flux.empty();
        }
        log.info("[crush] scanning {} project databases", databases.size());
        /**
        * 列表projectdatabases。
        * @return 列表projectdatabases的结果
        */
        return Flux.fromIterable(databases)
                .flatMap(this::streamDatabase, 2);
    }

    private List<Path> listProjectDatabases() {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(PROJECTS_INDEX)) {
            return result;
        }
        try {
            JsonNode root = Json.parse(Files.readString(PROJECTS_INDEX));
            JsonNode projects = root.get("projects");
            if (projects.isMissingValue() || !projects.isArray()) {
                return result;
            }
            for (int i = 0; i < projects.size(); i++) {
                JsonNode dataDir = projects.get(i).get("data_dir");
                if (dataDir.isMissingValue()) {
                    continue;
                }
                Path db = Path.of(dataDir.toStringValue(), "crush.db");
                if (Files.exists(db)) {
                    result.add(db);
                }
            }
        } catch (Exception e) {
            log.warn("[crush] index parse failed: {}", e.getMessage());
        /**
        * 流database。
        * @param db db
        * @return 流database的结果
        */
        }
        return result;
    }

    private Flux<ConversationMessage> streamDatabase(Path db) {
        return Flux.<ConversationMessage>create(sink -> {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                 PreparedStatement stmt = conn.prepareStatement(SQL_MESSAGES);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next() && !sink.isCancelled()) {
                    for (ConversationMessage m : toMessages(rs, db)) {
                        sink.next(m);
                    }
                }
                sink.complete();
            } catch (SQLException e) {
                log.debug("[crush] db read failed {}: {}", db, e.getMessage());
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(e -> {
              log.debug("[crush] stream failed {}: {}", db, e.getMessage());
              /**
              * 转为消息。
              * @param rs R
              * @param db db
              * @return 转为消息的结果
              * @param raw raw
              */
              return Flux.empty();
          });
    }

    private List<ConversationMessage> toMessages(ResultSet rs, Path db) throws SQLException {
        String messageId = rs.getString("id");
        String sessionId = rs.getString("session_id");
        String role = rs.getString("role");
        String model = rs.getString("model");
        long ts = rs.getLong("created_at") * 1000L;

        List<ConversationMessage> result = new ArrayList<>();
        JsonNode parts = safeParse(rs.getString("parts"));
        if (parts.isMissingValue() || !parts.isArray()) {
            return result;
        }
        for (int i = 0; i < parts.size(); i++) {
            JsonNode block = parts.get(i);
            String blockType = firstNonBlank(block.get("type").toStringValue(), "text");
            ConversationMessage.ConversationMessageBuilder b = ConversationMessage.builder()
                    .provider("crush")
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .role(role)
                    .model(model)
                    .contentType(blockType)
                    .timestamp(ts > 0 ? ts : null);
            if ("text".equals(blockType)) {
                JsonNode data = block.get("data");
                result.add(b.content(data.isMissingValue() ? ""
                        : data.get("text").toStringValue()).build());
            } else {
                result.add(b.build());
            }
        }
        return result;
    }

    private JsonNode safeParse(String raw) {
        try {
            return Json.parse(raw);
        } catch (Exception e) {
            log.debug("[crush] json parse failed: {}", e.getMessage());
            return Json.parse("[]");
        /**
        * asstr。
        * @param value 值
        * @return asStr的结果
        * @param fallback 降级
        */
        }
    }

    private static String asStr(Object value) {
        return value == null ? "" : value.toString();
    }


    private static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }
}
