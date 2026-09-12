package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
* Gemini CLI usage parser.
*
* <p>Gemini CLI persists chat transcripts under
* {@code ~/.gemini/tmp/<project>/chats/session-<date>-<id>.jsonl}. Recent
* CLI 版本 emit one {@code type=gemini} 事件 per 模型 响应 with a
* real 令牌 breakdown:</p>
*
* <pre>{@code
* {
*   "type": "gemini",
*   "id": "...", "timestamp": "2026-08-26T00:14:15.979Z",
*   "content": "ok", "model": "gemini-3.5-flash",
*   "tokens": { "input": 12779, "output": 1, "cached": 0,
*               "thoughts": 151, "tool": 0, "total": 12931 }
* }
* }</pre> 151, "tool": 0, "total": 12931 }
* }
* }</pre>
*
* <p>The session id lives on the first line of each transcript. Older
* transcripts without gemini 事件 are skipped silently.</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("gemini-cli")
public class GeminiCliUsageParser extends BaseUsageParser {

    private static final Path GEMINI_TMP = Path.of(
            System.getProperty("user.home"), ".gemini", "tmp");

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "gemini-cli"}
     */
    public String name() {
        return "gemini-cli";
    }

    /**
    * 流式解析全部转录文件中的模型响应用量事件。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listTranscripts();
        if (files.isEmpty()) {
            log.debug("[gemini-cli] no transcripts under {}", GEMINI_TMP);
            return Flux.empty();
        }
        log.info("[gemini-cli] scanning {} transcript files", files.size());
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::streamFile, 4);
    }

    private Flux<AiUsage> streamFile(Path file) {
        return Flux.using(
                        () -> Files.newBufferedReader(file),
                        reader -> {
                            String sessionId;
                            try {
                                sessionId = readSessionId(reader);
                            } catch (IOException e) {
                                return Flux.empty();
                            }
                            return Flux.fromStream(reader.lines())
                                    .map(line -> parseLineSafe(line, sessionId))
                                    .flatMapIterable(l -> l);
                        },
                        r -> {
                            try {
                                r.close();
                            } catch (Exception ignored) {
                                // 忽略关闭异常
                            }
                        })
                .onErrorResume(e -> {
                    log.debug("[gemini-cli] read failed {}: {}", file.getFileName(), e.getMessage());
                    return Flux.empty();
                });
    }

    /**
    * 读取转录首行中的 会话id；读取后该行不再进入下游解析。
    * @return 列表transcripts的结果
     /**
      * 读取会话id。
      * @param reader 读取
      * @return 读取会话id的结果
      */
     * @param line 线
     * @param sessionId 会话标识
     */
    private String readSessionId(java.io.BufferedReader reader) throws IOException {
        String first = reader.readLine();
        if (first == null || first.isBlank()) {
            return "";
        }
        try {
            JsonNode node = Json.parse(first);
            JsonNode sessionId = node.get("sessionId");
            return sessionId.isMissingValue() ? "" : sessionId.toStringValue();
        } catch (Exception e) {
            return "";
        /**
        * 解析线safe。
        * @param line 线
        * @param sessionId 会话标识
        * @return 解析线safe的结果
         */
        }
    }

    private List<AiUsage> parseLineSafe(String line, String sessionId) {
        try {
            return parseLine(line, sessionId);
        } catch (Exception e) {
            log.debug("[gemini-cli] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<AiUsage> parseLine(String line, String sessionId) {
        if (line.isBlank()) {
            return List.of();
        }
        JsonNode node = Json.parse(line);
        if (!"gemini".equals(node.get("type").toStringValue())) {
            return List.of();
        }
        JsonNode tokens = node.get("tokens");
        if (tokens.isMissingValue()) {
            return List.of();
        }
        int inputTokens = tokens.get("input").toIntValue(-1);
        int outputTokens = tokens.get("output").toIntValue(-1);
        if (inputTokens <= 0 && outputTokens <= 0) {
            return List.of();
        }
        int cached = tokens.get("cached").toIntValue(0);
        int thoughts = tokens.get("thoughts").toIntValue(0);
        long timestamp = parseInstantToMillis(node.get("timestamp").toStringValue());

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider("google")
                .model(node.get("model").toStringValue())
                .requestId(firstNonBlank(node.get("id").toStringValue(), sessionId))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(tokens.get("total").toIntValue(inputTokens + outputTokens))
                .cacheTokens(cached > 0 ? cached : null)
                .reasoningTokens(thoughts > 0 ? thoughts : null)
                .startTime(timestamp > 0 ? timestamp : null);

        if (!sessionId.isBlank()) {
            builder.requestId(sessionId + ":" + (timestamp > 0 ? timestamp : ""));
        }
        return new ArrayList<>(List.of(builder.build()));
    }

    private List<Path> listTranscripts() {
        if (!Files.isDirectory(GEMINI_TMP)) {
            return List.of();
        }
        try (var stream = Files.walk(GEMINI_TMP)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (IOException e) {
            log.warn("[gemini-cli] walk failed: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
