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
 * Cline conversation parser.
 *
 * <p>Cline CLI stores each session's full message history in a single JSON
 * document at {@code ~/.cline/data/sessions/<id>/<id>.messages.json}:</p>
 *
 * <pre>{@code
 * {
 *   "sessionId": "...",
 *   "messages": [
 *     { "id": "msg_...", "role": "user",
 *       "content": [ { "type": "text", "text": "<user_input ...>..." } ],
 *       "ts": 1787538184446,
 *       "modelInfo": { "id": "gemini-3.6-flash" } }
 *   ]
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cline")
public class ClineConversationParser implements ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(ClineConversationParser.class);

    private static final Path SESSIONS_DIR = Path.of(
            System.getProperty("user.home"), ".cline", "data", "sessions");

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "cline"}
     */
    @Override
    public String name() {
        return "cline";
    }

    /**
     * 流式解析全部会话消息：每个 messages.json 一个惰性任务。
     */
    @Override
    public Flux<ConversationMessage> streamMessages() {
        List<Path> files = listMessageFiles();
        if (files.isEmpty()) {
            log.debug("[cline] no message files under {}", SESSIONS_DIR);
            return Flux.empty();
        }
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(file -> Mono.fromCallable(() -> parseFile(file))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(Flux::fromIterable),
                        4);
    }

    private List<Path> listMessageFiles() {
        if (!Files.isDirectory(SESSIONS_DIR)) {
            return List.of();
        }
        try (var stream = Files.walk(SESSIONS_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".messages.json"))
                    .toList();
        } catch (IOException e) {
            log.warn("[cline] walk failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<ConversationMessage> parseFile(Path file) {
        try {
            JsonNode root = Json.parse(Files.readString(file));
            String sessionId = root.get("sessionId").toStringValue();
            JsonNode messages = root.get("messages");
            if (messages.isMissingValue() || !messages.isArray()) {
                return List.of();
            }
            List<ConversationMessage> result = new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                result.addAll(parseMessage(messages.get(i), sessionId));
            }
            log.debug("[cline] parsed {} messages from {}", result.size(), file.getFileName());
            return result;
        } catch (Exception e) {
            log.debug("[cline] parse failed {}: {}", file.getFileName(), e.getMessage());
            return List.of();
        }
    }

    private List<ConversationMessage> parseMessage(JsonNode node, String sessionId) {
        String role = node.get("role").toStringValue();
        long ts = node.get("ts").toLongValue(0L);
        String model = node.get("modelInfo").get("id").toStringValue();

        java.util.function.Function<String, ConversationMessage> base =
                blockType -> ConversationMessage.builder()
                        .provider("cline")
                        .sessionId(sessionId)
                        .messageId(node.get("id").toStringValue())
                        .role(role)
                        .model(model)
                        .contentType(blockType)
                        .timestamp(ts > 0 ? ts : null)
                        .build();

        List<ConversationMessage> result = new ArrayList<>();
        JsonNode content = node.get("content");
        if (content.isMissingValue()) {
            return result;
        }
        if (!content.isArray()) {
            result.add(base.apply("text").toBuilder().content(content.toStringValue()).build());
            return result;
        }
        for (int i = 0; i < content.size(); i++) {
            JsonNode block = content.get(i);
            String blockType = block.get("type").toStringValue();
            switch (blockType) {
                case "text", "input_text", "output_text" -> {
                    JsonNode text = block.get("text");
                    result.add(base.apply("text").toBuilder()
                            .content(text.isMissingValue() ? "" : text.toStringValue())
                            .build());
                }
                case "thinking" -> result.add(base.apply("thinking"));
                case "tool_use" -> result.add(base.apply("tool_use")
                        .toBuilder().content(block.get("name").toStringValue()).build());
                default -> result.add(base.apply(blockType));
            }
        }
        return result;
    }
}
