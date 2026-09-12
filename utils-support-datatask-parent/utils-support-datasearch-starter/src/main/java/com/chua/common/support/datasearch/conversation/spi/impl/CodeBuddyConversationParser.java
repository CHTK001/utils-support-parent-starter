package com.chua.common.support.datasearch.conversation.spi.impl;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import com.chua.common.support.datasearch.conversation.spi.AbstractJsonlConversationParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
   * 腾讯云 编码buddy 编码 conversation parser.
 *
 * <p>CodeBuddy Code persists sessions as JSONL transcripts under
 * {@code ~/.codebuddy/projects/<project>/<sessionId>.jsonl} (CN edition:
 * {@code ~/.codebuddycn}). Message lines use a flat shape where the content
   * array blocks are 类型 {@code input_text} / {@code output_text}:</p>
 *
 * <pre>{@code
 * {
 *   "id": "...", "timestamp": 1787545195161,
 *   "type": "message", "role": "user" | "assistant",
 *   "sessionId": "...", "cwd": "...",
 *   "content": [ { "type": "input_text", "text": "..." } ]
 * }
 * }</pre>", "text": "..." } ]
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("codebuddy")
public class CodeBuddyConversationParser extends AbstractJsonlConversationParser {

    private static final Path PROJECTS_DIR_INTL = Path.of(
            System.getProperty("user.home"), ".codebuddy", "projects");

    private static final Path PROJECTS_DIR_CN = Path.of(
            System.getProperty("user.home"), ".codebuddycn", "projects");

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "codebuddy"}
     */
    @Override
    public String name() {
        return "codebuddy";
    }

    /**
     * 返回会话文件根目录（国际版）。
     *
     * @return {@code ~/.codebuddy/projects}
     */
    @Override
    protected Path rootDir() {
        return PROJECTS_DIR_INTL;
    }

    /**
     * 扫描国际版与国内版两个目录。
     */
    @Override
    protected List<Path> rootDirs() {
        return List.of(PROJECTS_DIR_INTL, PROJECTS_DIR_CN);
    }

    /**
     * 返回会话文件后缀。
     *
     * @return {@code ".jsonl"}
     */
    @Override
    protected String fileSuffix() {
        return ".jsonl";
    }

    /**
     * 解析单行事件为零或多条消息记录。
     */
    @Override
    protected List<ConversationMessage> parseLine(String line) {
        if (line.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = Json.parse(line);
            if (!"message".equals(node.get("type").toStringValue())) {
                return List.of();
            }
            String role = node.get("role").toStringValue();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                return List.of();
            }
            long timestamp = node.get("timestamp").toLongValue(0L);
            return messagesFromContent(node.get("content"),
                    node.get("sessionId").toStringValue(),
                    node.get("id").toStringValue(),
                    role, "", timestamp > 0 ? timestamp : null,
                    node.get("cwd").toStringValue());
        } catch (Exception e) {
            log.debug("[codebuddy] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ConversationMessage> messagesFromContent(JsonNode content,
            String sessionId, String messageId, String role, String model,
            Long timestamp, String cwd) {
        if (content.isMissingValue() || !content.isArray()) {
            return List.of();
        }
        java.util.function.Function<String, ConversationMessage> base =
                blockType -> ConversationMessage.builder()
                        .provider("codebuddy")
                        .sessionId(sessionId)
                        .messageId(messageId)
                        .role(role)
                        .model(model)
                        .contentType(blockType)
                        .timestamp(timestamp)
                        .cwd(cwd)
                        .build();

        List<ConversationMessage> result = new ArrayList<>();
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
