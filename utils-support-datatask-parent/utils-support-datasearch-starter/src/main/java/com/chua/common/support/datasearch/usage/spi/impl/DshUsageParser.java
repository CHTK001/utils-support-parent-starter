package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import com.github.luben.zstd.ZstdInputStream;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * DeepSeek Harness 用量解析器（best-effort）。
 *
 * <p>数据源为 {@code <DSH_HOME>/sessions/<项目>/<会话>/session.v4.jsonl.zstd}
 * （{@code DSH_HOME} / {@code TOKENTRACKER_DSH_HOME} 覆盖，默认 {@code ~/.dsh}）。
 * 会话是事件流 JSONL，<b>且整体经 zstd 压缩</b>——这是必须解压的原因：
 * 早先按明文 {@code session.jsonl} 读取的写法在本机上恒定取不到任何行，
 * 表现为"已安装 DSH 但用量为空"的静默失效。</p>
 *
 * <p>令牌口径：{@code assistant/message} 事件在 {@code data.usage} 携带
 * {@code inputTokens} / {@code outputTokens} / {@code cacheReadTokens} /
 * {@code cacheWriteTokens} / {@code reasoningTokens}。其中 input、cacheRead、cacheWrite
 * 是<b>互斥分段</b>（源自身 total 由三段相加得到），故此处先并回全量输入；
 * 写入量（cacheWrite）是新增的未命中输入，不计入命中。推理量单列，
 * 不并入输出，符合 {@link com.chua.common.support.datasearch.usage.spi.UsageParser}
 * 的 Token 契约。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("dsh")
public class DshUsageParser extends BaseUsageParser {

    private static final String PROVIDER_DSH = "dsh";
    private static final String DEFAULT_MODEL = "dsh-unknown";

    /**
     * DSH 转录文件名（v4 起为 zstd 压缩的行式 JSON）
     */
    private static final String SESSION_FILE = "session.v4.jsonl.zstd";

    /**
     * zstd 帧魔数（小端 0xFD2FB528）
     */
    private static final int ZSTD_MAGIC = 0xFD2FB528;

    private final Path dshHome;

    /**
     * 默认构造器：按 {@code DSH_HOME} / {@code TOKENTRACKER_DSH_HOME} / {@code ~/.dsh}
     * 依次解析数据根目录。
     */
    public DshUsageParser() {
        this(resolveDshHome());
    }

    /**
     * 指定数据根目录的构造器（供测试注入夹具目录）。
     *
     * @param dshHome DSH 数据根目录
     */
    protected DshUsageParser(Path dshHome) {
        this.dshHome = dshHome;
    }

    /**
     * 解析 DSH 数据根目录。
     *
     * @return 数据根目录
     */
    private static Path resolveDshHome() {
        String home = System.getenv("DSH_HOME");
        if (home == null || home.isBlank()) {
            home = System.getenv("TOKENTRACKER_DSH_HOME");
        }
        return (home != null && !home.isBlank())
                ? Path.of(home)
                : Path.of(System.getProperty("user.home"), ".dsh");
    }

    @Override
    public String name() {
        return PROVIDER_DSH;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) {
            log.debug("[dsh] no {} under {}", SESSION_FILE, dshHome);
            return Flux.empty();
        }
        log.info("[dsh] scanning {} session files", files.size());
        return Flux.fromIterable(files)
                .map(this::parseSessionFile)
                .flatMapIterable(l -> l)
                .filter(java.util.Objects::nonNull)
                .onErrorResume(e -> {
                    log.debug("[dsh] read failed: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * 解析单个会话文件（zstd 压缩的 JSONL）。
     *
     * @param file 会话文件
     * @return 用量记录列表；无数据时为空列表
     */
    private List<AiUsage> parseSessionFile(Path file) {
        List<AiUsage> result = Wraps.list();
        String headerModel = null;
        try (InputStream in = openSession(file);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    AiUsage usage = parseLine(line, file, headerModel);
                    if (usage == null) {
                        // 非用量行仍可能是请求头，用于补全模型名
                        String model = readHeaderModel(line);
                        if (model != null) {
                            headerModel = model;
                        }
                        continue;
                    }
                    if (usage.getModel() == null || DEFAULT_MODEL.equals(usage.getModel())) {
                        usage.setModel(headerModel != null ? headerModel : DEFAULT_MODEL);
                    }
                    result.add(usage);
                } catch (Exception e) {
                    log.debug("[dsh] line parse failed: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[dsh] read failed {}: {}", file.getFileName(), e.getMessage());
        }
        return result;
    }

    /**
     * 打开会话文件，按魔数自动识别是否为 zstd 压缩。
     *
     * @param file 会话文件
     * @return 明文输入流
     * @throws IOException 读取或解压失败
     */
    private InputStream openSession(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        if (isZstd(raw)) {
            return new ZstdInputStream(new ByteArrayInputStream(raw));
        }
        return new ByteArrayInputStream(raw);
    }

    /**
     * 判断字节流是否为 zstd 帧（ZSTD_MAGIC 小端存放）。
     *
     * @param raw 原始字节
     * @return 是 zstd 返回 true
     */
    private static boolean isZstd(byte[] raw) {
        return raw.length >= 4
                && (raw[0] & 0xFF) == (ZSTD_MAGIC & 0xFF)
                && (raw[1] & 0xFF) == ((ZSTD_MAGIC >>> 8) & 0xFF)
                && (raw[2] & 0xFF) == ((ZSTD_MAGIC >>> 16) & 0xFF)
                && (raw[3] & 0xFF) == ((ZSTD_MAGIC >>> 24) & 0xFF);
    }

    /**
     * 从 {@code request/header} 事件读取模型名。
     *
     * @param line JSONL 单行
     * @return 模型名；非请求头或字段缺失时返回 null
     */
    private String readHeaderModel(String line) {
        JsonNode node = Json.parse(line);
        if (!"request/header".equals(node.get("type").toStringValue())) {
            return null;
        }
        JsonNode data = node.get("data");
        if (data == null || data.isMissingValue()) {
            return null;
        }
        String model = data.get("header").get("config").get("model").toStringValue();
        return model != null && !model.isBlank() ? model : null;
    }

    /**
     * 解析单行事件为用量记录。
     *
     * @param line        JSONL 单行
     * @param file        来源文件（用于拼请求标识）
     * @param headerModel 已知请求头模型名，可为 null
     * @return 用量记录；非用量行或全零时返回 null
     */
    private AiUsage parseLine(String line, Path file, String headerModel) {
        JsonNode node = Json.parse(line);
        if (!"assistant/message".equals(node.get("type").toStringValue())) {
            return null;
        }
        JsonNode data = node.get("data");
        if (data == null || data.isMissingValue() || data.get("usage").isMissingValue()) {
            return null;
        }
        JsonNode usage = data.get("usage");
        int input = usage.get("inputTokens").toIntValue(0);
        int output = usage.get("outputTokens").toIntValue(0);
        int cacheRead = usage.get("cacheReadTokens").toIntValue(0);
        int cacheWrite = usage.get("cacheWriteTokens").toIntValue(0);
        int reasoning = usage.get("reasoningTokens").toIntValue(0);
        if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0 && reasoning <= 0) {
            return null;
        }
        long timeMs = node.get("time").toLongValue(0L);
        if (timeMs <= 0) {
            return null;
        }
        String model = data.get("message").get("source").get("model").toStringValue();
        if (model == null || model.isBlank()) {
            model = headerModel;
        }
        // 三段互斥，先并回全量输入；写入量是未命中输入，不计入命中。
        int promptTokens = Math.max(0, input) + Math.max(0, cacheRead) + Math.max(0, cacheWrite);
        int cacheHit = Math.min(cacheRead, promptTokens);
        int total = promptTokens + output;
        return AiUsage.builder()
                .provider(PROVIDER_DSH)
                .model(model == null || model.isBlank() ? DEFAULT_MODEL : model)
                .requestId(node.get("seq").toStringValue() + ":" + file.getFileName())
                .inputTokens(promptTokens > 0 ? Integer.valueOf(promptTokens) : null)
                .outputTokens(output)
                .totalTokens(total)
                .cacheTokens(cacheHit > 0 ? Integer.valueOf(cacheHit) : null)
                .reasoningTokens(reasoning > 0 ? Integer.valueOf(reasoning) : null)
                .currency("USD")
                .estimated(false)
                .startTime(timeMs)
                .build();
    }

    /**
     * 列出会话文件。
     *
     * @return 会话文件路径列表，按路径排序；无数据时为空列表
     */
    private List<Path> listSessionFiles() {
        Path sessionsRoot = dshHome.resolve("sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        List<Path> files = Wraps.list();
        try (Stream<Path> stream = Files.walk(sessionsRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> SESSION_FILE.equals(p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("[dsh] walk failed: {}", e.getMessage());
        }
        return files.stream().sorted().toList();
    }
}
