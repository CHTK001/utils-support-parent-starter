package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 豆包（火山方舟 Ark）用量解析器验收测试。
 *
 * <p>hermetic 模式：在临时 SQLite 库中写入 {@code proxy_request_logs} 夹具
 * （含豆包 provider 与非豆包 provider 各若干行），通过 {@code DOUBAO_USAGE_DB}
 * 环境变量指向该库，验收解析出的 {@link AiUsage} 关键字段不变量；
 * 同时验收无数据库 / 无匹配 provider 时返回空流的边界行为。</p>
 *
 * <p>由于 {@code DoubaoUsageParser} 的库路径在类加载时读取环境变量，
 * 本测试通过 {@link #spawnChild} 派生子进程注入不同环境变量分别验收
 * 三种场景，与 {@code NewProviderDataTest} 的子进程验收模式一致。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class DoubaoUsageParserTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && "verify".equals(args[0])) {
            int code = verifyChild(args[1], Path.of(args[2]));
            System.exit(code);
        }
        Path dbDoubao = createDoubaoFixture();
        Path dbEmpty = createEmptyFixture();
        Path dbMissing = Files.createTempFile("doubao-missing", ".db");
        Files.deleteIfExists(dbMissing);

        check(spawnChild("doubao-has", dbDoubao.toString()) == 0,
                "hermetic[doubao] 子进程验收通过（3 条豆包记录 + 非豆包行被过滤）");
        check(spawnChild("doubao-nomatch", dbEmpty.toString()) == 0,
                "hermetic[doubao-nomatch] 无匹配 provider 时返回空流");
        check(spawnChild("doubao-missing", dbMissing.toString()) == 0,
                "hermetic[doubao-missing] 无数据库时返回空流");

        System.out.println("DoubaoUsageParserTest: " + pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ==================== child process ====================

    private static int spawnChild(String key, String dbPath) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(childClasspath());
        cmd.add(DoubaoUsageParserTest.class.getName());
        cmd.add("verify");
        cmd.add(key);
        cmd.add(dbPath);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("DOUBAO_USAGE_DB", dbPath);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> output = process.inputReader().lines().toList();
        int code = process.waitFor();
        output.forEach(line -> System.out.println("  [child:" + key + "] " + line));
        return code;
    }

    private static int verifyChild(String key, Path dbPath) {
        System.setProperty("user.home", System.getProperty("user.home"));
        // 直接读 env（由子进程注入）
        List<AiUsage> records = collect();
        if ("doubao-has".equals(key)) {
            check(records.size() == 3, "hermetic 解析出 3 条豆包记录（实际 " + records.size() + "）");
            check(records.stream().allMatch(r -> "doubao".equals(r.getProvider())), "provider=doubao");
            check(records.stream().allMatch(r -> r.getModel() != null && !r.getModel().isBlank()),
                    "model 有值");
            check(records.stream().allMatch(r -> r.getTotalTokens() != null && r.getTotalTokens() > 0),
                    "totalTokens > 0");
            check(records.stream().noneMatch(r -> r.getStartTime() == null || r.getStartTime() <= 0),
                    "startTime 有值");
            check(records.stream().anyMatch(r -> r.getTotalCost() != null
                    && r.getTotalCost().signum() > 0), "至少一条 totalCost > 0");
        } else {
            check(records.isEmpty(), "hermetic[" + key + "] 返回空流");
        }
        System.out.println((fail == 0 ? "CHILD_PASS" : "CHILD_FAIL") + " " + pass + " checks");
        return fail > 0 ? 1 : 0;
    }

    private static List<AiUsage> collect() {
        DoubaoUsageParser parser = new DoubaoUsageParser();
        List<AiUsage> out = new ArrayList<>();
        parser.streamAll().doOnNext(out::add).subscribe();
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static String childClasspath() {
        java.net.URL location = DoubaoUsageParserTest.class.getProtectionDomain().getCodeSource().getLocation();
        Path testClasses;
        try {
            testClasses = Path.of(location.toURI());
        } catch (java.net.URISyntaxException e) {
            testClasses = Path.of(location.getPath());
        }
        Path classes = testClasses.resolveSibling("classes");
        StringBuilder cp = new StringBuilder();
        if (Files.isDirectory(classes)) {
            cp.append(classes).append(java.io.File.pathSeparator);
        }
        if (Files.isDirectory(testClasses)) {
            cp.append(testClasses).append(java.io.File.pathSeparator);
        }
        java.nio.file.Path cpFile = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "ds-cp.txt");
        if (Files.exists(cpFile)) {
            try {
                String deps = Files.readString(cpFile).trim();
                if (!deps.isEmpty()) {
                    cp.append(deps);
                }
            } catch (Exception ignored) {
                // fall through
            }
        } else {
            cp.append(System.getProperty("java.class.path"));
        }
        return cp.toString();
    }

    // ==================== fixtures ====================

    private static Path createDoubaoFixture() throws Exception {
        Path db = Files.createTempDirectory("doubao-fixture-").resolve("cc-switch.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            createTable(stmt);
            stmt.execute("INSERT INTO proxy_request_logs "
                    + "(request_id, provider_id, provider_type, model, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_creation_tokens, total_cost_usd, "
                    + "latency_ms, first_token_ms, duration_ms, status_code, session_id, created_at) VALUES "
                    + "('req-1', 'volcengine', 'ark', 'doubao-pro', 1200, 800, 0, 0, '0.012', "
                    + "1200, 320, 1200, 200, 'sess-1', 1700000000), "
                    + "('req-2', 'ark-doubao', 'ark', 'doubao-lite', 500, 300, 200, 50, '0.005', "
                    + "900, 280, 900, 200, 'sess-2', 1700000001), "
                    + "('req-3', 'volcano-code', 'volc', 'doubao-1.5-pro', 300, 150, 0, 0, '0.002', "
                    + "700, 250, 700, 200, 'sess-3', 1700000002), "
                    + "('req-4', 'openai', 'openai', 'gpt-4o', 100, 50, 0, 0, '0.001', "
                    + "500, 200, 500, 200, 'sess-4', 1700000003)");
        }
        return db;
    }

    private static Path createEmptyFixture() throws Exception {
        Path db = Files.createTempDirectory("doubao-empty-").resolve("cc-switch.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            createTable(stmt);
            stmt.execute("INSERT INTO proxy_request_logs "
                    + "(request_id, provider_id, provider_type, model, input_tokens, output_tokens, "
                    + "total_cost_usd, latency_ms, status_code, session_id, created_at) VALUES "
                    + "('req-x', 'anthropic', 'anthropic', 'claude-sonnet-4', 100, 50, '0.001', "
                    + "500, 200, 'sess-x', 1700000000)");
        }
        return db;
    }

    private static void createTable(Statement stmt) throws Exception {
        stmt.execute("CREATE TABLE proxy_request_logs ("
                + "request_id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, "
                + "provider_type TEXT NOT NULL, model TEXT NOT NULL, "
                + "input_tokens INTEGER NOT NULL DEFAULT 0, "
                + "output_tokens INTEGER NOT NULL DEFAULT 0, "
                + "cache_read_tokens INTEGER NOT NULL DEFAULT 0, "
                + "cache_creation_tokens INTEGER NOT NULL DEFAULT 0, "
                + "input_cost_usd TEXT NOT NULL DEFAULT '0', "
                + "output_cost_usd TEXT NOT NULL DEFAULT '0', "
                + "cache_read_cost_usd TEXT NOT NULL DEFAULT '0', "
                + "cache_creation_cost_usd TEXT NOT NULL DEFAULT '0', "
                + "total_cost_usd TEXT NOT NULL DEFAULT '0', "
                + "latency_ms INTEGER NOT NULL DEFAULT 0, "
                + "first_token_ms INTEGER, "
                + "duration_ms INTEGER, status_code INTEGER NOT NULL, "
                + "session_id TEXT, created_at INTEGER NOT NULL, "
                + "data_source TEXT NOT NULL DEFAULT 'proxy')");
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            pass++;
            System.out.println("  ok - " + message);
        } else {
            fail++;
            System.out.println("  FAIL - " + message);
        }
    }
}
