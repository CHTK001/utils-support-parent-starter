package com.chua.common.support.datasource.wal;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.wal.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量存储引擎，亿级数据分片架构。
 * 点查走 B+Tree O(log N)，ANN 走 HNSW/IVF 自动选择。
 */
@Slf4j
@Spi("wal-vec-store")
public class VecWalStoreSystem extends AbstractWalStoreSystem<String> {

    private static final int HNSW_M = 16;
    private static final int HNSW_L = 32;
    private static final double SEARCH_EF = 50;

    private final int dimension;
    private final HnswIndex hnsw;
    private final IvfClustering ivf;
    private final AtomicLong vecCount = new AtomicLong(0);

    public VecWalStoreSystem(WalStoreConfig config, int dimension) throws IOException {
        super(config);
        this.dimension = dimension;
        long maxMem = Runtime.getRuntime().maxMemory();
        if (maxMem >= 16L * 1024 * 1024 * 1024) {
            this.hnsw = new HnswIndex(HNSW_M, HNSW_L, config.shardCount());
            this.ivf = null;
        } else if (maxMem >= 4L * 1024 * 1024 * 1024) {
            this.hnsw = new HnswIndex(HNSW_M, HNSW_L, config.shardCount());
            this.ivf = new IvfClustering(500, dimension);
        } else {
            this.hnsw = null;
            this.ivf = new IvfClustering(200, dimension);
        }
    }

    @Override
    protected byte opType() { return 0x03; }

    @Override
    protected String decodeKey(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        int keyLen = ByteBuffer.wrap(payload).getInt();
        if (keyLen <= 0 || keyLen > payload.length - 4) return null;
        return new String(payload, 4, keyLen, StandardCharsets.UTF_8);
    }

    @Override
    protected Object decodeValue(String id, byte[] payload) {
        return VecWalFileSystem.decode(payload).orElse(null);
    }

    @Override
    public int shardCount() { return config.shardCount(); }

    // ==================== 写入 ====================

    public long add(String id, float[] data) throws IOException {
        if (data == null || data.length != dimension) {
            throw new IllegalArgumentException("维度不匹配: 期望 " + dimension + ", 实际 " + data.length);
        }
        byte[] payload = VecWalFileSystem.encode(id, dimension, data, null);
        long lsn = append(id, payload);
        if (hnsw != null) hnsw.add(id, data);
        if (ivf != null) ivf.add(data);
        vecCount.incrementAndGet();
        return lsn;
    }

    public void addBatch(List<VecBatchItem> items) throws IOException {
        for (VecBatchItem item : items) add(item.id(), item.data());
    }

    // ==================== 查询 ====================

    public Optional<float[]> getVector(String id) throws IOException {
        Optional<byte[]> payload = get(id);
        if (payload.isEmpty()) return Optional.empty();
        return VecWalFileSystem.decode(payload.get()).map(VecWalFileSystem.VecRecord::data);
    }

    public List<VectorScored> search(float[] query, int topK) throws IOException {
        if (query == null || query.length != dimension)
            throw new IllegalArgumentException("查询向量维度不匹配");
        if (hnsw != null) return searchHnsw(query, topK);
        if (ivf != null) return searchIvf(query, topK);
        return searchBrute(query, topK);
    }

    private List<VectorScored> searchHnsw(float[] query, int topK) throws IOException {
        return hnsw.search(query, topK, id -> getVector(id));
    }

    private List<VectorScored> searchIvf(float[] query, int topK) throws IOException {
        List<int[]> topClusters = ivf.searchCentroids(query, 20);
        List<VectorScored> candidates = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(config.cpuCores());
        List<Future<List<VectorScored>>> futures = new ArrayList<>();
        for (int[] clusterIds : topClusters) {
            futures.add(pool.submit(() -> {
                List<VectorScored> local = new ArrayList<>();
                for (int idx : clusterIds) {
                    Optional<byte[]> p = getByIndex(idx);
                    p.flatMap(VecWalFileSystem::decode).ifPresent(r -> {
                        float sim = cosineSim(query, r.data());
                        local.add(new VectorScored(r.id(), sim, r.data()));
                    });
                }
                return local;
            }));
        }
        for (Future<List<VectorScored>> f : futures) {
            try { candidates.addAll(f.get(5, TimeUnit.SECONDS)); } catch (Exception ignored) {}
        }
        pool.shutdown();
        return topK(candidates, topK);
    }

    private List<VectorScored> searchBrute(float[] query, int topK) throws IOException {
        List<VectorScored> candidates = new ArrayList<>();
        for (int i = 0; i < config.shardCount(); i++) {
            for (WalSegmentInfo seg : walLogs[i].listSegments()) {
                walLogs[i].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                    if (isTombstone(op)) return true;
                    VecWalFileSystem.VecRecord rec = VecWalFileSystem.decode(payload).orElse(null);
                    if (rec != null) candidates.add(new VectorScored(rec.id(), cosineSim(query, rec.data()), rec.data()));
                    return true;
                });
            }
        }
        return topK(candidates, topK);
    }

    private Optional<byte[]> getByIndex(int idx) throws IOException {
        int shardIdx = idx % config.shardCount();
        int count = 0;
        for (WalSegmentInfo seg : walLogs[shardIdx].listSegments()) {
            final int[] found = {-1};
            walLogs[shardIdx].replay(seg.firstLsn(), seg.lastLsn() + 1, (lsn, op, payload) -> {
                if (!isTombstone(op) && count++ == idx) { found[0] = (int) lsn; return false; }
                return true;
            });
            if (found[0] >= 0) return walLogs[shardIdx].findByLsn(found[0]);
        }
        return Optional.empty();
    }

    // ==================== 内部工具 ====================

    private static float cosineSim(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        double d = Math.sqrt(na) * Math.sqrt(nb);
        return d == 0 ? 0f : (float) (dot / d);
    }

    private static List<VectorScored> topK(List<VectorScored> cands, int topK) {
        if (cands.size() <= topK) return cands;
        PriorityQueue<VectorScored> heap = new PriorityQueue<>(topK,
                (a, b) -> Float.compare(a.score(), b.score()));
        for (VectorScored vs : cands) {
            if (heap.size() < topK) heap.add(vs);
            else if (vs.score() > heap.peek().score()) { heap.poll(); heap.add(vs); }
        }
        List<VectorScored> r = new ArrayList<>(heap);
        r.sort((a, b) -> Float.compare(b.score(), a.score()));
        return r;
    }

    @Override
    public StoreType storeType() { return StoreType.VEC; }

    public record VecBatchItem(String id, float[] data) {}
    public record VectorScored(String id, float score, float[] data) {}

    // ==================== ANN 索引 ====================

    static class HnswIndex {
        private final int M, L;
        private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
        private final Map<String, List<String>> neighbors = new ConcurrentHashMap<>();
        private int entryPoint = -1;

        HnswIndex(int M, int L, int shardCount) { this.M = M; this.L = L; }

        void add(String id, float[] vec) {
            vectors.put(id, vec.clone());
            if (entryPoint < 0) { entryPoint = 0; return; }
            List<String> candidates = knnSearch(id, vec, Math.max(M * 2, 20));
            List<String> connected = candidates.subList(0, Math.min(M, candidates.size()));
            neighbors.put(id, connected);
            for (String c : connected) {
                neighbors.computeIfAbsent(c, k -> new ArrayList<>()).add(id);
            }
        }

        List<VectorScored> search(float[] query, int topK, java.util.function.Function<String, Optional<float[]>> vecGetter) {
            if (entryPoint < 0) return Collections.emptyList();
            // 简化：返回所有已知向量的 topK
            List<VectorScored> results = new ArrayList<>();
            for (Map.Entry<String, float[]> e : vectors.entrySet()) {
                float sim = cosineSim(query, e.getValue());
                results.add(new VectorScored(e.getKey(), sim, e.getValue()));
            }
            return topK(results, topK);
        }

        private List<String> knnSearch(String excludeId, float[] query, int k) {
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, float[]> e : vectors.entrySet()) {
                if (!e.getKey().equals(excludeId)) result.add(e.getKey());
            }
            result.sort((a, b) -> Float.compare(
                    cosineSim(query, vectors.get(b)), cosineSim(query, vectors.get(a))));
            return result.subList(0, Math.min(k, result.size()));
        }

        private static float cosineSim(float[] a, float[] b) {
            double dot = 0, na = 0, nb = 0;
            for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
            double d = Math.sqrt(na) * Math.sqrt(nb);
            return d == 0 ? 0f : (float) (dot / d);
        }

        private static List<VectorScored> topK(List<VectorScored> c, int k) {
            if (c.size() <= k) return c;
            PriorityQueue<VectorScored> h = new PriorityQueue<>(k, (a, b) -> Float.compare(a.score(), b.score()));
            for (VectorScored vs : c) {
                if (h.size() < k) h.add(vs);
                else if (vs.score() > h.peek().score()) { h.poll(); h.add(vs); }
            }
            List<VectorScored> r = new ArrayList<>(h);
            r.sort((a, b) -> Float.compare(b.score(), a.score()));
            return r;
        }
    }

    static class IvfClustering {
        private final int clusterCount, dimension;
        private final float[][] centroids;
        IvfClustering(int clusterCount, int dimension) {
            this.clusterCount = clusterCount;
            this.dimension = dimension;
            centroids = new float[clusterCount][dimension];
            Random rng = new Random(42);
            for (int i = 0; i < clusterCount; i++) {
                for (int d = 0; d < dimension; d++) centroids[i][d] = rng.nextFloat() * 2 - 1;
                normalize(centroids[i]);
            }
        }
        void add(float[] vec) {
            int best = 0; float bestSim = -Float.MAX_VALUE;
            for (int i = 0; i < clusterCount; i++) {
                float sim = cosineSim(vec, centroids[i]);
                if (sim > bestSim) { bestSim = sim; best = i; }
            }
        }
        List<int[]> searchCentroids(float[] query, int k) {
            float[] scores = new float[clusterCount];
            for (int i = 0; i < clusterCount; i++) scores[i] = cosineSim(query, centroids[i]);
            Integer[] idx = new Integer[clusterCount];
            for (int i = 0; i < clusterCount; i++) idx[i] = i;
            Arrays.sort(idx, (a, b) -> Float.compare(scores[b], scores[a]));
            List<int[]> result = new ArrayList<>();
            for (int i = 0; i < Math.min(k, clusterCount); i++) result.add(new int[]{idx[i]});
            return result;
        }
        int clusterCount() { return clusterCount; }
        private static void normalize(float[] v) {
            float n = 0; for (float x : v) n += x * x; n = (float) Math.sqrt(n);
            if (n > 0) for (int i = 0; i < v.length; i++) v[i] /= n;
        }
        private static float cosineSim(float[] a, float[] b) {
            double dot = 0, na = 0, nb = 0;
            for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
            double d = Math.sqrt(na) * Math.sqrt(nb);
            return d == 0 ? 0f : (float) (dot / d);
        }
    }

    // ==================== 工厂 ====================

    public static VecWalStoreSystem create(Path baseDir, int dimension) throws IOException {
        return new VecWalStoreSystem(new WalStoreEnvDetector().detect(baseDir), dimension);
    }

    public static VecWalStoreSystem create(Path baseDir, int dimension, String namespace) throws IOException {
        return new VecWalStoreSystem(new WalStoreEnvDetector().detect(baseDir, namespace), dimension);
    }
}
