package com.chua.common.support.network.filepush;

import com.chua.common.support.network.filepush.FilePushClient.FileTaskResult;
import com.chua.common.support.network.filepush.FilePushClient.PushResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实同步演示：把文件真的推到目标目录，逐文件打印落地过程，再逐字节校验。
 *
 * <p>与 {@link FilePushRoundTripTest}/{@link FilePushFluentApiTest} 的区别在于本类不以
 * 断言输出为主，而是把同步过程打印出来：源目录树 → 每个文件推送完成的实时进度 →
 * 目标目录树 → 源/目标 SHA-256 逐文件比对。</p>
 *
 * <p><b>运行模式</b>（{@code -D} 系统属性）：</p>
 * <pre>
 * filepush.demo.mode    local（默认）：进程内起服务端，可做字节级校验与目录树打印
 *                       remote：只连远端已运行的服务端，改用"二次增量全跳过"证明同步落地
 * filepush.demo.host    默认 127.0.0.1
 * filepush.demo.port    local 默认 0（自动分配）；remote 默认 9777
 * filepush.demo.target  local 接收目录，默认 E:/temp/filepush-demo/dst-&lt;scale&gt;
 * filepush.demo.work    工作根目录，默认 E:/temp/filepush-demo（E: 不可用时退回 java.io.tmpdir）
 * filepush.demo.scale   full（local 默认，约 117MB / 252 文件）
 *                       small（remote 默认，约 1.8MB / 23 文件）
 * filepush.demo.keep    true 时保留生成的数据，便于人工打开查看；默认 false（跑完清理）
 * </pre>
 *
 * <p><b>远端示例</b>（凭据不入源码，目标机需先启动 FilePushServer）：</p>
 * <pre>
 * java -Dfilepush.demo.mode=remote -Dfilepush.demo.host=&lt;远端主机&gt; \
 *      -Dfilepush.demo.port=9777 -Dfilepush.demo.scale=small \
 *      com.chua.common.support.network.filepush.FilePushSyncDemoTest
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FilePushSyncDemoTest {

    /** 排除模式：日志目录不参与同步，用于演示 excludes 生效 */
    private static final String EXCLUDE_PATTERN = "logs/";

    /** 校验和流式读取缓冲 */
    private static final int DIGEST_BUFFER = 64 * 1024;

    /** 超过该文件数则只打印目录级汇总，不逐个列文件 */
    private static final int TREE_DETAIL_LIMIT = 60;

    private static boolean allPassed = true;

    private FilePushSyncDemoTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean remote = "remote".equalsIgnoreCase(System.getProperty("filepush.demo.mode", "local"));
        String scale = System.getProperty("filepush.demo.scale", remote ? "small" : "full");
        String host = System.getProperty("filepush.demo.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("filepush.demo.port", remote ? "9777" : "0"));
        boolean keep = Boolean.parseBoolean(System.getProperty("filepush.demo.keep", "false"));

        Path work = Path.of(System.getProperty("filepush.demo.work", defaultWorkDir()));
        Path source = work.resolve("src-" + scale);
        Path target = Path.of(System.getProperty("filepush.demo.target",
                work.resolve("dst-" + scale).toString()));

        Dataset dataset = Dataset.of(scale);
        System.out.println("========== FilePush 真实同步演示 ==========");
        System.out.println("模式       : " + (remote ? "remote（连远端已运行的服务端）" : "local（进程内起服务端）"));
        System.out.println("数据集     : " + scale + " → " + dataset.pushedFiles + " 个待同步文件（另有 1 个被 excludes 排除）");
        System.out.println("同步目录(源): " + source.toAbsolutePath());
        System.out.println("同步目录(目标): " + (remote ? host + ":" + port + " 上的接收目录" : target.toAbsolutePath()));
        System.out.println();

        try {
            buildDataset(source, dataset);
            printTree("源目录", source, null);

            if (remote) {
                runRemote(host, port, source, dataset);
            } else {
                runLocal(host, source, target, dataset);
            }
        } finally {
            if (!keep) {
                deleteRecursively(source);
                if (!remote) {
                    deleteRecursively(target);
                }
            } else {
                System.out.println();
                System.out.println("已保留数据（filepush.demo.keep=true）：" + source.toAbsolutePath()
                        + (remote ? "" : " 与 " + target.toAbsolutePath()));
            }
        }

        System.out.println();
        System.out.println("[FilePushSyncDemoTest] " + (allPassed ? "ALL PASSED" : "SOME CHECKS FAILED"));
        System.exit(allPassed ? 0 : 1);
    }

    // ------------------------------------------------------------------ local

    /**
     * 本地模式：进程内起服务端，做字节级校验。
     */
    private static void runLocal(String host, Path source, Path target, Dataset dataset) throws Exception {
        deleteRecursively(target);
        try (FilePushServer server = FilePushServer.create()
                .targetDir(target)
                .host("127.0.0.1")
                .port(0)
                .chunkSize(1024 * 1024)
                .connectionParallelism(48)
                .cleanup(true)) {
            int port = server.start();
            System.out.println();
            System.out.println("--- 阶段 1：全量同步 → 127.0.0.1:" + port + " ---");

            PushResult first = pushWithProgress(source, host, port, dataset.pushedFiles, false, true);
            check("全量同步无失败", !first.hasFailures(), failures(first));
            check("全量同步文件数一致", first.successCount() == dataset.pushedFiles,
                    "成功 " + first.successCount() + " / 期望 " + dataset.pushedFiles);

            printTree("目标目录（同步后）", target, dataset);
            verifyByteIdentical(source, target, dataset);
            check("被排除的日志未同步", !Files.exists(target.resolve("logs/app.log")),
                    "目标端不应出现 " + EXCLUDE_PATTERN + " 下的文件");

            System.out.println();
            System.out.println("--- 阶段 2：二次增量同步（应全部跳过）---");
            PushResult second = pushWithProgress(source, host, port, 0, true, true);
            check("增量跳过全部文件", second.skippedCount() == dataset.pushedFiles,
                    "跳过 " + second.skippedCount() + " / 期望 " + dataset.pushedFiles);
            check("增量未重复传输", second.successCount() == 0, "成功 " + second.successCount());

            System.out.println();
            System.out.println("--- 阶段 3：改动 1 个 + 新增 1 个 + 目标端留 1 个陈旧文件 ---");
            Path modified = source.resolve("docs/notes-01.md");
            Files.writeString(modified, Files.readString(modified) + "\n## 追加的一段（增量应重推本文件）\n");
            Path added = source.resolve("data/orders/2026/09/order-9999.csv");
            Files.writeString(added, csvBody(9999, 64), StandardCharsets.UTF_8);
            Path stale = target.resolve("stale-should-be-cleaned.txt");
            Files.writeString(stale, "上一轮遗留、本轮源端已不存在的文件\n", StandardCharsets.UTF_8);

            Dataset grown = dataset.withPushedFiles(dataset.pushedFiles + 1);
            PushResult third = pushWithProgress(source, host, port, 2, true, true);
            check("增量只重推变更的 2 个", third.successCount() == 2,
                    "成功 " + third.successCount() + "（期望 2：notes-01.md 与 order-9999.csv）");
            check("其余文件全部跳过", third.skippedCount() == dataset.pushedFiles - 1,
                    "跳过 " + third.skippedCount() + " / 期望 " + (dataset.pushedFiles - 1));
            check("陈旧文件被 cleanup 删除", !Files.exists(stale), "目标端仍存在 " + stale.getFileName());
            check("新增文件已落地", Files.isRegularFile(target.resolve("data/orders/2026/09/order-9999.csv")),
                    "目标端缺少 order-9999.csv");

            System.out.println();
            System.out.println("--- 阶段 4：变更后再次逐字节校验 ---");
            verifyByteIdentical(source, target, grown);

            Map<String, Long> stats = server.snapshotStats();
            System.out.println();
            System.out.println("服务端统计: " + stats);
            check("服务端无错误", stats.getOrDefault("errors", -1L) == 0L, "errors=" + stats.get("errors"));
        }
    }

    /**
     * 远端模式：无法访问对端文件系统，改用"二次增量全跳过"证明每个文件都已按
     * 相同 size + mtime 落到远端——这是服务端清单协商的直接结果。
     */
    private static void runRemote(String host, int port, Path source, Dataset dataset) throws Exception {
        System.out.println();
        System.out.println("--- 阶段 1：全量同步 → " + host + ":" + port + " ---");
        PushResult first = pushWithProgress(source, host, port, dataset.pushedFiles, false, false);
        check("全量同步无失败", !first.hasFailures(), failures(first));
        check("全量同步文件数一致", first.successCount() == dataset.pushedFiles,
                "成功 " + first.successCount() + " / 期望 " + dataset.pushedFiles);

        System.out.println();
        System.out.println("--- 阶段 2：二次增量同步（远端清单应覆盖全部文件）---");
        PushResult second = pushWithProgress(source, host, port, 0, true, false);
        check("远端已持有全部文件（size+mtime 一致才会跳过）",
                second.skippedCount() == dataset.pushedFiles,
                "跳过 " + second.skippedCount() + " / 期望 " + dataset.pushedFiles);
        check("远端二次同步零传输", second.successCount() == 0, "成功 " + second.successCount());
        System.out.println();
        System.out.println("说明：远端文件系统不可直接访问，故以“二次增量全部跳过”作为落地证据——");
        System.out.println("      服务端清单里每个文件的 size 与 mtime 都与源端一致，才会出现 0 传输。");
    }

    // ------------------------------------------------------------------ push

    /**
     * 用完整链式 API 建客户端并推送，逐文件打印实时进度。
     *
     * @param expectedTransfer 本轮预期真正传输的文件数（增量轮次只有变更文件会回调）
     */
    private static PushResult pushWithProgress(Path source, String host, int port, long expectedTransfer,
                                               boolean incremental, boolean cleanup) throws IOException {
        AtomicLong done = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        try (FilePushClient client = FilePushClient.create()
                .sourceDir(source)
                .host(host)
                .port(port)
                .chunkSize(1024 * 1024)
                .parallelism(32)
                .excludes(EXCLUDE_PATTERN)
                .incremental(incremental)
                .cleanup(cleanup)
                .onFile(result -> {
                    long index = done.incrementAndGet();
                    long cumulative = result.success() ? bytes.addAndGet(result.fileSize()) : bytes.get();
                    System.out.printf("  [%3d/%3d] %-4s %-52s %10s B  累计 %s%n",
                            index, expectedTransfer, result.success() ? "OK" : "FAIL",
                            abbreviate(result.relativePath(), 52),
                            String.format("%,d", result.fileSize()), humanBytes(cumulative));
                    if (!result.success()) {
                        System.out.println("           └─ " + result.error());
                    }
                })) {
            System.out.println("链式创建客户端: sourceDir=" + source.getFileName()
                    + " host=" + host + " port=" + port
                    + " incremental=" + incremental + " cleanup=" + cleanup
                    + " excludes=[" + EXCLUDE_PATTERN + "]");
            if (expectedTransfer == 0) {
                System.out.println("  （本轮预期零传输：所有文件都应命中服务端清单而被跳过）");
            }
            PushResult result = client.push();
            System.out.println("推送结果: 成功 " + result.successCount()
                    + "，跳过 " + result.skippedCount()
                    + "，失败 " + result.failedCount()
                    + "，耗时 " + result.elapsedMs() + " ms"
                    + "，吞吐 " + String.format("%.2f", result.throughputMbs()) + " MB/s");
            return result;
        }
    }

    // ------------------------------------------------------------------ verify

    /**
     * 逐文件比对源与目标的 SHA-256、大小、修改时间，并检查目标端没有多余文件。
     */
    private static void verifyByteIdentical(Path source, Path target, Dataset dataset) throws IOException {
        List<Path> sourceFiles = listPushedFiles(source);
        int mismatched = 0;
        int missing = 0;
        List<String> problems = new ArrayList<>();
        for (Path file : sourceFiles) {
            String rel = source.relativize(file).toString().replace('\\', '/');
            Path mirrored = target.resolve(rel);
            if (!Files.isRegularFile(mirrored)) {
                missing++;
                if (problems.size() < 10) {
                    problems.add("缺失 " + rel);
                }
                continue;
            }
            if (Files.size(file) != Files.size(mirrored)) {
                mismatched++;
                if (problems.size() < 10) {
                    problems.add("大小不符 " + rel + " " + Files.size(file) + " != " + Files.size(mirrored));
                }
                continue;
            }
            if (!sha256(file).equals(sha256(mirrored))) {
                mismatched++;
                if (problems.size() < 10) {
                    problems.add("校验和不符 " + rel);
                }
                continue;
            }
            long srcMtime = Files.getLastModifiedTime(file).toMillis();
            long dstMtime = Files.getLastModifiedTime(mirrored).toMillis();
            if (srcMtime != dstMtime) {
                mismatched++;
                if (problems.size() < 10) {
                    problems.add("修改时间不符 " + rel + " " + srcMtime + " != " + dstMtime);
                }
            }
        }
        List<String> extra = new ArrayList<>();
        for (Path file : listAllFiles(target)) {
            String rel = target.relativize(file).toString().replace('\\', '/');
            if (rel.startsWith("logs/")) {
                extra.add("被排除却出现 " + rel);
            }
        }
        System.out.println();
        System.out.println("逐字节校验: 源端待同步 " + sourceFiles.size() + " 个文件"
                + "，缺失 " + missing + "，不一致 " + mismatched
                + (extra.isEmpty() ? "" : "，排除项泄漏 " + extra.size()));
        problems.forEach(p -> System.out.println("  ! " + p));
        extra.forEach(p -> System.out.println("  ! " + p));
        check("源端待同步文件数与数据集一致", sourceFiles.size() == dataset.pushedFiles,
                "实际 " + sourceFiles.size() + " / 期望 " + dataset.pushedFiles);
        check("目标端无缺失文件", missing == 0, "缺失 " + missing + " 个");
        check("目标端文件与源端逐字节一致（SHA-256 + size + mtime）", mismatched == 0,
                "不一致 " + mismatched + " 个");
        check("excludes 未泄漏到目标端", extra.isEmpty(), String.join("; ", extra));
    }

    // ------------------------------------------------------------------ dataset

    /**
     * 数据集规格：文件构成与数量。
     *
     * <p>{@code pushedFiles} = README 1 + config 3 + docCount + csvCount + binCount，
     * 不含被 excludes 排除的 {@code logs/app.log}。
     * full = 1+3+40+200+8 = 252；small = 1+3+6+12+2 = 24。</p>
     */
    private record Dataset(int docCount, int docRows, int csvCount, int csvRows,
                           int binCount, int binBytes, int pushedFiles) {

        static Dataset of(String scale) {
            return "small".equalsIgnoreCase(scale)
                    ? new Dataset(6, 120, 12, 900, 2, 512 * 1024, 24)
                    : new Dataset(40, 900, 200, 4200, 8, 8 * 1024 * 1024, 252);
        }

        Dataset withPushedFiles(int files) {
            return new Dataset(docCount, docRows, csvCount, csvRows, binCount, binBytes, files);
        }
    }

    /**
     * 生成一份结构真实的数据集：配置、文档、订单 CSV、二进制包，外加一个被排除的日志。
     */
    private static void buildDataset(Path root, Dataset d) throws IOException {
        deleteRecursively(root);
        write(root.resolve("README.md"), """
                # FilePush 同步演示数据

                本目录由 FilePushSyncDemoTest 自动生成，用于验证目录推送的端到端一致性。

                - `config/`  应用配置
                - `docs/`    Markdown 文档
                - `data/orders/2026/09/` 订单明细 CSV
                - `lib/`     二进制包
                - `logs/`    日志（被 excludes 排除，不应出现在目标端）
                """);
        write(root.resolve("config/app.properties"), """
                app.name=file-push-demo
                app.version=4.0.0.42
                server.port=8080
                filepush.host=127.0.0.1
                filepush.port=9777
                filepush.chunk-size=1048576
                filepush.client-parallelism=32
                """);
        write(root.resolve("config/jdbc.properties"), """
                jdbc.driver=com.microsoft.sqlserver.jdbc.SQLServerDriver
                jdbc.url=jdbc:sqlserver://${DB_HOST};databaseName=${DB_NAME};encrypt=false
                jdbc.username=${DB_USER}
                jdbc.password=${DB_PASSWORD}
                jdbc.pool.max-size=20
                """);
        write(root.resolve("config/logback.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration>
                    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
                    </appender>
                    <root level="INFO"><appender-ref ref="STDOUT"/></root>
                </configuration>
                """);
        write(root.resolve("logs/app.log"), "本文件位于 logs/ 下，应被 excludes 排除\n");

        for (int i = 1; i <= d.docCount(); i++) {
            write(root.resolve(String.format("docs/notes-%02d.md", i)), markdown(i, d.docRows()));
        }
        for (int i = 1; i <= d.csvCount(); i++) {
            write(root.resolve(String.format("data/orders/2026/09/order-%04d.csv", i)),
                    csvBody(i, d.csvRows()));
        }
        Random random = new Random(20260914L);
        for (int i = 0; i < d.binCount(); i++) {
            Path file = root.resolve(String.format("lib/blob-%02d.bin", i));
            Files.createDirectories(file.getParent());
            byte[] block = new byte[Math.min(64 * 1024, d.binBytes())];
            try (OutputStream out = Files.newOutputStream(file)) {
                for (int written = 0; written < d.binBytes(); written += block.length) {
                    random.nextBytes(block);
                    out.write(block, 0, (int) Math.min(block.length, d.binBytes() - written));
                }
            }
        }
        System.out.println("已生成源数据集：" + listAllFiles(root).size() + " 个文件（含 1 个待排除日志）");
    }

    private static String markdown(int index, int rows) {
        StringBuilder sb = new StringBuilder(rows * 64);
        sb.append("# 设计说明 ").append(String.format("%02d", index)).append("\n\n");
        for (int i = 0; i < rows; i++) {
            sb.append("- 条目 ").append(i)
                    .append("：目录推送在分片 ").append(i % 17)
                    .append(" 处保持顺序，落盘采用定位写，因此第 ").append(index)
                    .append(" 篇文档的内容可以逐字节复现。\n");
        }
        return sb.toString();
    }

    private static String csvBody(int idBase, int rows) {
        StringBuilder sb = new StringBuilder(rows * 72 + 64);
        sb.append("orderId,customerId,product,quantity,amount,createdAt\n");
        for (int i = 0; i < rows; i++) {
            sb.append(idBase * 100000L + i).append(',')
                    .append(1000 + (i % 400)).append(',')
                    .append("SKU-").append(String.format("%05d", (idBase * 31 + i) % 9999)).append(',')
                    .append(1 + i % 9).append(',')
                    .append(String.format("%.2f", (idBase * 7.13 + i * 3.77) % 5000)).append(',')
                    .append("2026-09-").append(String.format("%02d", 1 + i % 28))
                    .append('T').append(String.format("%02d", i % 24)).append(":00:00\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ output

    /** 打印目录树；文件数超过 TREE_DETAIL_LIMIT 时只打印目录级汇总 */
    private static void printTree(String label, Path root, Dataset dataset) throws IOException {
        List<Path> files = listAllFiles(root);
        long bytes = 0;
        Map<String, long[]> byDir = new LinkedHashMap<>();
        for (Path file : files) {
            bytes += Files.size(file);
            String rel = root.relativize(file).toString().replace('\\', '/');
            int slash = rel.lastIndexOf('/');
            String dir = slash < 0 ? "(根目录)" : rel.substring(0, slash);
            byDir.computeIfAbsent(dir, k -> new long[2]);
            long[] acc = byDir.get(dir);
            acc[0]++;
            acc[1] += Files.size(file);
        }
        System.out.println();
        System.out.println("── " + label + " " + root.toAbsolutePath()
                + "  （" + files.size() + " 个文件，" + humanBytes(bytes) + "）");
        if (files.size() <= TREE_DETAIL_LIMIT) {
            files.stream()
                    .sorted(Comparator.comparing(p -> p.toString()))
                    .forEach(p -> System.out.printf("   %-58s %12s B%n",
                            root.relativize(p).toString().replace('\\', '/'),
                            String.format("%,d", safeSize(p))));
        } else {
            byDir.forEach((dir, acc) -> System.out.printf("   %-42s %4d 个文件  %10s%n",
                    dir + "/", acc[0], humanBytes(acc[1])));
            System.out.println("   （文件数超过 " + TREE_DETAIL_LIMIT + "，仅列目录级汇总）");
        }
        if (dataset != null) {
            System.out.println("   其中 logs/ 下的文件不参与同步（excludes=\"" + EXCLUDE_PATTERN + "\"）");
        }
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : "..." + text.substring(text.length() - max + 3);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> listAllFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /** 源端实际参与同步的文件（应用 excludes 后的清单） */
    private static List<Path> listPushedFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path file : listAllFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (!rel.contains(EXCLUDE_PATTERN)) {
                result.add(file);
            }
        }
        return result;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[DIGEST_BUFFER];
            try (var in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String defaultWorkDir() {
        Path candidate = Path.of("E:/temp/filepush-demo");
        Path parent = candidate.getParent();
        return Files.isDirectory(parent)
                ? candidate.toString()
                : Path.of(System.getProperty("java.io.tmpdir"), "filepush-demo").toString();
    }

    private static String failures(PushResult result) {
        List<FileTaskResult> failures = result.failures();
        if (failures.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        failures.stream().limit(5).forEach(f -> sb.append("\n  ").append(f.relativePath())
                .append(" → ").append(f.error()));
        if (failures.size() > 5) {
            sb.append("\n  ... 另有 ").append(failures.size() - 5).append(" 个");
        }
        return sb.toString();
    }

    private static void check(String label, boolean condition, String detail) {
        if (!condition) {
            allPassed = false;
        }
        System.out.println((condition ? "  [PASS] " : "  [FAIL] ") + label
                + (condition ? "" : " — " + detail));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
