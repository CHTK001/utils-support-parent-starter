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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Claude Code conversation parser.
 *
 * <p>Claude Code persists every session as a JSONL transcript under
 * {@code ~/.claude/projects/<encoded-path>/<sessionId>.jsonl}. Each line is
 * one event; user and assistant events carry the chat content:</p>
 *
 * <pre>{@code
 * {
 *   "type": "assistant",
 *   "timestamp": "2026-07-09T23:58:42.000Z",
 *   "sessionId": "...",
 *   "cwd": "...",
 *   "message": {
 *     "id": "msg_...",
 *     "role": "assistant",
 *     "model": "deepseek-v4-flash",
 *     "content": [
 *       { "type": "text", "text": "..." },
 *       { "type": "tool_use", "name": "Read", ... }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>Text blocks are emitted with full content; thinking / tool_use /
 * tool_result blocks are emitted as type markers with empty content.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("claude-code")
public class ClaudeCodeConversationParser implements ConversationParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeConversationParser.class);

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".claude", "projects");

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "claude-code"}
     */
    @Override
    public String name() {
        return "claude-code";
    }

    /**
     * 流式解析全部会话消息：逐文件、逐行惰性拉取。
     *
     * <p>磁盘读取与行解析都发生在订阅线程（boundedElastic）上，
     * 不做逐行调度跳转；解析为纯 CPU 操作，开销极低。</p>
     */
    @Override
    public Flux<ConversationMessage> streamMessages() {
        List<Path> files = listTranscripts();
        if (files.isEmpty()) {
            log.debug("[claude-code] no transcripts under {}", PROJECTS_DIR);
            return Flux.empty();
        }
        log.info("[claude-code] streaming from {} transcript files", files.size());
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::streamFile, 4);
    }

    private Flux<ConversationMessage> streamFile(Path file) {
        return Flux.using(
                        () -> Files.newBufferedReader(file),
                        reader -> Flux.fromStream(reader.lines())
                                .map(this::parseLineSafe)
                                .flatMapIterable(l -> l),
                        reader -> {
                            try {
                                reader.close();
                            } catch (Exception ignored) {
                                // 忽略关闭异常
                            }
                        })
                .onErrorResume(e -> {
                    log.debug("[claude-code] read failed {}: {}", file.getFileName(), e.getMessage());
                    return Flux.empty();
                });
    }

    private List<ConversationMessage> parseLineSafe(String line) {
        try {
            return parseLine(line);
        } catch (Exception e) {
            log.debug("[claude-code] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Path> listTranscripts() {
        if (!Files.isDirectory(PROJECTS_DIR)) {
            return List.of();
        }
        try (var stream = Files.walk(PROJECTS_DIR)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (IOException e) {
            log.warn("[claude-code] walk failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<ConversationMessage> parseLine(String line) {
        if (line.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = Json.parse(line);
            String type = node.get("type").toStringValue();
            if (!"user".equals(type) && !"assistant".equals(type)) {
                return List.of();
            }
            JsonNode message = node.get("message");
            if (message.isMissingValue()) {
                return List.of();
            }
            String role = message.get("role").toStringValue();
            if (role.isBlank()) {
                role = type;
            }
            long timestamp = parseInstantToMillis(node.get("timestamp").toStringValue());
            String sessionId = node.get("sessionId").toStringValue();
            JsonNode id = message.get("id");
            String messageId = id.isMissingValue() ? "" : id.toStringValue();

            return messagesFromContent(message.get("content"), sessionId, messageId,
                    role, message.get("model").toStringValue(),
                    timestamp > 0 ? timestamp : null, node.get("cwd").toStringValue());
        } catch (Exception e) {
            log.debug("[claude-code] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 展开消息 content：字符串视为单个 text 块；数组按块展开，
     * text 块携带正文，其余块仅保留类型标记（内容置空）。
     */
    private List<ConversationMessage> messagesFromContent(JsonNode content,
            String sessionId, String messageId, String role, String model,
            Long timestamp, String cwd) {
        if (content.isMissingValue()) {
            return List.of();
        }
        java.util.function.Function<String, ConversationMessage> base =
                blockType -> ConversationMessage.builder()
                        .provider("claude-code")
                        .sessionId(sessionId)
                        .messageId(messageId)
                        .role(role)
                        .model(model)
                        .contentType(blockType)
                        .timestamp(timestamp)
                        .cwd(cwd)
                        .build();

        List<ConversationMessage> result = new java.util.ArrayList<>();
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
                case "tool_result" -> result.add(base.apply("tool_result"));
                default -> result.add(base.apply(blockType));
            }
        }
        return result;
    }

    private long parseInstantToMillis(String ts) {
        if (ts == null || ts.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(ts).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }
}
