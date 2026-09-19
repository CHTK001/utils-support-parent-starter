package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AtomCodeUsageParser 验收测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.common.support.datasearch.usage.spi.impl.AtomCodeUsageParserTest}
 * 内部阶段：
 *   1. hermetic —— 子进程注入临时 ATOMCODE_HOME，端到端验收
 *      模型归属（meta 主导模型选取 / config 兜底 / 无 meta 文件）、
 *      token 口径（非缓存输入、缓存单列、totalTokens 汇总）、
 *      脏行与无 usage 行跳过；
 *   2. real —— 对本机 ~/.atomcode 真实数据验收（无数据时自动跳过）。
 * 任一校验失败抛出 {@link AssertionError} 并输出 FAIL，全部通过输出 PASS。
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AtomCodeUsageParserTest {

    /** 失败计数 */
    private static int failureCount = 0;

    /** 成功计数 */
    private static int passCount = 0;

    /**
     * main。
     * @param args 子进程模式时传 {@code verify <tempHome>}
     */
    public static void main(String[] args) {
        if (args.length == 2 && "verify".equals(args[0])) {
            System.exit(verifyHermetic(Path.of(args[1])));
        }
        runHermeticPhase();
        runRealDataPhase();
        System.out.println("PASS " + passCount + " checks, " + failureCount + " failures");
        if (failureCount > 0) {
            System.exit(1);
        }
    }

    // ==================== 阶段 1：合成数据端到端（子进程注入环境变量） ====================

    /**
     * 构造临时 ATOMCODE_HOME，并以子进程方式运行解析验收。
     */
    private static void runHermeticPhase() {
        try {
            Path home = Files.createTempDirectory("atomcode-test-");
            writeHermeticFixture(home);
            String javaBin = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    AtomCodeUsageParserTest.class.getName(), "verify", home.toString());
            pb.environment().put("ATOMCODE_HOME", home.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            List<String> output = process.inputReader().lines().toList();
            int code = process.waitFor();
            output.forEach(line -> System.out.println("  [child] " + line));
            check(code == 0, "hermetic 子进程验收通过（exit=" + code + "）");
        } catch (Exception e) {
            check(false, "hermetic 阶段异常: " + e);
        }
    }

    /**
     * 写入合成 atomcode 数据：会话 A（meta 含主导模型选取 + 兜底）、
     * 会话 B（无 meta）、脏行与无 usage 行、config.toml 兜底模型。
     * @param home 临时 ATOMCODE_HOME
     */
    private static void writeHermeticFixture(Path home) throws IOException {
        Path sessionDir = home.resolve("sessions").resolve("dirA");
        Files.createDirectories(sessionDir);
        String sessionA = "11111111-1111-1111-1111-111111111111";
        String sessionB = "22222222-2222-2222-2222-222222222222";
        Files.writeString(sessionDir.resolve(sessionA + ".jsonl"), String.join("\n",
                "{\"v\":1,\"ts\":1787964681601,\"session_id\":\"" + sessionA + "\",\"turn_id\":1,"
                        + "\"usage\":{\"prompt\":1000,\"completion\":100,\"cached\":800}}",
                "{\"v\":1,\"ts\":1787964681602,\"session_id\":\"" + sessionA + "\",\"turn_id\":2,"
                        + "\"usage\":{\"prompt\":500,\"completion\":50,\"cached\":0}}",
                ""));
        Files.writeString(sessionDir.resolve(sessionA + ".meta"), """
                {"v":1,"id":"%s","turn_stats":[
                  {"turn_id":1,"model_usage":[
                    {"provider_id":"Cfg-glm","model_id":"glm-big","tokens":{"input":200,"output":100,"cached_input":800}},
                    {"provider_id":"Cfg-glm","model_id":"glm-small","tokens":{"input":10,"output":1,"cached_input":5}}]},
                  {"turn_id":2,"model_usage":[]}]}
                """.formatted(sessionA));
        // 会话 B：有 usage 但无 meta 文件 → 全部走 config 兜底
        Files.writeString(sessionDir.resolve(sessionB + ".jsonl"), String.join("\n",
                "{\"v\":1,\"ts\":1787964681603,\"session_id\":\"" + sessionB + "\",\"turn_id\":1,"
                        + "\"usage\":{\"prompt\":800,\"completion\":40,\"cached\":100}}",
                "{\"not-a-usage-line\":true}",
                "{broken json",
                ""));
        Files.writeString(home.resolve("config.toml"), String.join("\n",
                "default_provider = \"Cfg\"",
                "default_model = \"Cfg-glm\"",
                "",
                "[models.\"Cfg-glm\"]",
                "model = \"cfg-glm-real\"",
                ""));
    }

    /**
     * 子进程入口：对临时 HOME 运行解析并断言全部预期。
     * @param home 注入的 ATOMCODE_HOME
     * @return 退出码（0 = 全部通过）
     */
    private static int verifyHermetic(Path home) {
        List<AiUsage> records = new AtomCodeUsageParser().streamAll().collectList().block(Duration.ofMinutes(1));
        // 会话 A 两条 + 会话 B 一条；脏行/无 usage 行/空 meta turn 均被跳过
        check(records != null && records.size() == 3, "记录数 = 3，实际 " + (records == null ? "null" : records.size()));
        if (records == null || records.size() != 3) {
            System.out.println("FAIL hermetic 记录数不符");
            return failureCount > 0 ? 1 : 0;
        }
        Map<String, AiUsage> byRequest = records.stream()
                .collect(Collectors.toMap(AiUsage::getRequestId, Function.identity()));

        AiUsage turn1 = byRequest.get("11111111-1111-1111-1111-111111111111-1");
        check(turn1 != null, "会话A turn1 存在");
        if (turn1 != null) {
            check("glm-big".equals(turn1.getModel()), "turn1 模型取主导条目 glm-big，实际 " + turn1.getModel());
            check(Integer.valueOf(200).equals(turn1.getInputTokens()), "turn1 非缓存输入 = 200（1000-800），实际 " + turn1.getInputTokens());
            check(Integer.valueOf(100).equals(turn1.getOutputTokens()), "turn1 输出 = 100，实际 " + turn1.getOutputTokens());
            check(Integer.valueOf(300).equals(turn1.getTotalTokens()), "turn1 总数 = 300（不含缓存），实际 " + turn1.getTotalTokens());
            check(Integer.valueOf(800).equals(turn1.getCacheTokens()), "turn1 缓存单列 = 800，实际 " + turn1.getCacheTokens());
        }

        AiUsage turn2 = byRequest.get("11111111-1111-1111-1111-111111111111-2");
        check(turn2 != null, "会话A turn2 存在");
        if (turn2 != null) {
            check("cfg-glm-real".equals(turn2.getModel()), "turn2 空 model_usage 回退 config 真实模型名，实际 " + turn2.getModel());
            check(Integer.valueOf(500).equals(turn2.getInputTokens()), "turn2 非缓存输入 = 500（cached=0），实际 " + turn2.getInputTokens());
            check(turn2.getCacheTokens() == null, "turn2 无缓存则 cacheTokens 为 null，实际 " + turn2.getCacheTokens());
        }

        AiUsage sessionB = byRequest.get("22222222-2222-2222-2222-222222222222-1");
        check(sessionB != null, "会话B（无 meta）记录存在");
        if (sessionB != null) {
            check("cfg-glm-real".equals(sessionB.getModel()), "会话B 回退 config 兜底模型，实际 " + sessionB.getModel());
            check(Integer.valueOf(700).equals(sessionB.getInputTokens()), "会话B 非缓存输入 = 700（800-100），实际 " + sessionB.getInputTokens());
            check(Integer.valueOf(100).equals(sessionB.getCacheTokens()), "会话B 缓存单列 = 100，实际 " + sessionB.getCacheTokens());
        }
        System.out.println((failureCount == 0 ? "CHILD_PASS" : "CHILD_FAIL") + " " + passCount + " checks");
        return failureCount > 0 ? 1 : 0;
    }

    // ==================== 阶段 2：本机真实数据验收 ====================

    /**
     * 对本机 ~/.atomcode 真实数据做不变量验收；无数据时跳过。
     */
    private static void runRealDataPhase() {
        Path sessions = Path.of(System.getProperty("user.home"), ".atomcode", "sessions");
        if (!Files.isDirectory(sessions)) {
            System.out.println("SKIP 真实数据验收：本机无 " + sessions);
            return;
        }
        List<AiUsage> records = new AtomCodeUsageParser().streamAll().collectList().block(Duration.ofMinutes(2));
        check(records != null && !records.isEmpty(), "真实数据解析出记录（" + (records == null ? 0 : records.size()) + " 条）");
        if (records == null || records.isEmpty()) {
            return;
        }
        long withoutModel = records.stream().filter(r -> r.getModel() == null || r.getModel().isBlank()).count();
        check(withoutModel == 0, "全部记录含模型名（缺失 " + withoutModel + " 条）");

        long badTotal = records.stream()
                .filter(r -> r.getInputTokens() != null && r.getOutputTokens() != null)
                .filter(r -> r.getTotalTokens() == null
                        || r.getTotalTokens() != r.getInputTokens() + r.getOutputTokens())
                .count();
        check(badTotal == 0, "totalTokens = inputTokens + outputTokens（违例 " + badTotal + " 条）");

        records.stream()
                .filter(r -> r.getInputTokens() != null)
                .filter(r -> r.getInputTokens() < 0)
                .limit(3)
                .forEach(r -> check(false, "非缓存输入出现负值: " + r.getRequestId() + " = " + r.getInputTokens()));

        String models = records.stream().map(AiUsage::getModel).distinct().collect(Collectors.joining(", "));
        System.out.println("  真实数据 " + records.size() + " 条，模型分布: " + models);
        check(models.contains("deepseek-v4-flash"), "模型分布含本机已知 deepseek-v4-flash");
    }

    // ==================== 断言基建 ====================

    /**
     * 校验一条断言，失败计数并打印 FAIL。
     * @param condition 条件
     * @param message 说明
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("  ok - " + message);
        } else {
            failureCount++;
            System.out.println("  FAIL - " + message);
        }
    }
}
