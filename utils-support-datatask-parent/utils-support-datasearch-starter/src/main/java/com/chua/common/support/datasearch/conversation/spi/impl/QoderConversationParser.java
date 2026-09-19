package com.chua.common.support.datasearch.conversation.spi.impl;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import com.chua.common.support.datasearch.conversation.spi.AbstractJsonlConversationParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Qoder conversation parser.
 *
 * <p>Qoder CLI persists sessions as Claude-Code-style JSONL transcripts under
 * {@code ~/.qoder/projects/<project>/<sessionId>.jsonl}: user content is a
 * plain 字符串 while assistant 内容 是否 an array 的 类型 blocks.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("qoder")
public class QoderConversationParser extends AbstractJsonlConversationParser {

    private static final Path PROJECTS_DIR = Path.of(
            System.getProperty("user.home"), ".qoder", "projects");

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "qoder"}
     */
    @Override
    public String name() {
        return "qoder";
    }

    /**
     * 返回会话文件根目录。
     *
     * @return {@code ~/.qoder/projects}
     */
    @Override
    protected Path rootDir() {
        return PROJECTS_DIR;
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
     * @param ts ts
     * @return 解析instant转为millis的结果
     /**
    * 解析线。
    * @param line 线
    * @return 解析线的结果
    * @param ts ts
    */
    @Override
    protected List<ConversationMessage> parseLine(String line) {
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
            log.debug("[qoder] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ConversationMessage> messagesFromContent(JsonNode content,
            String sessionId, String messageId, String role, String model,
            Long timestamp, String cwd) {
        if (content.isMissingValue()) {
            return List.of();
        }
        java.util.function.Function<String, ConversationMessage> base =
                blockType -> ConversationMessage.builder()
                        .provider("qoder")
                        .sessionId(sessionId)
                        .messageId(messageId)
                        .role(role)
                        .model(model)
                        .contentType(blockType)
                        .timestamp(timestamp)
                        .cwd(cwd)
                        .build();

        List<ConversationMessage> result = new ArrayList<>();
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

    /**
     * 解析Instant转为毫秒数。
     *
     * @param ts 方法入参 ts
     * @return 结果数值
     */
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
