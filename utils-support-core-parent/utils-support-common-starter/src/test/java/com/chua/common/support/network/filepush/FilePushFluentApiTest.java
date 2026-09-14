package com.chua.common.support.network.filepush;

import com.chua.common.support.network.filepush.FilePushClient.PushResult;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链式装配 API 测试：覆盖 {@link FilePushConfig} 的链式 setter、
 * {@link FilePushServer} / {@link FilePushClient} 的链式设置方法，
 * 以及"纯链式构建"的端到端推送与增量推送。
 *
 * <p>运行方式：直接执行 {@code main}。覆盖场景：</p>
 * <ul>
 *   <li>每个 setter 返回同一实例且字段生效（链式不断链）</li>
 *   <li>{@code loadFromSystemProperties} 返回入参配置，可继续接链</li>
 *   <li>服务端 {@code targetDir} 的 Path / String 两个重载；空白入参快速失败</li>
 *   <li>客户端 {@code sourceDir} 的 Path / String 两个重载；空白入参快速失败</li>
 *   <li>{@code parallelism(n)} 在构造后重建限流信号量（否则静默不生效）</li>
 *   <li>{@code excludes(...)} 可变参数重载端到端生效</li>
 *   <li>纯链式构建的服务端 + 客户端完成一次全量推送并校验内容</li>
 *   <li>纯链式开启增量后二次推送全部跳过</li>
 *   <li>{@code onFile(...)} 逐文件回调一次、增量跳过不回调、回调抛异常不影响推送</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FilePushFluentApiTest {

    /** 默认客户端并发：CPU × 4，上限 256（与 FilePushConfig 保持一致） */
    private static final int DEFAULT_PARALLELISM =
            Math.min(256, Runtime.getRuntime().availableProcessors() * 4);

    /** 是否全部通过 */
    private static boolean allPassed = true;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 测试执行异常
     */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("filepush-fluent");
        try {
            testConfigChaining();
            testLoadFromSystemPropertiesChaining();
            testServerChaining(root);
            testClientChaining(root);
            testParallelismRebuildsLimiter();
            testChainedEndToEnd(root);
            testOnFileCallback(root);
        } finally {
            deleteRecursively(root);
        }
        System.out.println("[FilePushFluentApiTest] " + (allPassed ? "ALL PASSED" : "SOME TESTS FAILED"));
        System.exit(allPassed ? 0 : 1);
    }

    /** 配置链式 setter：每步返回同一实例，且字段确实生效 */
    private static void testConfigChaining() {
        System.out.println("--- 1. FilePushConfig 链式 setter ---");
        FilePushConfig config = FilePushConfig.defaults();
        FilePushConfig chained = config
                .setHost("10.0.0.1")
                .setPort(8888)
                .setChunkSize(2 * 1024 * 1024)
                .setIoBufferSize(32 * 1024)
                .setClientFileParallelism(16)
                .setServerConnectionParallelism(24)
                .setServerWriteParallelism(6)
                .setConnectTimeoutMs(1234)
                .setReadTimeoutMs(5678)
                .setCleanup(true)
                .setIncremental(true)
                .setFileCount(7)
                .setExcludes(List.of("log", "tmp/"))
                .setIncludes(List.of("dat"));

        check("链式返回同一实例", chained == config, "chained==config -> " + (chained == config));
        check("host 生效", "10.0.0.1".equals(config.getHost()), config.getHost());
        check("port 生效", config.getPort() == 8888, String.valueOf(config.getPort()));
        check("chunkSize 生效", config.getChunkSize() == 2 * 1024 * 1024,
                String.valueOf(config.getChunkSize()));
        check("ioBufferSize 生效", config.getIoBufferSize() == 32 * 1024,
                String.valueOf(config.getIoBufferSize()));
        check("clientParallelism 生效", config.getClientFileParallelism() == 16,
                String.valueOf(config.getClientFileParallelism()));
        check("serverParallelism 生效", config.getServerConnectionParallelism() == 24,
                String.valueOf(config.getServerConnectionParallelism()));
        check("writeParallelism 生效", config.getServerWriteParallelism() == 6,
                String.valueOf(config.getServerWriteParallelism()));
        check("connectTimeoutMs 生效", config.getConnectTimeoutMs() == 1234,
                String.valueOf(config.getConnectTimeoutMs()));
        check("readTimeoutMs 生效", config.getReadTimeoutMs() == 5678,
                String.valueOf(config.getReadTimeoutMs()));
        check("cleanup 生效", config.isCleanup(), String.valueOf(config.isCleanup()));
        check("incremental 生效", config.isIncremental(), String.valueOf(config.isIncremental()));
        check("fileCount 生效", config.getFileCount() == 7, String.valueOf(config.getFileCount()));
        check("excludes 生效", List.of("log", "tmp/").equals(config.getExcludes()),
                String.valueOf(config.getExcludes()));
        check("includes 生效", List.of("dat").equals(config.getIncludes()),
                String.valueOf(config.getIncludes()));
    }

    /** loadFromSystemProperties 返回入参配置，可继续接链 */
    private static void testLoadFromSystemPropertiesChaining() {
        System.out.println("--- 2. loadFromSystemProperties 链式 ---");
        System.setProperty("filepush.host", "192.168.9.9");
        System.setProperty("filepush.port", "7001");
        System.setProperty("filepush.chunk-size", "262144");
        System.setProperty("filepush.excludes", "log, bak ,");
        System.setProperty("filepush.includes", "dat");
        try {
            FilePushConfig config = FilePushConfig
                    .loadFromSystemProperties(FilePushConfig.defaults())
                    .setCleanup(true);

            check("返回入参配置本身可继续接链", config.isCleanup(), String.valueOf(config.isCleanup()));
            check("系统属性 host 已加载", "192.168.9.9".equals(config.getHost()), config.getHost());
            check("系统属性 port 已加载", config.getPort() == 7001, String.valueOf(config.getPort()));
            check("系统属性 chunk-size 已加载", config.getChunkSize() == 262144,
                    String.valueOf(config.getChunkSize()));
            check("系统属性 excludes 已按逗号切分并去空白",
                    List.of("log", "bak").equals(config.getExcludes()),
                    String.valueOf(config.getExcludes()));
            check("系统属性 includes 已加载", List.of("dat").equals(config.getIncludes()),
                    String.valueOf(config.getIncludes()));
        } finally {
            System.clearProperty("filepush.host");
            System.clearProperty("filepush.port");
            System.clearProperty("filepush.chunk-size");
            System.clearProperty("filepush.excludes");
            System.clearProperty("filepush.includes");
        }
    }

    /** 服务端链式：同步目录两种重载 + 其余参数 + config() 下钻 */
    private static void testServerChaining(Path root) {
        System.out.println("--- 3. FilePushServer 链式 ---");
        Path target = root.resolve("srv-target");
        FilePushConfig config = FilePushConfig.defaults();
        FilePushServer server = new FilePushServer(config);

        FilePushServer chained = server
                .targetDir(target)
                .host("127.0.0.1")
                .port(6001)
                .chunkSize(512 * 1024)
                .connectionParallelism(32)
                .cleanup(true);

        check("服务端链式返回同一实例", chained == server, "chained==server -> " + (chained == server));
        check("targetDir(Path) 设置同步目录", target.equals(config.getTargetDir()),
                String.valueOf(config.getTargetDir()));
        check("host 生效", "127.0.0.1".equals(config.getHost()), config.getHost());
        check("port 生效", config.getPort() == 6001, String.valueOf(config.getPort()));
        check("chunkSize 生效", config.getChunkSize() == 512 * 1024,
                String.valueOf(config.getChunkSize()));
        check("connectionParallelism 生效", config.getServerConnectionParallelism() == 32,
                String.valueOf(config.getServerConnectionParallelism()));
        check("cleanup 生效", config.isCleanup(), String.valueOf(config.isCleanup()));
        check("config() 返回同一配置", server.config() == config, "server.config()==config");

        // 字符串重载（带空白，应被 trim）
        server.targetDir("  " + root.resolve("srv-target-str") + "  ");
        check("targetDir(String) 设置同步目录并 trim",
                root.resolve("srv-target-str").equals(config.getTargetDir()),
                String.valueOf(config.getTargetDir()));

        boolean threw = false;
        try {
            server.targetDir("   ");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("targetDir(空白字符串) 快速失败", threw, "抛出 IllegalArgumentException -> " + threw);

        threw = false;
        try {
            server.targetDir((String) null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("targetDir(null 字符串) 快速失败", threw, "抛出 IllegalArgumentException -> " + threw);
    }

    /** 客户端链式：源目录两种重载 + 过滤模式可变参数重载 + config() 下钻 */
    private static void testClientChaining(Path root) throws IOException {
        System.out.println("--- 4. FilePushClient 链式 ---");
        Path source = root.resolve("cli-source");
        Files.createDirectories(source);
        FilePushConfig config = FilePushConfig.defaults();

        try (FilePushClient client = new FilePushClient(config)) {
            FilePushClient chained = client
                    .sourceDir(source)
                    .host("127.0.0.1")
                    .port(6002)
                    .chunkSize(64 * 1024)
                    .incremental(true)
                    .cleanup(true)
                    .excludes("log", "tmp/")
                    .includes(List.of("dat", "bin"));

            check("客户端链式返回同一实例", chained == client, "chained==client -> " + (chained == client));
            check("sourceDir(Path) 设置源目录", source.equals(config.getSourceDir()),
                    String.valueOf(config.getSourceDir()));
            check("host 生效", "127.0.0.1".equals(config.getHost()), config.getHost());
            check("port 生效", config.getPort() == 6002, String.valueOf(config.getPort()));
            check("chunkSize 生效", config.getChunkSize() == 64 * 1024,
                    String.valueOf(config.getChunkSize()));
            check("incremental 生效", config.isIncremental(), String.valueOf(config.isIncremental()));
            check("cleanup 生效", config.isCleanup(), String.valueOf(config.isCleanup()));
            check("excludes(可变参数) 生效", List.of("log", "tmp/").equals(config.getExcludes()),
                    String.valueOf(config.getExcludes()));
            check("includes(List) 生效", List.of("dat", "bin").equals(config.getIncludes()),
                    String.valueOf(config.getIncludes()));
            check("config() 返回同一配置", client.config() == config, "client.config()==config");

            client.sourceDir("  " + root.resolve("cli-source-str") + "  ");
            check("sourceDir(String) 设置源目录并 trim",
                    root.resolve("cli-source-str").equals(config.getSourceDir()),
                    String.valueOf(config.getSourceDir()));

            boolean threw = false;
            try {
                client.sourceDir("");
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check("sourceDir(空字符串) 快速失败", threw, "抛出 IllegalArgumentException -> " + threw);
        }
    }

    /** parallelism(n) 必须在构造后重建限流信号量，否则设置静默不生效 */
    private static void testParallelismRebuildsLimiter() throws Exception {
        System.out.println("--- 5. parallelism() 重建限流信号量 ---");
        FilePushConfig config = FilePushConfig.defaults().setClientFileParallelism(2);
        try (FilePushClient client = new FilePushClient(config)) {
            check("构造时信号量许可=2", permits(client) == 2, "permits=" + permits(client));

            client.parallelism(9);
            check("parallelism(9) 重建信号量", permits(client) == 9, "permits=" + permits(client));
            check("parallelism(9) 同步写入配置", config.getClientFileParallelism() == 9,
                    String.valueOf(config.getClientFileParallelism()));

            client.parallelism(0);
            check("parallelism(0) 回退默认 CPU×4", permits(client) == DEFAULT_PARALLELISM,
                    "permits=" + permits(client) + " 期望=" + DEFAULT_PARALLELISM);

            client.parallelism(-5);
            check("parallelism(负数) 回退默认 CPU×4", permits(client) == DEFAULT_PARALLELISM,
                    "permits=" + permits(client) + " 期望=" + DEFAULT_PARALLELISM);
        }
    }

    /** 纯链式构建服务端与客户端，完成全量推送 + 增量二次推送 */
    private static void testChainedEndToEnd(Path root) throws Exception {
        System.out.println("--- 6. 纯链式端到端推送 ---");
        Path source = root.resolve("e2e-src");
        Path target = root.resolve("e2e-dst");
        Files.createDirectories(source.resolve("sub"));
        byte[] small = "chained api".getBytes();
        byte[] big = randomBytes(2 * 1024 * 1024 + 11);
        Files.write(source.resolve("a.txt"), small);
        Files.write(source.resolve("sub").resolve("b.bin"), big);
        Files.write(source.resolve("skip.log"), "excluded".getBytes());

        try (FilePushServer server = new FilePushServer(FilePushConfig.defaults())
                .targetDir(target)
                .host("127.0.0.1")
                .port(0)
                .chunkSize(256 * 1024)
                .connectionParallelism(8)
                .cleanup(true)) {
            int port = server.start();
            System.out.println("  服务端已监听端口 " + port);

            try (FilePushClient client = new FilePushClient(FilePushConfig.defaults())
                    .sourceDir(source)
                    .host("127.0.0.1")
                    .port(port)
                    .chunkSize(256 * 1024)
                    .parallelism(4)
                    .excludes("skip.log")) {
                PushResult first = client.push();
                check("全量推送无失败", !first.hasFailures(),
                        "failed=" + first.failedCount() + failures(first));
                check("全量推送 2 个文件", first.successCount() == 2,
                        "success=" + first.successCount());
                check("可变参数 excludes 端到端生效", !Files.exists(target.resolve("skip.log")),
                        "skip.log 应被排除");
                check("a.txt 内容一致", Arrays.equals(small, readAll(target.resolve("a.txt"))),
                        "size=" + safeSize(target.resolve("a.txt")));
                check("sub/b.bin 内容一致（多分片）",
                        Arrays.equals(big, readAll(target.resolve("sub").resolve("b.bin"))),
                        "size=" + safeSize(target.resolve("sub").resolve("b.bin")));

                // 同一个客户端实例链式切换到增量，二次推送应全部跳过
                client.incremental(true);
                PushResult second = client.push();
                check("增量二次推送无失败", !second.hasFailures(),
                        "failed=" + second.failedCount() + failures(second));
                check("增量二次推送全部跳过", second.skippedCount() == 2,
                        "skipped=" + second.skippedCount() + " 期望=2");
                check("增量二次推送不再传输", second.successCount() == 0,
                        "success=" + second.successCount());
            }

            var stats = server.snapshotStats();
            check("服务端统计 files=2", stats.get("files") == 2, String.valueOf(stats));
            check("服务端统计 errors=0", stats.get("errors") == 0, String.valueOf(stats));
        }
    }

    /** onFile 进度回调：逐文件触发一次、增量跳过不触发、回调抛异常不影响推送 */
    private static void testOnFileCallback(Path root) throws Exception {
        Path source = root.resolve("cb-src");
        Path target = root.resolve("cb-dst");
        Files.createDirectories(source);
        for (String name : new String[]{"a.txt", "b.txt", "c.txt"}) {
            Files.write(source.resolve(name), randomBytes(4096));
        }

        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        try (FilePushServer server = new FilePushServer(FilePushConfig.defaults())
                .targetDir(target).host("127.0.0.1").port(0)) {
            int port = server.start();

            try (FilePushClient client = new FilePushClient(FilePushConfig.defaults())
                    .sourceDir(source).host("127.0.0.1").port(port).parallelism(3)
                    .onFile(r -> {
                        calls.incrementAndGet();
                        seen.add(r.relativePath());
                    })) {
                PushResult first = client.push();
                check("onFile 逐文件触发一次", calls.get() == 3, "回调 " + calls.get() + " 次，期望 3");
                check("onFile 覆盖全部文件", seen.equals(Set.of("a.txt", "b.txt", "c.txt")),
                        "实际 " + seen);
                check("onFile 不影响推送结果", !first.hasFailures() && first.successCount() == 3,
                        "success=" + first.successCount() + failures(first));

                // 增量轮次：全部跳过，不产生任务，因此不应回调
                calls.set(0);
                seen.clear();
                client.incremental(true);
                PushResult second = client.push();
                check("增量跳过的文件不触发 onFile", calls.get() == 0, "回调 " + calls.get() + " 次");
                check("增量二次推送全部跳过", second.skippedCount() == 3,
                        "skipped=" + second.skippedCount());

                // 回调抛异常必须被吞掉，不得让整体推送失败
                client.incremental(false).onFile(r -> {
                    throw new IllegalStateException("故意抛出的回调异常");
                });
                PushResult third = client.push();
                check("回调抛异常不影响推送", !third.hasFailures() && third.successCount() == 3,
                        "success=" + third.successCount() + failures(third));

                // 传 null 取消回调
                client.onFile(null);
                PushResult fourth = client.push();
                check("onFile(null) 可取消回调", !fourth.hasFailures() && fourth.successCount() == 3,
                        "success=" + fourth.successCount() + failures(fourth));
            }
        }
    }

    /** 反射读取客户端限流信号量的可用许可数 */
    private static int permits(FilePushClient client) throws Exception {
        Field field = FilePushClient.class.getDeclaredField("fileLimiter");
        field.setAccessible(true);
        return ((Semaphore) field.get(client)).availablePermits();
    }

    /** 失败明细拼成一行，便于断言输出 */
    private static String failures(PushResult result) {
        if (!result.hasFailures()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" [");
        result.failures().forEach(f ->
                sb.append(f.relativePath()).append(':').append(f.error()).append(' '));
        return sb.append(']').toString();
    }

    /** 读文件全部字节，不存在时返回 null */
    private static byte[] readAll(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    /** 安全取文件大小 */
    private static String safeSize(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) + " bytes" : "不存在";
        } catch (IOException e) {
            return "读取失败";
        }
    }

    /** 断言并打印 */
    private static void check(String label, boolean condition, String detail) {
        if (!condition) {
            allPassed = false;
        }
        System.out.println("  [" + (condition ? "PASS" : "FAIL") + "] " + label + " | " + detail);
    }

    /** 生成随机字节 */
    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        return data;
    }

    /** 递归删除目录或文件 */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root).sorted(Comparator.reverseOrder())) {
            walk.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 安静删除
                }
            });
        } catch (IOException ignored) {
            // 安静删除
        }
    }
}
