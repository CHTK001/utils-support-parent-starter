// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.example;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.impl.ScnetUsageParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import reactor.core.scheduler.Schedulers;

/**
 * {@link ScnetUsageParser} 夹具自检示例。
 *
 * <p>本模块没有 JUnit 依赖，回归用例沿用 {@code exec-maven-plugin} 的示例 main 方式：
 * 在临时目录合成 SCNet 会话 JSONL，覆写解析器根目录后跑一遍，逐项核对口径；
 * 任一断言失败即抛异常，让 {@code exec:java} 以非零构建结果收场。</p>
 *
 * <p>覆盖的口径（均取自真实会话结构，见 {@link ScnetUsageParser} 类注释）：</p>
 * <ul>
 *   <li>全局 {@code sessions} 与项目 {@code projects/<id>/sessions} 双目录扫描；</li>
 *   <li>{@code type=compaction} 的顶层 {@code usage} 计入，模型取 {@code details.budget.model}；</li>
 *   <li>{@code inputTokens} 含缓存命中，{@code cacheTokens} 单列；</li>
 *   <li>{@code outputTokens} 净出推理，{@code reasoningTokens} 单列，{@code total = input + output}；</li>
 *   <li>{@code durationMs} 落到 {@code durationMillis}，{@code startTime} 取消息时间戳；</li>
 *   <li>同文件按 {@code responseId} 去重，非 assistant 消息与全零用量跳过；</li>
 *   <li>零金额不产出 {@code totalCost}（币种保留 {@link AiUsage} 的默认 {@code USD}）。</li>
 * </ul>
 *
 * <p>运行方式（模块目录下）：</p>
 * <pre>{@code
 * mvn -o test-compile exec:java@run-scnet-usage-test
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.45
 */
public final class ScnetUsageParserExample {

    /**
     * 夹具应产出的调用条数：1 条 assistant 消息 + 1 条压缩事件
     */
    private static final int EXPECTED_RECORDS = 2;

    /**
     * 夹具中的 assistant 用量：输入 100 + 缓存命中 20
     */
    private static final int EXPECTED_INPUT_TOKENS = 120;

    /**
     * 夹具中的 assistant 用量：输出 50 - 推理 10
     */
    private static final int EXPECTED_OUTPUT_TOKENS = 40;

    /**
     * 夹具中的 assistant 用量：输入 120 + 输出 40
     */
    private static final int EXPECTED_TOTAL_TOKENS = 160;

    /**
     * 夹具中的 assistant 用量：缓存命中段
     */
    private static final int EXPECTED_CACHE_TOKENS = 20;

    /**
     * 夹具中的 assistant 用量：推理段
     */
    private static final int EXPECTED_REASONING_TOKENS = 10;

    /**
     * 夹具中的 assistant 用量：请求耗时毫秒
     */
    private static final long EXPECTED_DURATION_MILLIS = 1200L;

    /**
     * 夹具中的 assistant 用量：请求发起时刻（epoch 毫秒）
     */
    private static final long EXPECTED_START_TIME = 1700000000000L;

    /**
     * 夹具中的压缩事件：输入 Token
     */
    private static final int COMPACTION_INPUT_TOKENS = 1000;

    /**
     * 夹具中的压缩事件：输出 Token
     */
    private static final int COMPACTION_OUTPUT_TOKENS = 80;

    /**
     * 夹具使用的模型名
     */
    private static final String MODEL_SCNET_MAX = "SCNet-Max";

    /**
     * 断言失败计数
     */
    private static int failures;

    private ScnetUsageParserExample() {
    }

    /**
     * 示例入口：合成夹具、跑解析、逐项断言，失败则抛出异常。
     *
     * @param args 未使用
     * @throws Exception 临时目录读写失败
     */
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("scnet-usage-fixture");
        try {
            run(base);
        } finally {
            deleteQuietly(base);
            // 解析器在 boundedElastic 上跑会话文件，不释放会让 exec:java 等满 15 秒再强行结束
            Schedulers.shutdownNow();
        }
        if (failures > 0) {
            throw new IllegalStateException("SCNet 用量解析夹具存在 " + failures + " 处不符合预期");
        }
        System.out.println("SCNET-USAGE-FIXTURE=PASS");
    }

    /**
     * 在给定根目录下布置夹具并执行断言。
     *
     * @param base 临时根目录
     * @throws IOException 夹具写入失败
     */
    private static void run(Path base) throws IOException {
        Path flat = Files.createDirectories(base.resolve("sessions"));
        Path project = Files.createDirectories(base.resolve("projects/p1"));

        write(flat.resolve("2026-11-18T00-00-00-000Z_s1.jsonl"),
                assistantLine(), assistantLine(), userLine(), zeroLine());
        write(project.resolve("2026-11-18T00-01-00-000Z_s2.jsonl"), compactionLine());

        FixtureParser parser = new FixtureParser(flat, project);
        List<AiUsage> all = parser.streamAll().collectList().block();
        if (all == null) {
            throw new IllegalStateException("解析结果为空");
        }
        all.forEach(usage -> System.out.println("  " + usage.getRequestId()
                + " model=" + usage.getModel()
                + " in=" + usage.getInputTokens() + " out=" + usage.getOutputTokens()
                + " total=" + usage.getTotalTokens() + " cache=" + usage.getCacheTokens()
                + " reasoning=" + usage.getReasoningTokens()
                + " duration=" + usage.getDurationMillis() + " start=" + usage.getStartTime()
                + " cost=" + usage.getTotalCost() + " currency=" + usage.getCurrency()));

        AiUsage message = find(all, "r1");
        AiUsage compaction = find(all, "comp1");
        check("记录数=2（用户消息、全零用量、重复行都被剔除）", all.size() == EXPECTED_RECORDS);
        check("assistant 调用存在", message != null);
        check("压缩调用存在", compaction != null);
        if (message != null) {
            assertMessage(message);
        }
        if (compaction != null) {
            assertCompaction(compaction);
        }
        Set<String> models = all.stream().map(AiUsage::getModel)
                .collect(Collectors.toCollection(TreeSet::new));
        check("模型不含 unknown", !models.contains("unknown"));
    }

    /**
     * 断言 assistant 消息的用量口径。
     *
     * @param usage 解析结果
     */
    private static void assertMessage(AiUsage usage) {
        check("inputTokens 含缓存命中 100+20", eq(usage.getInputTokens(), EXPECTED_INPUT_TOKENS));
        check("outputTokens 净出推理 50-10", eq(usage.getOutputTokens(), EXPECTED_OUTPUT_TOKENS));
        check("totalTokens=input+output", eq(usage.getTotalTokens(), EXPECTED_TOTAL_TOKENS));
        check("cacheTokens=20", eq(usage.getCacheTokens(), EXPECTED_CACHE_TOKENS));
        check("reasoningTokens=10", eq(usage.getReasoningTokens(), EXPECTED_REASONING_TOKENS));
        check("durationMillis=1200", eq(usage.getDurationMillis(), EXPECTED_DURATION_MILLIS));
        check("startTime=消息时间戳", eq(usage.getStartTime(), EXPECTED_START_TIME));
        check("model=SCNet-Max", MODEL_SCNET_MAX.equals(usage.getModel()));
        check("零金额不产出 totalCost（币种为默认 USD）",
                usage.getTotalCost() == null && "USD".equals(usage.getCurrency()));
    }

    /**
     * 断言压缩事件的用量口径。
     *
     * @param usage 解析结果
     */
    private static void assertCompaction(AiUsage usage) {
        check("压缩事件 input=1000", eq(usage.getInputTokens(), COMPACTION_INPUT_TOKENS));
        check("压缩事件 output=80", eq(usage.getOutputTokens(), COMPACTION_OUTPUT_TOKENS));
        check("压缩事件 model 取 details.budget.model", MODEL_SCNET_MAX.equals(usage.getModel()));
        check("压缩事件无耗时字段", usage.getDurationMillis() == null);
    }

    /**
     * assistant 消息夹具行：含缓存、推理、耗时，且重复一行用于验证去重。
     *
     * @return JSONL 行
     */
    private static String assistantLine() {
        return "{\"type\":\"message\",\"timestamp\":\"2026-11-18T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"model\":\"SCNet-Max\",\"provider\":\"scnet\","
                + "\"responseId\":\"r1\",\"stopReason\":\"stop\",\"durationMs\":1200,"
                + "\"timestamp\":1700000000000,"
                + "\"usage\":{\"input\":100,\"output\":50,\"cacheRead\":20,\"cacheWrite\":0,"
                + "\"reasoning\":10,\"totalTokens\":170,"
                + "\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}}}}";
    }

    /**
     * 用户消息夹具行：带用量但非 assistant，必须跳过。
     *
     * @return JSONL 行
     */
    private static String userLine() {
        return "{\"type\":\"message\",\"message\":{\"role\":\"user\",\"timestamp\":1700000001000,"
                + "\"usage\":{\"input\":999,\"output\":999,\"cacheRead\":0,\"cacheWrite\":0,"
                + "\"reasoning\":0,\"totalTokens\":1998}}}";
    }

    /**
     * 全零用量夹具行：必须跳过，避免产出零值记录。
     *
     * @return JSONL 行
     */
    private static String zeroLine() {
        return "{\"type\":\"message\",\"message\":{\"role\":\"assistant\",\"model\":\"SCNet-Max\","
                + "\"responseId\":\"r0\",\"timestamp\":1700000002000,"
                + "\"usage\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,"
                + "\"reasoning\":0,\"totalTokens\":0}}}";
    }

    /**
     * 上下文压缩事件夹具行：用量在顶层，模型在 {@code details.budget}。
     *
     * @return JSONL 行
     */
    private static String compactionLine() {
        return "{\"type\":\"compaction\",\"id\":\"comp1\",\"tokensBefore\":9000,"
                + "\"details\":{\"budget\":{\"provider\":\"scnet\",\"model\":\"SCNet-Max\"}},"
                + "\"usage\":{\"input\":1000,\"output\":80,\"cacheRead\":0,\"cacheWrite\":0,"
                + "\"reasoning\":0,\"totalTokens\":1080,"
                + "\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0,\"total\":0}}}";
    }

    /**
     * 按请求号取记录。
     *
     * @param all 全部记录
     * @param id  请求号
     * @return 命中记录；未命中返回 null
     */
    private static AiUsage find(List<AiUsage> all, String id) {
        for (AiUsage usage : all) {
            if (id.equals(usage.getRequestId())) {
                return usage;
            }
        }
        return null;
    }

    /**
     * 写入 JSONL 夹具文件。
     *
     * @param path  目标文件
     * @param lines 行内容
     * @throws IOException 写入失败
     */
    private static void write(Path path, String... lines) throws IOException {
        Files.write(path, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 比较两个值是否相等。
     *
     * @param actual   实际值
     * @param expected 期望值
     * @return 相等返回 true
     */
    private static boolean eq(Object actual, Object expected) {
        return expected.equals(actual);
    }

    /**
     * 记录一条断言结果，失败时累计次数。
     *
     * @param name 断言描述
     * @param ok   是否通过
     */
    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
    }

    /**
     * 递归删除夹具目录，失败不影响结论。
     *
     * @param base 临时根目录
     */
    private static void deleteQuietly(Path base) {
        try (Stream<Path> walk = Files.walk(base)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响断言结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * 覆写根目录的夹具解析器。
     */
    private static final class FixtureParser extends ScnetUsageParser {

        /**
         * 全局会话根目录
         */
        private final Path sessions;

        /**
         * 项目会话根目录
         */
        private final Path projects;

        private FixtureParser(Path sessions, Path projects) {
            this.sessions = sessions;
            this.projects = projects;
        }

        @Override
        protected Path sessionRoot() {
            return sessions;
        }

        @Override
        protected Path projectRoot() {
            return projects;
        }
    }
}
