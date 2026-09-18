package com.chua.common.support.network.filepush;

import com.chua.common.support.network.filepush.FilePushClient.PushResult;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 目录推送吞吐基准与语义验证。
 *
 * <p>运行方式：直接执行 {@code main}。覆盖：</p>
 * <ul>
 *   <li><b>少量大文件</b> — 8 × 64 MB，检验分片流水线与写盘并发能否跑满磁盘</li>
 *   <li><b>大量小文件</b> — 4000 × 8 KB（40 子目录），检验每文件一条连接的握手开销</li>
 *   <li><b>分片大小扫描</b> — 大文件场景下 1 MB / 4 MB / 8 MB 对比</li>
 *   <li><b>增量同步</b> — 二次推送应全部跳过，并校验服务端 mtime 与源一致</li>
 *   <li><b>增量 + 清理共存</b> — 未变更文件不得被 cleanup 误删（历史 pushedFiles 缺陷场景）</li>
 *   <li><b>落盘原语 A/B</b> — 每分片 RandomAccessFile 开关 vs 单 FileChannel 定位写</li>
 * </ul>
 *
 * <p>基准数据落在系统临时目录（用例结束递归删除），不触碰工程目录。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FilePushBenchmark {

    /** MB 换算基数 */
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    /** 大文件场景：文件数 */
    private static final int LARGE_FILE_COUNT = 8;

    /** 大文件场景：单文件 MB */
    private static final int LARGE_FILE_MB = 64;

    /** 小文件场景：文件数 */
    private static final int SMALL_FILE_COUNT = 4000;

    /** 小文件场景：单文件字节数 */
    private static final int SMALL_FILE_BYTES = 8 * 1024;

    /** 小文件场景：子目录数 */
    private static final int SMALL_FILE_DIRS = 40;

    /** 落盘原语 A/B：分片数 */
    private static final int PRIMITIVE_CHUNKS = 512;

    /** 落盘原语 A/B：分片字节数 */
    private static final int PRIMITIVE_CHUNK_BYTES = 1024 * 1024;

    /** 基准结果汇总行 */
    private static final List<String> SUMMARY = new ArrayList<>();

    /** 是否全部通过 */
    private static boolean allPassed = true;

    /**
     * main。
     *
     * @param args 参数
     * @throws Exception 基准执行异常
     */
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("filepush-bench");
        System.out.println("工作目录: " + root.toAbsolutePath());
        try {
            benchLargeFiles(root);
            benchChunkSizeSweep(root);
            benchManySmallFiles(root);
            benchIncremental(root);
            benchIncrementalWithCleanup(root);
            benchWritePrimitives(root);
        } finally {
            deleteRecursively(root);
        }

        System.out.println();
        System.out.println("================ 基准汇总 ================");
        SUMMARY.forEach(System.out::println);
        System.out.println("==========================================");
        System.out.println(allPassed ? "ALL PASSED" : "SOME CHECKS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /**
     * 场景一：少量大文件全量推送
     * @param root 根节点，不允许为 null
     */
    private static void benchLargeFiles(Path root) throws Exception {
        Path source = root.resolve("large-src");
        Path target = root.resolve("large-dst");
        byte[] payload = randomBytes(LARGE_FILE_MB * 1024 * 1024);
        Files.createDirectories(source);
        // 同一份内容复制出多个文件：避免基准时间被随机数生成吃掉
        Path first = source.resolve("blob-0.bin");
        Files.write(first, payload);
        for (int i = 1; i < LARGE_FILE_COUNT; i++) {
            Files.copy(first, source.resolve("blob-" + i + ".bin"));
        }
        long totalBytes = (long) LARGE_FILE_COUNT * payload.length;

        PushResult result = push(source, target, FilePushConfig.DEFAULT_CHUNK_SIZE, 0, false, false);
        boolean ok = !result.hasFailures() && result.successCount() == LARGE_FILE_COUNT;
        ok &= assertTargetBytes(target, totalBytes, "large-files");
        record("大文件 8x64MB (512MB, chunk=1MB)", result, ok);
        deleteRecursively(target);
    }

    /**
     * 场景二：大文件下分片大小扫描
     * @param root 根节点，不允许为 null
     */
    private static void benchChunkSizeSweep(Path root) throws Exception {
        Path source = root.resolve("large-src");
        long totalBytes = (long) LARGE_FILE_COUNT * LARGE_FILE_MB * 1024 * 1024;
        int[] chunkSizes = {1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024};
        for (int chunkSize : chunkSizes) {
            Path target = root.resolve("sweep-" + (chunkSize / 1024 / 1024) + "mb");
            PushResult result = push(source, target, chunkSize, 0, false, false);
            boolean ok = !result.hasFailures() && assertTargetBytes(target, totalBytes, "sweep");
            record("大文件 chunk=" + (chunkSize / 1024 / 1024) + "MB", result, ok);
            deleteRecursively(target);
        }
    }

    /**
     * 场景三：大量小文件全量推送
     * @param root 根节点，不允许为 null
     */
    private static void benchManySmallFiles(Path root) throws Exception {
        Path source = root.resolve("small-src");
        Path target = root.resolve("small-dst");
        byte[] payload = randomBytes(SMALL_FILE_BYTES);
        int perDir = SMALL_FILE_COUNT / SMALL_FILE_DIRS;
        for (int d = 0; d < SMALL_FILE_DIRS; d++) {
            Path dir = source.resolve("dir-" + d);
            Files.createDirectories(dir);
            for (int i = 0; i < perDir; i++) {
                Files.write(dir.resolve("f-" + i + ".dat"), payload);
            }
        }
        long totalBytes = (long) SMALL_FILE_COUNT * SMALL_FILE_BYTES;

        PushResult result = push(source, target, FilePushConfig.DEFAULT_CHUNK_SIZE, 0, false, false);
        boolean ok = !result.hasFailures() && result.successCount() == SMALL_FILE_COUNT;
        ok &= assertTargetBytes(target, totalBytes, "small-files");
        record("小文件 4000x8KB (31MB, 40 目录)", result, ok);
    }

    /**
     * 场景四：增量同步——二次推送应全部跳过，且服务端 mtime 与源一致
     * @param root 根节点，不允许为 null
     */
    private static void benchIncremental(Path root) throws Exception {
        Path source = root.resolve("small-src");
        Path target = root.resolve("small-dst");

        PushResult second = push(source, target, FilePushConfig.DEFAULT_CHUNK_SIZE, 0, true, false);
        boolean ok = !second.hasFailures();
        ok &= check("增量二次推送全部跳过", second.skippedCount() == SMALL_FILE_COUNT,
                "skipped=" + second.skippedCount() + " 期望=" + SMALL_FILE_COUNT);
        ok &= check("增量二次推送不再传输文件", second.successCount() == 0,
                "success=" + second.successCount() + " 期望=0");
        ok &= checkMtimePreserved(source, target, 20);
        record("增量同步（二次推送）", second, ok);
    }

    /**
     * 场景五：增量与清理共存——未变更文件不得被误删
     * @param root 根节点，不允许为 null
     */
    private static void benchIncrementalWithCleanup(Path root) throws Exception {
        Path source = root.resolve("cleanup-src");
        Path target = root.resolve("cleanup-dst");
        Files.createDirectories(source);
        byte[] payload = randomBytes(4096);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String name = "c-" + i + ".dat";
            names.add(name);
            Files.write(source.resolve(name), payload);
        }
        push(source, target, FilePushConfig.DEFAULT_CHUNK_SIZE, 0, true, true);

        // 改动 1 个、删除 1 个：二次扫描到 199 个，其中 1 个需重推、198 个未变更
        Files.write(source.resolve(names.getFirst()), randomBytes(8192));
        Files.delete(source.resolve(names.get(1)));

        PushResult result = push(source, target, FilePushConfig.DEFAULT_CHUNK_SIZE, 0, true, true);
        boolean ok = !result.hasFailures();
        ok &= check("cleanup: 变更文件已重推", result.successCount() == 1,
                "success=" + result.successCount() + " 期望=1");
        ok &= check("cleanup: 未变更文件已跳过", result.skippedCount() == 198,
                "skipped=" + result.skippedCount() + " 期望=198");
        ok &= check("cleanup: 未变更文件未被误删", Files.exists(target.resolve(names.get(5))),
                names.get(5) + " 应存在");
        ok &= check("cleanup: 源端已删文件被清理", !Files.exists(target.resolve(names.get(1))),
                names.get(1) + " 应被删除");
        ok &= check("cleanup: 变更文件内容已更新", Files.size(target.resolve(names.getFirst())) == 8192,
                "size=" + safeSize(target.resolve(names.getFirst())));
        record("增量 + 清理共存", result, ok);
    }

    /**
     * 场景六：落盘原语实验矩阵。
     *
     * <p>首轮基准出现反直觉结果——共享 FileChannel 定位写反而慢于每分片 RandomAccessFile。
     * 怀疑 JDK {@code FileChannelImpl.write(ByteBuffer, long)} 对 {@code positionLock}
     * 加锁，使同一通道上的并发定位写退化为串行。为定位瓶颈，对每种落盘方式分别在
     * <b>并发</b>与<b>单线程</b>下各测一次：单线程值隔离"开关句柄"开销，
     * 并发值 / 单线程值的加速比则暴露"共享通道锁竞争"。</p>
     * @param root 根节点，不允许为 null
     */
    private static void benchWritePrimitives(Path root) throws Exception {
        Path dir = root.resolve("primitive");
        Files.createDirectories(dir);
        byte[] chunk = randomBytes(PRIMITIVE_CHUNK_BYTES);
        long totalBytes = (long) PRIMITIVE_CHUNKS * PRIMITIVE_CHUNK_BYTES;
        double totalMb = totalBytes / BYTES_PER_MB;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        System.out.printf("%n[落盘原语矩阵 %dx%dKB = %.0fMB]%n", PRIMITIVE_CHUNKS,
                PRIMITIVE_CHUNK_BYTES / 1024, totalMb);
        System.out.printf("  %-34s %10s %10s %8s | %10s %10s %8s%n",
                "落盘方式", "并发ms", "MB/s", "加速比", "单线程ms", "MB/s", "");
        try {
            for (WriteStrategy strategy : WriteStrategy.values()) {
                Path file = dir.resolve(strategy.name().toLowerCase() + ".bin");
                double parallelMs = timedWrite(file, chunk, pool, true, strategy);
                long parallelSize = Files.size(file);
                deleteRecursively(file);

                double serialMs = timedWrite(file, chunk, pool, false, strategy);
                long serialSize = Files.size(file);
                deleteRecursively(file);

                boolean ok = parallelSize == totalBytes && serialSize == totalBytes;
                allPassed &= check("落盘原语 " + strategy.name() + " 结果完整", ok,
                        "并发size=" + parallelSize + " 单线程size=" + serialSize
                                + " expected=" + totalBytes);

                double parallelMbs = totalMb / (parallelMs / 1000.0);
                double serialMbs = totalMb / (serialMs / 1000.0);
                System.out.printf("  %-34s %10.0f %10.2f %7.2fx | %10.0f %10.2f%n",
                        strategy.label, parallelMs, parallelMbs, serialMs / Math.max(parallelMs, 0.001),
                        serialMs, serialMbs);
                SUMMARY.add(String.format(
                        "落盘 %-22s 并发=%6.0fms(%7.2f MB/s) 单线程=%6.0fms(%7.2f MB/s) 加速比=%.2fx",
                        strategy.label, parallelMs, parallelMbs, serialMs, serialMbs,
                        serialMs / Math.max(parallelMs, 0.001)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 按指定策略写满整个文件并计时。
     *
     * @param file 目标文件
     * @param chunk 单分片内容
     * @param pool 虚拟线程池
     * @param concurrent true 表示分片并发派发，false 表示在调用线程串行写
     * @param strategy 落盘策略
     * @return 耗时（毫秒）
     * @throws Exception 写入失败或等待被中断
     */
    private static double timedWrite(Path file, byte[] chunk, ExecutorService pool,
                                     boolean concurrent, WriteStrategy strategy) throws Exception {
        Files.deleteIfExists(file);
        Files.createFile(file);
        CountDownLatch done = new CountDownLatch(PRIMITIVE_CHUNKS);
        AtomicInteger failures = new AtomicInteger();
        long start = System.nanoTime();
        try (FileChannel shared = strategy.needsSharedChannel()
                ? FileChannel.open(file, StandardOpenOption.WRITE) : null) {
            try {
                for (int i = 0; i < PRIMITIVE_CHUNKS; i++) {
                    final int index = i;
                    if (concurrent) {
                        pool.execute(() -> {
                            try {
                                strategy.write(file, shared, chunk, index);
                            } catch (IOException e) {
                                failures.incrementAndGet();
                            } finally {
                                done.countDown();
                            }
                        });
                    } else {
                        done.countDown();
                        strategy.write(file, shared, chunk, index);
                    }
                }
            } finally {
                done.await();
            }
        }
        if (failures.get() > 0) {
            throw new IOException(strategy.name() + " 写入失败 " + failures.get() + " 次");
        }
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    /** 定位写，循环直到缓冲区全部写完 */
    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }

    /**
     * 落盘策略。
     *
     * <p>三种方式写入的数据区间互不重叠，结果必须完全一致，差异只在句柄与锁的开销。</p>
     */
    private enum WriteStrategy {

        /** 旧实现：每个分片独立开/seek/写/关一次 RandomAccessFile */
        RAF_PER_CHUNK("每分片 RandomAccessFile 开关") {
            @Override
            boolean needsSharedChannel() {
                return false;
            }

            @Override
            void write(Path file, FileChannel shared, byte[] chunk, int index) throws IOException {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                    raf.seek((long) index * chunk.length);
                    raf.write(chunk);
                }
            }
        },

        /** 当前实现：整个文件只开一个 FileChannel，各分片带 position 定位写 */
        SHARED_CHANNEL_POSITIONAL("共享 FileChannel 定位写") {
            @Override
            boolean needsSharedChannel() {
                return true;
            }

            @Override
            void write(Path file, FileChannel shared, byte[] chunk, int index) throws IOException {
                writeFully(shared, ByteBuffer.wrap(chunk), (long) index * chunk.length);
            }
        },

        /** 备选：每个分片独立开一个 FileChannel 后定位写，避免共享通道锁 */
        CHANNEL_PER_CHUNK("每分片 FileChannel 开关") {
            @Override
            boolean needsSharedChannel() {
                return false;
            }

            @Override
            void write(Path file, FileChannel shared, byte[] chunk, int index) throws IOException {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    writeFully(channel, ByteBuffer.wrap(chunk), (long) index * chunk.length);
                }
            }
        };

        /** 中文标签，用于报表输出 */
        private final String label;

        WriteStrategy(String label) {
            this.label = label;
        }

        /** 是否需要预先打开一个供全部分片共享的通道 */
        abstract boolean needsSharedChannel();

        /**
         * 写入一个分片。
         *
         * @param file 目标文件
         * @param shared 共享通道（needsSharedChannel 为 false 时为 null）
         * @param chunk 分片内容
         * @param index 分片序号
         * @throws IOException 写入失败
         */
        abstract void write(Path file, FileChannel shared, byte[] chunk, int index) throws IOException;
    }

    /**
     * 启服务端、跑一次推送、返回结果。
     *
     * @param source 源目录
     * @param target 目标目录
     * @param chunkSize 分片大小
     * @param parallelism 客户端并发（0 走默认）
     * @param incremental 是否增量
     * @param cleanup 是否清理
     * @return 推送结果
     * @throws IOException 启动或推送失败
     */
    private static PushResult push(Path source, Path target, int chunkSize, int parallelism,
                                   boolean incremental, boolean cleanup) throws IOException {
        FilePushConfig serverConfig = FilePushConfig.defaults();
        serverConfig.setHost("127.0.0.1");
        serverConfig.setPort(0);
        serverConfig.setTargetDir(target);
        serverConfig.setChunkSize(chunkSize);
        serverConfig.setCleanup(cleanup);

        try (FilePushServer server = new FilePushServer(serverConfig)) {
            int port = server.start();
            FilePushConfig clientConfig = FilePushConfig.defaults();
            clientConfig.setHost("127.0.0.1");
            clientConfig.setPort(port);
            clientConfig.setSourceDir(source);
            clientConfig.setChunkSize(chunkSize);
            clientConfig.setClientFileParallelism(parallelism);
            clientConfig.setIncremental(incremental);
            clientConfig.setCleanup(cleanup);
            try (FilePushClient client = new FilePushClient(clientConfig)) {
                return client.push();
            }
        }
    }

    /** 校验目标目录累计字节数 */
    private static boolean assertTargetBytes(Path target, long expected, String label)
            throws IOException {
        long actual = 0;
        if (Files.isDirectory(target)) {
            try (var walk = Files.walk(target)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    actual += Files.size(file);
                }
            }
        }
        return check(label + " 目标字节数", actual == expected,
                "actual=" + actual + " expected=" + expected);
    }

    /** 抽样校验服务端文件 mtime 是否与源一致（增量同步的前提） */
    private static boolean checkMtimePreserved(Path source, Path target, int sample)
            throws IOException {
        List<Path> files;
        try (var walk = Files.walk(source)) {
            files = walk.filter(Files::isRegularFile).limit(sample).toList();
        }
        boolean ok = true;
        int mismatch = 0;
        for (Path file : files) {
            Path mirrored = target.resolve(source.relativize(file).toString());
            if (!Files.exists(mirrored)) {
                mismatch++;
                continue;
            }
            long srcMtime = Files.getLastModifiedTime(file).toMillis();
            long dstMtime = Files.getLastModifiedTime(mirrored).toMillis();
            if (srcMtime != dstMtime) {
                mismatch++;
            }
        }
        ok &= check("mtime 已还原（抽样 " + files.size() + " 个）", mismatch == 0,
                "不一致=" + mismatch);
        return ok;
    }

    /**
     * 打印并记录一行基准结果
     * @param label 标签，不允许为 null
     * @param result 结果，不允许为 null
     * @param passed passed（布尔开关）
     */
    private static void record(String label, PushResult result, boolean passed) {
        allPassed &= passed;
        System.out.printf("%n[%s] %s%n  成功=%d 失败=%d 跳过=%d 耗时=%dms 吞吐=%.2f MB/s%n",
                label, passed ? "PASS" : "FAIL",
                result.successCount(), result.failedCount(), result.skippedCount(),
                result.elapsedMs(), result.throughputMbs());
        SUMMARY.add(String.format("%-38s %6dms | %8.2f MB/s | 成功=%-5d 跳过=%-5d 失败=%d %s",
                label, result.elapsedMs(), result.throughputMbs(),
                result.successCount(), result.skippedCount(), result.failedCount(),
                passed ? "" : "  <<< FAIL"));
    }

    /**
     * 断言并打印
     * @param label 标签，不允许为 null
     * @param condition condition（布尔开关）
     * @param detail 方法入参 detail
     * @return 是否成功（true 表示成功）
     */
    private static boolean check(String label, boolean condition, String detail) {
        if (!condition) {
            allPassed = false;
        }
        System.out.println("  [" + (condition ? "PASS" : "FAIL") + "] " + label + " | " + detail);
        return condition;
    }

    /**
     * 安全取文件大小
     * @param file 文件，不允许为 null
     * @return 结果字符串
     */
    private static String safeSize(Path file) {
        try {
            return Files.exists(file) ? String.valueOf(Files.size(file)) : "不存在";
        } catch (IOException e) {
            return "读取失败";
        }
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
     * 递归删除目录或文件
     * @param root 根节点，不允许为 null
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
