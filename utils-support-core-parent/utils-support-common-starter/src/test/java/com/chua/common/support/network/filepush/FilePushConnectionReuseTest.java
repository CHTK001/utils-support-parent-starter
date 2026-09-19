package com.chua.common.support.network.filepush;

import com.chua.common.support.network.filepush.FilePushClient.PushResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 连接复用（{@code filesPerConnection > 1}）端到端测试。
 *
 * <p>协议上启用后握手为 {@code fileCount=N}，服务端在 N 个文件之后还会等待
 * {@code MSG_DONE} 收尾；同时存在两个易错分支，本测试专门覆盖：</p>
 * <ul>
 *   <li><b>尾部单文件连接</b> — 分组后某条连接只剩 1 个文件时，服务端按「单文件连接」
 *       处理（不回 DONE），客户端必须跳过收尾帧，否则会撞上已关闭的连接。</li>
 *   <li><b>分片大小两端不一致</b> — 协议 meta 只带 lastChunk、不带 chunkSize，
 *       服务端须按序累加偏移而非用自身配置反推。</li>
 * </ul>
 *
 * <p>另覆盖：空文件、多分片大文件、中文名、mtime 还原（增量同步的前提）、
 * 复用模式下的增量与清理。</p>
 *
 * <p>运行方式：直接执行 {@code main}（与本模块其他 filepush 测试一致）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FilePushConnectionReuseTest {

    /**
     * 是否全部通过
    */
    private static boolean allPassed = true;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 测试失败
     */
    public static void main(String[] args) throws Exception {
        Path tmpRoot = Files.createTempDirectory("filepush-reuse-test");
        try {
            reuseHappyPath(tmpRoot.resolve("caseA"));
            tailSingleFileConnection(tmpRoot.resolve("caseB"));
            mismatchedChunkSize(tmpRoot.resolve("caseC"));
            incrementalAfterReuse(tmpRoot.resolve("caseD"));
            cleanupAfterReuse(tmpRoot.resolve("caseE"));
        } finally {
            deleteRecursively(tmpRoot);
        }
        System.out.println("[FilePushConnectionReuseTest] "
                + (allPassed ? "ALL PASSED" : "SOME TESTS FAILED"));
        System.exit(allPassed ? 0 : 1);
    }

    /**
     * 用例 A：13 个文件（含空文件、多分片大文件、中文名、子目录），每连接 4 个文件。
     *
     * <p>校验逐文件 SHA-256 与 mtime 一致。</p>
     *
     * @param root 用例根目录
     * @throws Exception 测试失败
     */
    private static void reuseHappyPath(Path root) throws Exception {
        Path src = root.resolve("src");
        Path tgt = root.resolve("tgt");
        Files.createDirectories(src.resolve("sub"));
        Map<String, byte[]> expect = new HashMap<>();
        Random random = new Random(7);
        for (int i = 0; i < 13; i++) {
            String name = i == 0 ? "sub/\u4e2d\u6587\u540d.json" : "f-" + i + ".bin";
            byte[] data;
            if (i == 1) {
                data = new byte[0];
            } else if (i == 2) {
                data = new byte[1_500_000];
                random.nextBytes(data);
            } else {
                data = new byte[1024 * (i + 1)];
                random.nextBytes(data);
            }
            Path p = src.resolve(name);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
            // 把 mtime 打散成明显不同的值，确保「还原 mtime」真的生效而非巧合
            Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_000_000L + i * 86_400_000L));
            expect.put(name.replace('/', java.io.File.separatorChar), data);
        }

        try (FilePushServer server = newServer(tgt, 256 * 1024);
             FilePushClient client = newClient(src, server.start(), 256 * 1024)
                     .filesPerConnection(4).parallelism(8)) {
            PushResult result = client.push();
            allPassed &= check("A 复用推送无失败且数量正确",
                    !result.hasFailures() && result.successCount() == 13,
                    "success=" + result.successCount() + " failed=" + result.failedCount());
            allPassed &= verifyTree(src, tgt, expect, "A");
            allPassed &= check("A 服务端计数", server.snapshotStats().get("files") == 13L,
                    String.valueOf(server.snapshotStats()));
        }
    }

    /**
     * 用例 B：3 个文件、每连接 2 个 → 分组为 [2,1]，第二条连接在协议上退化为
     * {@code fileCount=1}，客户端不得再发 {@code MSG_DONE}。
     *
     * @param root 用例根目录
     * @throws Exception 测试失败
     */
    private static void tailSingleFileConnection(Path root) throws Exception {
        Path src = root.resolve("src");
        Path tgt = root.resolve("tgt");
        Files.createDirectories(src);
        Map<String, byte[]> expect = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            byte[] data = ("tail-" + i).getBytes();
            Files.write(src.resolve("t" + i + ".txt"), data);
            expect.put("t" + i + ".txt", data);
        }

        try (FilePushServer server = newServer(tgt, 64 * 1024);
             FilePushClient client = newClient(src, server.start(), 64 * 1024)
                     .filesPerConnection(2).parallelism(2)) {
            PushResult result = client.push();
            allPassed &= check("B 尾部单文件连接推送成功",
                    !result.hasFailures() && result.successCount() == 3,
                    "success=" + result.successCount() + " failed=" + result.failedCount()
                            + " failures=" + result.failures());
            allPassed &= verifyTree(src, tgt, expect, "B");
        }
    }

    /**
     * 用例 C：客户端 4MB 分片 / 服务端 1MB 分片 → 服务端必须按序累加偏移，
     * 不得用自身 chunkSize 反推。
     *
     * @param root 用例根目录
     * @throws Exception 测试失败
     */
    private static void mismatchedChunkSize(Path root) throws Exception {
        Path src = root.resolve("src");
        Path tgt = root.resolve("tgt");
        Files.createDirectories(src);
        Map<String, byte[]> expect = new HashMap<>();
        Random random = new Random(11);
        for (int i = 0; i < 6; i++) {
            byte[] data = new byte[3_000_000 + i * 111];
            random.nextBytes(data);
            Files.write(src.resolve("m" + i + ".bin"), data);
            expect.put("m" + i + ".bin", data);
        }

        try (FilePushServer server = newServer(tgt, 1024 * 1024);
             FilePushClient client = newClient(src, server.start(), 4 * 1024 * 1024)
                     .filesPerConnection(3).parallelism(4)) {
            PushResult result = client.push();
            allPassed &= check("C 分片大小两端不一致仍成功",
                    !result.hasFailures() && result.successCount() == 6,
                    "success=" + result.successCount() + " failed=" + result.failedCount()
                            + " failures=" + result.failures());
            allPassed &= verifyTree(src, tgt, expect, "C");
        }
    }

    /**
     * 用例 D：复用推送后开启增量 → 全部跳过（证明 meta 带了 mtime 且服务端还原成功）。
     *
     * @param root 用例根目录
     * @throws Exception 测试失败
     */
    private static void incrementalAfterReuse(Path root) throws Exception {
        Path src = root.resolve("src");
        Path tgt = root.resolve("tgt");
        Files.createDirectories(src);
        for (int i = 0; i < 10; i++) {
            Files.write(src.resolve("i" + i + ".txt"), ("inc-" + i).getBytes());
        }

        try (FilePushServer server = newServer(tgt, 64 * 1024)) {
            int port = server.start();
            try (FilePushClient first = newClient(src, port, 64 * 1024).filesPerConnection(3)) {
                PushResult r1 = first.push();
                allPassed &= check("D1 首轮全量成功", !r1.hasFailures() && r1.successCount() == 10,
                        "success=" + r1.successCount());
            }
            try (FilePushClient second = newClient(src, port, 64 * 1024)
                    .filesPerConnection(3).incremental(true)) {
                PushResult r2 = second.push();
                allPassed &= check("D2 增量全跳过（mtime 已还原）",
                        r2.skippedCount() == 10 && r2.successCount() == 0,
                        "skipped=" + r2.skippedCount() + " success=" + r2.successCount());
            }
        }
    }

    /**
     * 用例 E：复用模式下 cleanup 生效，删除源端已不存在的旧文件。
     *
     * @param root 用例根目录
     * @throws Exception 测试失败
     */
    private static void cleanupAfterReuse(Path root) throws Exception {
        Path src = root.resolve("src");
        Path tgt = root.resolve("tgt");
        Files.createDirectories(src);
        for (int i = 0; i < 5; i++) {
            Files.write(src.resolve("k" + i + ".txt"), ("keep-" + i).getBytes());
        }

        // cleanup 需服务端一并开启
        try (FilePushServer server = newServer(tgt, 64 * 1024, true)) {
            int port = server.start();
            try (FilePushClient first = newClient(src, port, 64 * 1024)
                    .filesPerConnection(4).cleanup(true)) {
                first.push();
            }
            // 服务端多出一个源端没有的旧文件
            Files.write(tgt.resolve("stale.txt"), "stale".getBytes());
            try (FilePushClient second = newClient(src, port, 64 * 1024)
                    .filesPerConnection(4).incremental(true).cleanup(true)) {
                second.push();
            }
            allPassed &= check("E 复用模式下 cleanup 删除旧文件",
                    !Files.exists(tgt.resolve("stale.txt")),
                    "stale.txt exists=" + Files.exists(tgt.resolve("stale.txt")));
            allPassed &= check("E 其余文件仍在",
                    Files.exists(tgt.resolve("k0.txt")) && Files.exists(tgt.resolve("k4.txt")),
                    "k0/k4 present");
        }
    }

    /**
     * 构造服务端（端口 0 由系统分配）。
     *
     * @param targetDir 目标目录
     * @param chunkSize 服务端分片大小
     * @return 服务端（未启动）
     */
    private static FilePushServer newServer(Path targetDir, int chunkSize) {
        return newServer(targetDir, chunkSize, false);
    }

    /**
     * 构造服务端（可开启 cleanup）。
     *
     * <p>注意：{@code cleanup} 是<b>两端开关</b>——服务端 {@code cleanupStaleFiles}
     * 会先检查自身的 {@code config.isCleanup()}，只开客户端不生效。</p>
     *
     * @param targetDir 目标目录
     * @param chunkSize 服务端分片大小
     * @param cleanup   服务端是否允许清理旧文件
     * @return 服务端（未启动）
     */
    private static FilePushServer newServer(Path targetDir, int chunkSize, boolean cleanup) {
        FilePushConfig config = FilePushConfig.defaults();
        config.setHost("127.0.0.1");
        config.setPort(0);
        config.setTargetDir(targetDir);
        config.setChunkSize(chunkSize);
        config.setCleanup(cleanup);
        return new FilePushServer(config);
    }

    /**
     * 构造客户端。
     *
     * @param sourceDir 源目录
     * @param port      服务端端口
     * @param chunkSize 客户端分片大小
     * @return 客户端
     */
    private static FilePushClient newClient(Path sourceDir, int port, int chunkSize) {
        FilePushConfig config = FilePushConfig.defaults();
        config.setHost("127.0.0.1");
        config.setPort(port);
        config.setSourceDir(sourceDir);
        config.setChunkSize(chunkSize);
        return new FilePushClient(config);
    }

    /**
     * 逐文件比对目标目录与期望内容（SHA-256）及 mtime。
     *
     * @param src    源目录
     * @param tgt    目标目录
     * @param expect 期望内容（相对路径 → 字节）
     * @param label  用例标签
     * @return 是否全部一致
     * @throws Exception 读取失败
     */
    private static boolean verifyTree(Path src, Path tgt, Map<String, byte[]> expect, String label)
            throws Exception {
        boolean ok = true;
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : new TreeMap<>(expect).entrySet()) {
            Path target = tgt.resolve(e.getKey());
            if (!Files.exists(target)) {
                missing.add(e.getKey());
                ok = false;
                continue;
            }
            if (!sha256(target).equals(sha256(e.getValue()))) {
                ok = false;
                System.out.println("  [" + label + "] 内容不一致: " + e.getKey());
            }
            long srcMtime = Files.getLastModifiedTime(src.resolve(e.getKey())).toMillis();
            long tgtMtime = Files.getLastModifiedTime(target).toMillis();
            if (srcMtime != tgtMtime) {
                ok = false;
                System.out.println("  [" + label + "] mtime 不一致: " + e.getKey()
                        + " src=" + srcMtime + " tgt=" + tgtMtime);
            }
        }
        if (!missing.isEmpty()) {
            System.out.println("  [" + label + "] 缺失文件: " + missing);
        }
        // 目标目录不应多出文件
        long targetCount;
        try (var walk = Files.walk(tgt)) {
            targetCount = walk.filter(Files::isRegularFile).count();
        }
        if (targetCount != expect.size()) {
            ok = false;
            System.out.println("  [" + label + "] 目标文件数 " + targetCount
                    + " != 期望 " + expect.size());
        }
        return check(label + " 内容/mtime/数量全部一致（" + expect.size() + " 个文件）", ok, "");
    }

    /**
     * 断言并打印。
     *
     * @param name   断言名
     * @param passed 是否通过
     * @param detail 附加信息
     * @return passed
     */
    private static boolean check(String name, boolean passed, String detail) {
        System.out.println("  [" + (passed ? "PASS" : "FAIL") + "] " + name
                + (detail == null || detail.isEmpty() ? "" : " | " + detail));
        return passed;
    }

    /**
     * 计算字节的 SHA-256 十六进制串。
     *
     * @param data 数据
     * @return 摘要
     * @throws Exception 算法不可用
     */
    private static String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /**
     * 计算文件的 SHA-256 十六进制串。
     *
     * @param file 文件
     * @return 摘要
     * @throws Exception 读取失败
     */
    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    /**
     * 字节数组转十六进制。
     *
     * @param bytes 字节
     * @return 十六进制串
     */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 递归删除目录。
     *
     * @param root 根目录
     */
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
