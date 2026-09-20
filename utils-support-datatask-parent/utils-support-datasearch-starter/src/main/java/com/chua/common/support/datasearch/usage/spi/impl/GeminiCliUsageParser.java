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
 * }</pre>
 *
 * <p>The session id lives on the first line of each transcript. Older
 * transcripts without gemini 事件 are skipped silently.</p>
 *
 * <p>转录里没有耗时字段，但每轮提问行都带 {@code timestamp}，且紧跟其后的
 * {@code type=gemini} 行是同一轮的回答，因此按"上一条非回答行的时间"折算
 * {@code durationMillis}（本机实测该差值 2760ms，与首行 {@code startTime} 折算的
 * 2814ms 一致）。多轮会话逐轮配对，不会把整段会话时长记到单次请求上。</p>
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

    /**
     * 流文件。
     *
     * @param file 文件，不允许为 null
     * @return Flux 对象
     */
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
                            // 每条转录单独持有，记下最近一次提问行的时间用于折算耗时
                            long[] lastPromptMillis = new long[1];
                            return Flux.fromStream(reader.lines())
                                    .map(line -> parseLineSafe(line, sessionId, lastPromptMillis))
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
     * 读取转录首行中的会话 id；该行随后不再进入下游解析。
     *
     * @param reader 已读到首行的读取器
     * @return 首行携带的 sessionId；缺失或非法时 ""
     * @throws java.io.IOException 读取失败
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
        }
    }

    /**
     * 解析LineSafe。
     *
     * @param line 方法入参 line
     * @param sessionId 会话ID，不允许为 null
     * @param lastPromptMillis 本条转录最近一次提问时间，跨行传递
     * @return 结果列表，无数据时为空列表
     */
    private List<AiUsage> parseLineSafe(String line, String sessionId, long[] lastPromptMillis) {
        try {
            return parseLine(line, sessionId, lastPromptMillis);
        } catch (Exception e) {
            log.debug("[gemini-cli] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析Line。
     *
     * @param line 方法入参 line
     * @param sessionId 会话ID，不允许为 null
     * @param lastPromptMillis 本条转录最近一次提问时间，跨行传递
     * @return 结果列表，无数据时为空列表
     */
    private List<AiUsage> parseLine(String line, String sessionId, long[] lastPromptMillis) {
        if (line.isBlank()) {
            return List.of();
        }
        JsonNode node = Json.parse(line);
        if (!"gemini".equals(node.get("type").toStringValue())) {
            rememberPromptTime(node, lastPromptMillis);
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
        Long duration = durationOf(timestamp, lastPromptMillis[0]);
        long startTime = startTimeOf(timestamp, duration);

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider("google")
                .model(node.get("model").toStringValue())
                .requestId(firstNonBlank(node.get("id").toStringValue(), sessionId))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .cacheTokens(cached > 0 ? cached : null)
                .reasoningTokens(thoughts > 0 ? thoughts : null)
                .durationMillis(duration)
                .startTime(startTime > 0 ? startTime : null);

        if (!sessionId.isBlank()) {
            builder.requestId(sessionId + ":" + (timestamp > 0 ? timestamp : ""));
        }
        return new ArrayList<>(List.of(builder.build()));
    }

    /**
     * 记下提问行的时间，供紧随其后的回答行折算耗时。
     *
     * @param node 当前行
     * @param lastPromptMillis 本条转录最近一次提问时间
     */
    private static void rememberPromptTime(JsonNode node, long[] lastPromptMillis) {
        long millis = parseInstantToMillis(node.get("timestamp").toStringValue());
        if (millis > 0) {
            lastPromptMillis[0] = millis;
        }
    }

    /**
     * 由回答时间与提问时间折算单次耗时。
     *
     * @param answerMillis 回答行时间，缺失时为 0
     * @param promptMillis 本条转录最近一次提问时间，缺失时为 0
     * @return 耗时毫秒；两端任一缺失或倒挂时返回 空
     */
    private static Long durationOf(long answerMillis, long promptMillis) {
        return answerMillis > 0 && promptMillis > 0 && answerMillis > promptMillis
                ? answerMillis - promptMillis : null;
    }

    /**
     * 列出Transcripts。
     *
     * @return 结果列表，无数据时为空列表
     */
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
