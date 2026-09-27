package com.chua.common.support.datasearch.usage.spi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Qoder 解析器测试夹具。
 *
 * <p>Qoder 的取数面有两处：会话转录 {@code ~/.qoder/projects/&#42;/&#42;.jsonl} 出账单，
 * 运行日志 {@code ~/.qoder/logs/sessions/&#42;/&#42;/segments/&#42;.jsonl} 出时钟，
 * 档位显示名 {@code ~/.qoder/.auth/dynamic-texts.json} 出模型标识。本类只负责按这三处
 * 的字段形状拼字符串，不掺任何断言。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
public final class QoderFixture {

    /**
     * 转录里的档位码
     */
    public static final String TIER_QWEN_MAX = "qmodel_38max";

    /**
     * 档位码对应的模型标识（显示名 "Qwen3.8 Max" 经 slugify 的结果）
     */
    public static final String MODEL_QWEN_MAX = "qwen3.8-max";

    /**
     * 转录里的另一个档位码
     */
    public static final String TIER_QWEN_FLASH = "qfmodel";

    /**
     * 档位码对应的模型标识（显示名 "Qwen Flash Model"）
     */
    public static final String MODEL_QWEN_FLASH = "qwen-flash-model";

    /**
     * 相对假主目录的转录文件路径
     */
    private static final String TRANSCRIPT = ".qoder/projects/demo/sess-1.jsonl";

    /**
     * 相对假主目录的运行日志片段路径
     */
    private static final String RUN_LOG = ".qoder/logs/sessions/demo/sess-1/segments/seg-1.jsonl";

    /**
     * 相对假主目录的档位显示名文件路径
     */
    private static final String DYNAMIC_TEXTS = ".qoder/.auth/dynamic-texts.json";

    /**
     * 档位显示名内容：含 {@code .description} 派生键，用于验证它不会被当成档位码收录
     */
    private static final String DYNAMIC_TEXTS_JSON = """
            {
              "modelSelector.item.qmodel_38max": "Qwen3.8 Max",
              "modelSelector.item.qmodel_38max.description": "旗舰模型",
              "modelSelector.item.qfmodel": "Qwen Flash Model",
              "modelSelector.item.lite": "Lite Tier",
              "modelSelector.title": "\u6a21\u578b\u9009\u62e9\u5668"
            }
            """;

    private QoderFixture() {
    }

    /**
     * 清空假主目录下的 Qoder 数据并铺上档位显示名。
     *
     * <p>解析器与档位目录都在类加载/首用时读盘，因此每个用例执行前重铺一次，
     * 避免上一个用例留下的转录或日志参与本轮解析。</p>
     */
    public static void reset() {
        UsageTestHome.path();
        deleteRecursively(UsageTestHome.path().resolve(".qoder"));
        UsageTestHome.write(DYNAMIC_TEXTS, DYNAMIC_TEXTS_JSON);
    }

    /**
     * 写入会话转录。
     *
     * @param lines 逐行 JSON，按行序落盘
     */
    public static void transcript(String... lines) {
        UsageTestHome.write(TRANSCRIPT, String.join("\n", lines) + "\n");
    }

    /**
     * 写入 CLI 运行日志片段。
     *
     * @param lines 逐行事件 JSON
     */
    public static void runLog(String... lines) {
        UsageTestHome.write(RUN_LOG, String.join("\n", lines) + "\n");
    }

    /**
     * 拼一条 assistant 响应行。
     *
     * @param timestamp  转录时间戳（ISO-8601，实测为响应完成时刻）
     * @param messageId  {@code message.id}，即落库的请求号
     * @param model      档位码
     * @param stopReason 结束原因
     * @param requestId  {@code usage.request_id}，用于关联运行日志
     * @param input      输入 Token（含缓存命中）
     * @param cacheRead  缓存命中 Token
     * @param cacheWrite 缓存写入 Token
     * @param output     输出 Token
     * @param credits    平台抵扣额
     * @return 单行 JSON
     */
    public static String assistant(String timestamp, String messageId, String model, String stopReason,
                                  String requestId, int input, int cacheRead, int cacheWrite,
                                  int output, double credits) {
        return String.format("""
                {"type":"assistant","timestamp":"%s","sessionId":"sess-1","message":\
                {"id":"%s","model":"%s","stop_reason":"%s","usage":\
                {"input_tokens":%d,"output_tokens":%d,"cache_read_input_tokens":%d,\
                "cache_creation_input_tokens":%d,"credits":%s,"request_id":"%s"}}}""",
                timestamp, messageId, model, stopReason, input, output, cacheRead, cacheWrite,
                credits, requestId);
    }

    /**
     * 拼一条运行日志事件行。
     *
     * @param type      事件类型，取 {@code model.request.started} 或 {@code model.response.completed}
     * @param timestamp 事件时刻（带时区偏移的 ISO-8601）
     * @param requestId 请求号
     * @return 单行 JSON
     */
    public static String event(String type, String timestamp, String requestId) {
        return String.format("{\"type\":\"%s\",\"ts\":\"%s\",\"request_id\":\"%s\"}",
                type, timestamp, requestId);
    }

    /**
     * 拼一条非 assistant 行，用于验证被跳过。
     *
     * @param timestamp 时间戳
     * @return 单行 JSON
     */
    public static String userLine(String timestamp) {
        return String.format("{\"type\":\"user\",\"timestamp\":\"%s\",\"message\":{\"content\":\"hi\"}}",
                timestamp);
    }

    /**
     * epoch 毫秒写成 ISO-8601（Z 时区）。
     *
     * @param millis epoch 毫秒
     * @return 时间戳字符串
     */
    public static String instant(long millis) {
        return java.time.Instant.ofEpochMilli(millis).toString();
    }

    /**
     * 递归删除目录。
     *
     * @param dir 待删除目录，不存在时直接返回
     */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 夹具清理失败不影响本轮断言
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
