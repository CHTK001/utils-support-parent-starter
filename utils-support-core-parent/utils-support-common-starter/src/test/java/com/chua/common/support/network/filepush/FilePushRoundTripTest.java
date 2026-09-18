package com.chua.common.support.network.filepush;

import com.chua.common.support.network.filepush.FilePushClient.PushResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

/**
 * 目录推送端到端测试：启动 {@link FilePushServer}，用 {@link FilePushClient}
 * 推送临时目录，校验接收结果与服务端统计。
 *
 * <p>运行方式：直接执行 {@code main}。覆盖场景：</p>
 * <ul>
 *   <li>嵌套子目录、空文件、大文件（多分片）、路径分隔符归一</li>
 *   <li>并发推送（客户端 16 连接并行）</li>
 *   <li>excludes 排除模式</li>
 *   <li>服务端统计计数校验</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FilePushRoundTripTest {

    /** 是否全部通过 */
    private static boolean allPassed;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        Path tmpRoot = Files.createTempDirectory("filepush-test");
        Path sourceDir = tmpRoot.resolve("source");
        Path targetDir = tmpRoot.resolve("target");
        Files.createDirectories(sourceDir);
        Files.createDirectories(Paths.get(sourceDir.toString(), "sub", "deep"));

        // 构造测试文件
        byte[] small = "hello file".getBytes();
        byte[] big = randomBytes(3 * 1024 * 1024 + 7);
        byte[] empty = new byte[0];
        Files.write(sourceDir.resolve("a.txt"), small);
        Files.write(sourceDir.resolve("sub").resolve("b.bin"), big);
        Files.write(sourceDir.resolve("sub", "deep", "c.txt"), empty);
        byte[] excluded = "should not push".getBytes();
        Files.write(sourceDir.resolve("skip.log"), excluded);

        FilePushConfig serverConfig = FilePushConfig.defaults();
        serverConfig.setHost("127.0.0.1");
        serverConfig.setPort(0);
        serverConfig.setTargetDir(targetDir);
        serverConfig.setChunkSize(512 * 1024);

        try (FilePushServer server = new FilePushServer(serverConfig)) {
            int port = server.start();
            System.out.println("[FilePushRoundTripTest] server started on port " + port);

            FilePushConfig clientConfig = FilePushConfig.defaults();
            clientConfig.setHost("127.0.0.1");
            clientConfig.setPort(port);
            clientConfig.setSourceDir(sourceDir);
            clientConfig.setChunkSize(512 * 1024);
            clientConfig.setClientFileParallelism(8);
            clientConfig.setExcludes(Arrays.asList("skip.log"));

            try (FilePushClient client = new FilePushClient(clientConfig)) {
                PushResult result = client.push();
                result.tasks().forEach(t -> System.out.println("task: " + t));
                boolean ok = checkResult(result, 3);
                ok &= verifyTargetFiles(targetDir, small, big, empty);
                ok &= verifyStats(server, 3);
                System.out.println("[FilePushRoundTripTest] " + (ok ? "ALL PASSED" : "SOME TESTS FAILED"));
                allPassed = ok;
            }
        } finally {
            deleteRecursively(tmpRoot);
        }
        System.exit(allPassed ? 0 : 1);
    }

    /**
     * 校验推送结果
     * @param result 结果，不允许为 null
     * @param expectedFiles 方法入参 expectedFiles
     * @return 是否成功（true 表示成功）
     */
    private static boolean checkResult(PushResult result, int expectedFiles) {
        boolean ok = result.hasFailures();
        System.out.println("[checkResult] " + (ok ? "FAIL" : "PASS")
                + " | success=" + result.successCount()
                + " failed=" + result.failedCount()
                + " elapsed=" + result.elapsedMs() + "ms throughput="
                + String.format("%.2f", result.throughputMbs()) + "MB/s");
        if (result.hasFailures()) {
            result.failures().forEach(f ->
                    System.out.println("  failed: " + f.relativePath() + " -> " + f.error()));
        }
        return !ok && result.successCount() == expectedFiles;
    }

    /** 校验目标目录内容 */
    private static boolean verifyTargetFiles(Path targetDir, byte[] small, byte[] big,
                                              byte[] empty) throws IOException {
        boolean ok = true;
        Path a = targetDir.resolve("a.txt");
        Path b = targetDir.resolve("sub").resolve("b.bin");
        Path c = targetDir.resolve("sub", "deep", "c.txt");
        ok &= assertContent(a, small, "a.txt");
        ok &= assertContent(b, big, "sub/b.bin");
        ok &= assertContent(c, empty, "sub/deep/c.txt");
        // skip.log 应被排除
        boolean excludedAbsent = !Files.exists(targetDir.resolve("skip.log"));
        System.out.println("[verifyTargetFiles] " + (excludedAbsent ? "PASS" : "FAIL")
                + " | skip.log excluded=" + excludedAbsent);
        ok &= excludedAbsent;
        return ok;
    }

    /**
     * 校验服务端统计
     * @param server 服务端，不允许为 null
     * @param expectedFiles 方法入参 expectedFiles
     * @return 是否成功（true 表示成功）
     */
    private static boolean verifyStats(FilePushServer server, int expectedFiles) {
        var stats = server.snapshotStats();
        boolean ok = stats.get("files") == expectedFiles
                && stats.get("errors") == 0;
        System.out.println("[verifyStats] " + (ok ? "PASS" : "FAIL") + " | " + stats);
        return ok;
    }

    /** 断言目标文件内容与源一致 */
    private static boolean assertContent(Path file, byte[] expected, String label)
            throws IOException {
        if (!Files.exists(file)) {
            System.out.println("[assertContent] FAIL | " + label + " 不存在");
            return false;
        }
        byte[] actual = Files.readAllBytes(file);
        boolean ok = Arrays.equals(expected, actual);
        System.out.println("[assertContent] " + label + " -> "
                + (ok ? "PASS" : "FAIL") + " (" + actual.length + " bytes)");
        return ok;
    }

    /**
     * 生成随机字节
     * @param size 大小，不允许为 null
     * @return 结果值
     */
    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    /**
     * 递归删除目录
     * @param root 根节点，不允许为 null
     */
    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root).sorted(java.util.Comparator.reverseOrder())) {
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
