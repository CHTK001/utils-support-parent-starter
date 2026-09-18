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
* Gemini CLI conversation parser.
*
* <p>Gemini CLI persists transcripts under
* {@code ~/.gemini/tmp/<project>/chats/session-<date>-<id>.jsonl}:</p>
*
* <pre>{@code
* {"sessionId": "...", ...}                                    ← first line
* {"type": "user", "id": "...", "timestamp": "...ISO...",
*  "content": [ { "text": "Say ok" } ]}
* {"type": "gemini", "id": "...", "timestamp": "...ISO...",
*  "content": "ok", "model": "gemini-3.5-flash"}
* }</pre>re>
*
* <p>User content is an array of text blocks while assistant content is a
* plain 字符串 — both normalised 转为 {@code contentType="text"}.</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("gemini-cli")
public class GeminiCliConversationParser extends AbstractJsonlConversationParser {

    private static final Path GEMINI_TMP = Path.of(
            System.getProperty("user.home"), ".gemini", "tmp");

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "gemini-cli"}
    */
    @Override
    public String name() {
        return "gemini-cli";
    }

    /**
    * 返回会话文件根目录。
    *
    * @return {@code ~/.gemini/tmp}
    */
    @Override
    protected Path rootDir() {
        return GEMINI_TMP;
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
            if (!"user".equals(type) && !"gemini".equals(type)) {
                return List.of();
            }
            long timestamp = parseInstantToMillis(node.get("timestamp").toStringValue());
            JsonNode id = node.get("id");
            String model = "gemini".equals(type) ? node.get("model").toStringValue() : "";

            ConversationMessage.ConversationMessageBuilder base = ConversationMessage.builder()
                    .provider("gemini-cli")
                    .sessionId("")
                    .messageId(id.isMissingValue() ? "" : id.toStringValue())
                    .role("gemini".equals(type) ? "assistant" : "user")
                    .model(model)
                    .timestamp(timestamp > 0 ? timestamp : null);

            JsonNode content = node.get("content");
            if (content.isMissingValue()) {
                return List.of(base.contentType("text").build());
            }
            if (!content.isArray()) {
                return List.of(base.contentType("text")
                        .content(content.toStringValue()).build());
            }
            List<ConversationMessage> result = new ArrayList<>(content.size());
            for (int i = 0; i < content.size(); i++) {
                JsonNode block = content.get(i);
                JsonNode text = block.get("text");
                result.add(base.contentType("text")
                        .content(text.isMissingValue() ? "" : text.toStringValue())
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.debug("[gemini-cli] parse failed: {}", e.getMessage());
            return List.of();
        }
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
