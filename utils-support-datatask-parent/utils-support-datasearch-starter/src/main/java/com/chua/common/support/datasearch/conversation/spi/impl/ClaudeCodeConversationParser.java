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
* Claude 编码 conversation parser.
*
* <p>Claude Code persists every session as a JSONL transcript under
* {@code ~/.claude/projects/<encoded-path>/<sessionId>.jsonl}; user and
* assistant 事件 carry the 对话 内容. 文本 blocks are emitted with
* 完整 内容; thinking / tool_use / tool_结果 blocks are emitted as
* 类型 记号笔 with 空 内容.</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("claude-code")
public class ClaudeCodeConversationParser extends AbstractJsonlConversationParser {

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
    * 返回会话文件根目录。
    *
    * @return {@code ~/.claude/projects}
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
            log.debug("[claude-code] parse failed: {}", e.getMessage());
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
                        .provider("claude-code")
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
