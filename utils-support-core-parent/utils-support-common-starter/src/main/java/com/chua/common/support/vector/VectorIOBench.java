package com.chua.common.support.vector;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 向量存储 I/O 性能对比测试。
 * 对比三种读取路径：
 * 1. 顺序 数据输入流 扫描（旧版 文件 模式）
 * 2. mappedbyte缓冲 顺序扫描（无索引，整文件 mmap）
 * 3. B+树 + mappedbyte缓冲 精确偏移读取（HYBRID 模式）
 * @author CH
 * @since 4.0.0
 * @param count 数量
 * @param flushBatch flushbatch
 * @param label 标签
*/
public class VectorIOBench {

    private static final int DIM = 128; // DIM
    /**
    * main。
    * @param args 参数
    * @param count 数量
    * @param flushBatch flushbatch
    * @param label 标签
    */
    private static final Random RND = ThreadLocalRandom.current();
    private static Path testDir; // 测试dir

    /**
    * main。
    * @param args 参数
    */
    public static void main(String[] args) throws Exception {
        testDir = Files.createTempDirectory("io-bench-");
        System.out.println("============================================");
        System.out.println("  向量存储 I/O 性能基准测试");
        System.out.println("  维度: " + DIM + " | JVM: " + System.getProperty("java.version"));
        System.out.println("============================================\n");

        runTest(10_000, 2_000, "小量  1万条");
        runTest(100_000, 10_000, "中量  10万条");
        runTest(1_000_000, 20_000, "大量  100万条");

        deleteRecursively(testDir);
        System.out.println("\n测试目录已清理: " + testDir);
    }

    /**
     * 运行测试。
     *
     * @param count 数量，不允许为 null
     * @param flushBatch 刷写批次，不允许为 null
     * @param label 标签，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    private static void runTest(int count, int flushBatch, String label) throws Exception {
        System.out.println("────────────────────────────────────────────");
        System.out.println("【" + label + "】");

        Path dataDir = testDir.resolve(label.replaceAll("[\\s]", ""));

        // ---- 生成数据 ----
        DefaultVectorStorage gen = DefaultVectorStorage.builder()
                .dimension(DIM).dir(dataDir)
                .mode(DefaultVectorStorage.Mode.HYBRID).shardSize(flushBatch).build();
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            gen.add("id_" + i, randomVec());
            if ((i + 1) % flushBatch == 0) {
                gen.flush();
            }
        }
        gen.flush();
        gen.close();
        long writeMs = (System.nanoTime() - t0) / 1_000_000L;
        long diskBytes = totalDiskSize(dataDir);
        int shardCount = shardCount(dataDir);
        System.out.printf("  写入: %d ms | 磁盘: %.1f MB | 分片: %d%n",
                writeMs, diskBytes / 1_048_576.0, shardCount);

 // ---- 测试 A: 旧版顺序 数据输入流 ----
        long seqNs = measureSequential(dataDir, count, diskBytes);
        System.out.printf("  [A] 顺序 DataInputStream: %d us  |  %.0f QPS  |  %.0f MB/s%n",
                seqNs / 1000, 1_000_000.0 / seqNs,
                diskBytes / 1_048_576.0 / (seqNs / 1_000_000.0));

 // ---- 测试 B: mappedbyte缓冲 顺序扫描 ----
        long mmapNs = measureMmapSequential(dataDir, count, diskBytes);
        System.out.printf("  [B] MappedByteBuffer 顺序: %d us  |  %.0f QPS  |  %.0f MB/s%n",
                mmapNs / 1000, 1_000_000.0 / mmapNs,
                diskBytes / 1_048_576.0 / (mmapNs / 1_000_000.0));

        // ---- 测试 C: HYBRID (B+Tree + MappedByteBuffer 精确偏移) ----
        long hybridNs = measureHybrid(dataDir, count, diskBytes);
        System.out.printf("  [C] B+Tree+mmap 精确偏移: %d us  |  %.0f QPS  |  %.0f MB/s%n",
                hybridNs / 1000, 1_000_000.0 / hybridNs,
                diskBytes / 1_048_576.0 / (hybridNs / 1_000_000.0));

        // ---- 对比 ----
        System.out.printf("  加速比: B/A=%.1fx  C/A=%.1fx  C/B=%.1fx%n%n",
                (double) seqNs / mmapNs,
                (double) seqNs / hybridNs,
                (double) mmapNs / hybridNs);
    }

    // ==================== 三种搜索方式 ====================

    /**
    * A: 旧版顺序扫描 - 每个分片开 数据输入流 逐条读
    *
    * @param dir dir
    * @param expectedCount 期望数量
    * @param diskBytes diskbytes
    * @return 测量sequential的结果
    */
    private static long measureSequential(Path dir, int expectedCount, long diskBytes) throws Exception {
        File[] files = dir.toFile().listFiles((d, n) -> n.matches("shard_\\d{4}\\.bin"));
        if (files == null || files.length == 0) {
            return Long.MAX_VALUE;
        }
        Arrays.sort(files);
        float[] query = randomVec();
        int rounds = Math.max(3, 100_000 / Math.min(expectedCount, 10_000));
        long total = 0;
        for (int r = 0; r < rounds; r++) {
            long t = System.nanoTime();
            for (File f : files) {
                try (DataInputStream in = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(f.toPath())))) {
                    int count = in.readInt();
                    int dim = in.readInt();
                    for (int i = 0; i < count; i++) {
                        int idLen = in.readInt();
                        byte[] idBytes = new byte[idLen];
                        in.readFully(idBytes);
                        float[] v = new float[dim];
                        for (int j = 0; j < dim; j++) {
                            v[j] = in.readFloat();
                        }
                        in.readUTF();
                        cosineSIMD(query, v);
                    }
                }
            }
            total += System.nanoTime() - t;
        }
        return total / rounds;
    }

    /**
    * B: mappedbyte缓冲 顺序扫描 - 整文件 mmap 后顺序读
    *
    * @param dir dir
    * @param expectedCount 期望数量
    * @param diskBytes diskbytes
    * @return 测量mmapsequential的结果
    */
    private static long measureMmapSequential(Path dir, int expectedCount, long diskBytes) throws Exception {
        File[] files = dir.toFile().listFiles((d, n) -> n.matches("shard_\\d{4}\\.bin"));
        if (files == null || files.length == 0) {
            return Long.MAX_VALUE;
        }
        Arrays.sort(files);
        float[] query = randomVec();
        int rounds = Math.max(3, 100_000 / Math.min(expectedCount, 10_000));
        long total = 0;
        for (int r = 0; r < rounds; r++) {
            long t = System.nanoTime();
            for (File f : files) {
                try (FileChannel fc = FileChannel.open(f.toPath(), StandardOpenOption.READ)) {
                    MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
                    int count = buf.getInt();
                    int dim = buf.getInt();
                    byte[] idBuf = new byte[256];
                    float[] vecBuf = new float[dim];
                    for (int i = 0; i < count; i++) {
                        int idLen = buf.getInt();
                        if (idLen > idBuf.length) {
                            idBuf = new byte[idLen];
                        }
                        buf.get(idBuf, 0, idLen);
                        for (int k = 0; k < dim; k++) {
                            vecBuf[k] = buf.getFloat();
                        }
                        int metaLen = buf.getShort();
                        buf.position(buf.position() + metaLen);
                        cosineSIMD(query, vecBuf);
                    }
                }
            }
            total += System.nanoTime() - t;
        }
        return total / rounds;
    }

    /**
    * C: HYBRID - B+树 索引 + mappedbyte缓冲 精确偏移读取
    *
    * @param dir dir
    * @param expectedCount 期望数量
    * @param diskBytes diskbytes
    * @return 测量hybrid的结果
    */
    private static long measureHybrid(Path dir, int expectedCount, long diskBytes) throws Exception {
        DefaultVectorStorage storage = DefaultVectorStorage.builder()
                .dimension(DIM).dir(dir)
                .mode(DefaultVectorStorage.Mode.HYBRID).shardSize(10_000).build();
        System.out.printf("    加载: %d 条, %d 分片%n", storage.size(), storage.getShardCount());
        float[] query = randomVec();
        int rounds = Math.max(3, 100_000 / Math.min(expectedCount, 10_000));
        long total = 0;
        for (int r = 0; r < rounds; r++) {
            long t = System.nanoTime();
            storage.search(query, 10);
            total += System.nanoTime() - t;
        }
        long result = total / rounds;
        storage.close();
        return result;
    }

    // ==================== 工具方法 ====================

    /**
    * 随机vec。
    * @return 随机vec的结果
    */
    private static float[] randomVec() {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = RND.nextFloat() * 2 - 1;
        }
        float n = 0;
        for (float f : v) {
            n += f * f;
        }
        n = (float) Math.sqrt(n);
        if (n > 0) {
            for (int i = 0; i < DIM; i++) {
                v[i] /= n;
            }
        }
        return v;
    }

    /**
    * cosinesimd。
    * @param a a
    * @param b b
    * @return cosineSIMD的结果
    */
    private static float cosineSIMD(float[] a, float[] b) {
        double dot = 0, nA = 0, nB = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; nA += a[i] * a[i]; nB += b[i] * b[i]; }
        double d = Math.sqrt(nA) * Math.sqrt(nB);
        return d == 0 ? 0f : (float) (dot / d);
    }

    /**
    * totaldisk大小。
    * @param dir dir
    * @return totaldisk大小的结果
    */
    private static long totalDiskSize(Path dir) {
        try {
            return Files.list(dir).filter(p -> p.toString().endsWith(".bin"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (Exception e) {
                            return 0;
                        }
                    }).sum();
        } catch (Exception e) { return 0; }
    }

    /**
    * shard数量。
    * @param dir dir
    * @return shard数量的结果
    */
    private static int shardCount(Path dir) {
        File[] fs = dir.toFile().listFiles((d, n) -> n.matches("shard_\\d{4}\\.bin"));
        return fs == null ? 0 : fs.length;
    }

    /**
    * 删除recursively。
    * @param dir dir
    */
    private static void deleteRecursively(Path dir) {
        try { Files.walk(dir).sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                    }
                });
        } catch (Exception ignored) {}
    }
}
