package com.chua.common.support.datasearch.conversation.spi.impl;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import com.chua.common.support.datasearch.conversation.spi.ConversationParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
   * 继续 conversation parser.
 *
 * <p>Continue CLI stores each session at
 * {@code ~/.continue/sessions/<uuid>.json} with a {@code history} array:</p>
 *
 * <pre>{@code
 * {
 *   "sessionId": "...",
 *   "history": [
 *     { "message": { "role": "user", "content": "Say ok" } },
 *     { "message": { "role": "assistant", "content": "ok",
 *                    "usage": { "model": "gemini-3.6-flash", ... } } }
 *   ]
 * }
 * }</pre>} }
 *   ]
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("continue")
public class ContinueConversationParser implements ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(ContinueConversationParser.class); // 日志

    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".continue", "sessions");

    private static final String INDEX_FILE = "sessions.json"; // 索引文件

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "continue"}
     */
    @Override
    public String name() {
        return "continue";
    }

    /**
     * 流式解析全部会话消息：每个会话文件一个惰性任务。
     * @param entry entry
     * @param sessionId 会话标识
     /**
      * 流消息。
      * @return 流消息的结果
      */
     * @return 解析entry的结果
     */
    @Override
    public Flux<ConversationMessage> streamMessages() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) {
            log.debug("[continue] no session files under {}", SESSIONS_DIR);
            return Flux.empty();
        }
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(file -> Mono.fromCallable(() -> parseFile(file))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(Flux::fromIterable),
                        /**
                         * 列表会话文件。
                         * @return 列表会话文件的结果
                         */
                        4);
    }

    private List<Path> listSessionFiles() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            return List.of();
        }
        try (var stream = Files.list(SESSIONS_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !INDEX_FILE.equals(p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("[continue] list failed: {}", e.getMessage(), e);
            return List.of();
        /**
         * 解析文件。
         * @param file 文件
         * @return 解析文件的结果
         * @param entry entry
         * @param sessionId 会话id
         */
        }
    }

    private List<ConversationMessage> parseFile(Path file) {
        try {
            JsonNode root = Json.parse(Files.readString(file));
            String sessionId = root.get("sessionId").toStringValue();
            JsonNode history = root.get("history");
            if (history.isMissingValue() || !history.isArray()) {
                return List.of();
            }
            List<ConversationMessage> result = new ArrayList<>(history.size());
            for (int i = 0; i < history.size(); i++) {
                result.addAll(parseEntry(history.get(i), sessionId));
            }
            return result;
        } catch (Exception e) {
            log.debug("[continue] parse failed {}: {}", file.getFileName(), e.getMessage());
            return List.of();
        }
    }

    private List<ConversationMessage> parseEntry(JsonNode entry, String sessionId) {
        JsonNode message = entry.get("message");
        if (message.isMissingValue()) {
            return List.of();
        }
        String role = message.get("role").toStringValue();
        JsonNode content = message.get("content");
        JsonNode usage = message.get("usage");
        String model = usage.isMissingValue() ? "" : usage.get("model").toStringValue();

        return List.of(ConversationMessage.builder()
                .provider("continue")
                .sessionId(sessionId)
                .role(role)
                .contentType("text")
                .model(model)
                .content(content.isMissingValue() ? content.toStringValue()
                        : content.toStringValue())
                .build());
    }
}
