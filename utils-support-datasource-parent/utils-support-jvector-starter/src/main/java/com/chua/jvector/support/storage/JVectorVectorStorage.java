package com.chua.jvector.support.storage;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import io.github.jbellis.jvector.disk.SimpleMappedReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.OnHeapGraphIndex;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.quantization.PQVectors;
import io.github.jbellis.jvector.quantization.ProductQuantization;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVector 向量存储门面，根据 {@link JVectorStorageProperties} 的 mode 选择底层策略。
 *
 * <p>基于 jvector 4.0.0-rc.9，支持三种存储模式：</p>
 * <ul>
 *   <li>MEMORY: 纯内存图，适合小规模数据集</li>
 *   <li>ON_DISK: 磁盘持久化图，支持大数据集</li>
 *   <li>LARGER_THAN_MEMORY: PQ 压缩向量 + 磁盘存储，支持超大规模数据集</li>
 * </ul>
 *
 * @author CH
 */
public class JVectorVectorStorage extends AbstractVectorStorage {

    private static final VectorTypeSupport VTS =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    private final JVectorStorageProperties properties;
    private final VectorSimilarityFunction similarity;
    private volatile StorageStrategy delegate;

    public JVectorVectorStorage(int dimension, VectorCompareAlgorithm algorithm) {
        this(dimension, algorithm, null);
    }

    public JVectorVectorStorage(int dimension,
                                VectorCompareAlgorithm algorithm,
                                JVectorStorageProperties properties) {
        super(dimension, algorithm);
        this.properties = properties != null ? properties : new JVectorStorageProperties();
        this.similarity = toJVectorSim(algorithm);
        this.delegate = createStrategy();
    }

    private StorageStrategy createStrategy() {
        return switch (properties.getMode()) {
            case MEMORY -> new EagerMemoryStrategy(dimension(), getAlgorithm(), similarity);
            case ON_DISK -> new DiskStrategy(dimension(), getAlgorithm(), similarity, properties);
            case LARGER_THAN_MEMORY -> new LargerThanMemoryStrategy(dimension(), getAlgorithm(), similarity, properties);
        };
    }

    @Override
    protected synchronized boolean doAdd(String id, float[] vector) {
        return delegate.doAdd(id, vector);
    }

    @Override
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        return delegate.doSearch(query, topK);
    }

    @Override
    public synchronized int size() {
        return delegate.size();
    }

    @Override
    public synchronized void clear() {
        delegate.clear();
    }

    @Override
    public synchronized void close() {
        delegate.close();
    }

    private static VectorSimilarityFunction toJVectorSim(VectorCompareAlgorithm algo) {
        if (algo == null) {
            return VectorSimilarityFunction.EUCLIDEAN;
        }
        return switch (algo.name().toUpperCase()) {
            case "COSINE" -> VectorSimilarityFunction.COSINE;
            case "DOT", "DOT_PRODUCT" -> VectorSimilarityFunction.DOT_PRODUCT;
            default -> VectorSimilarityFunction.EUCLIDEAN;
        };
    }

    private interface StorageStrategy {
        boolean doAdd(String id, float[] vector);
        List<Vector> doSearch(float[] query, int topK);
        int size();
        void clear();
        void close();
    }

    private static class EagerMemoryStrategy implements StorageStrategy {
        private final int dimension;
        private final VectorCompareAlgorithm algorithm;
        private final VectorSimilarityFunction similarity;
        private volatile ImmutableGraphIndex graph;
        private final List<float[]> rawVectors = new ArrayList<>();
        private final List<VectorFloat<?>> vectors = new ArrayList<>();
        private final Map<String, Integer> idToOrd = new ConcurrentHashMap<>();
        private final Map<Integer, String> ordToId = new ConcurrentHashMap<>();
        private final AtomicInteger nextOrd = new AtomicInteger(0);

        EagerMemoryStrategy(int dimension, VectorCompareAlgorithm algorithm,
                            VectorSimilarityFunction similarity) {
            this.dimension = dimension;
            this.algorithm = algorithm;
            this.similarity = similarity;
        }

        @Override
        public synchronized boolean doAdd(String id, float[] vector) {
            int ord = nextOrd.getAndIncrement();
            idToOrd.put(id, ord);
            ordToId.put(ord, id);
            rawVectors.add(vector);
            vectors.add(VTS.createFloatVector(vector));
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
            return true;
        }

        @Override
        public synchronized List<Vector> doSearch(float[] query, int topK) {
            if (vectors.isEmpty()) return List.of();
            if (graph == null) buildGraph();
            CustomScore score = new CustomScore(query);
            SearchScoreProvider ssp = new DefaultSearchScoreProvider(score, score);
            try (var searcher = new GraphSearcher(graph)) {
                var result = searcher.search(ssp, topK, Bits.ALL);
                var list = new ArrayList<Vector>();
                for (var n : result.getNodes()) {
                    var id = ordToId.get(n.node);
                    if (id == null) continue;
                    float[] vd = n.node < rawVectors.size() ? rawVectors.get(n.node) : new float[0];
                    list.add(new Vector(id, vd, Map.of("score", (double) n.score)));
                }
                return list;
            } catch (Exception e) {
                throw new RuntimeException("搜索失败", e);
            }
        }

        @Override
        public synchronized int size() { return vectors.size(); }

        @Override
        public synchronized void clear() {
            vectors.clear(); rawVectors.clear();
            idToOrd.clear(); ordToId.clear();
            nextOrd.set(0);
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
        }

        @Override
        public synchronized void close() {
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
        }

        private void buildGraph() {
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            try (var builder = new GraphIndexBuilder(rav, similarity, 16, 200, 1.0f, 1.2f, false)) {
                for (int i = 0; i < vectors.size(); i++) builder.addGraphNode(i, vectors.get(i));
                graph = builder.build(rav);
            } catch (Exception ignored) {}
        }

        private class CustomScore implements ScoreFunction, ScoreFunction.ExactScoreFunction {
            private final float[] query;
            CustomScore(float[] query) { this.query = query; }
            @Override public boolean isExact() { return true; }
            @Override
            public float similarityTo(int nodeOrd) {
                if (algorithm != null && nodeOrd < rawVectors.size()) {
                    return -(float) algorithm.compare(query, rawVectors.get(nodeOrd));
                }
                return Float.NEGATIVE_INFINITY;
            }
        }
    }



    /**
     * DiskStrategy: ON_DISK 模式，将内存构建的图持久化到磁盘，支持加载回来搜索。
     *
     * @author CH
     */
    private static class DiskStrategy implements StorageStrategy {
        private final int dimension;
        private final VectorCompareAlgorithm algorithm;
        private final VectorSimilarityFunction similarity;
        private final JVectorStorageProperties properties;
        private final Path indexPath;

        private volatile OnDiskGraphIndex diskGraph;
        private final List<float[]> rawVectors = new ArrayList<>();
        private final List<VectorFloat<?>> vectors = new ArrayList<>();
        private final Map<String, Integer> idToOrd = new ConcurrentHashMap<>();
        private final Map<Integer, String> ordToId = new ConcurrentHashMap<>();
        private final AtomicInteger nextOrd = new AtomicInteger(0);

        DiskStrategy(int dimension, VectorCompareAlgorithm algorithm,
                     VectorSimilarityFunction similarity, JVectorStorageProperties properties) {
            this.dimension = dimension;
            this.algorithm = algorithm;
            this.similarity = similarity;
            this.properties = properties;
            this.indexPath = Paths.get(properties.getIndexPath());
            tryLoadExistingIndex();
        }

        private void tryLoadExistingIndex() {
            if (!Files.exists(indexPath)) return;
            try {
                diskGraph = OnDiskGraphIndex.load(new SimpleMappedReader.Supplier(indexPath));
            } catch (Exception e) {
                // 加载失败，稍后重新构建
            }
        }

        @Override
        public synchronized boolean doAdd(String id, float[] vector) {
            if (idToOrd.containsKey(id)) return false;
            var ord = nextOrd.getAndIncrement();
            idToOrd.put(id, ord);
            ordToId.put(ord, id);
            rawVectors.add(vector);
            vectors.add(VTS.createFloatVector(vector));
            // 添加后需要重新构建图
            diskGraph = null;
            return true;
        }

        @Override
        public synchronized List<Vector> doSearch(float[] query, int topK) {
            if (vectors.isEmpty()) return List.of();
            ensureGraphBuilt();
            if (diskGraph == null) return List.of();

            CustomScore score = new CustomScore(query);
            SearchScoreProvider ssp = new DefaultSearchScoreProvider(score, score);

            try (var searcher = new GraphSearcher(diskGraph)) {
                int ef = Math.max(topK, (int) (topK * properties.getSearchOverquery()));
                var result = searcher.search(ssp, ef, Bits.ALL);
                var list = new ArrayList<Vector>();
                for (var n : result.getNodes()) {
                    var id = ordToId.get(n.node);
                    if (id == null) continue;
                    float[] vd = n.node < rawVectors.size() ? rawVectors.get(n.node) : new float[0];
                    list.add(new Vector(id, vd, Map.of("score", (double) n.score)));
                    if (list.size() >= topK) break;
                }
                return list;
            } catch (Exception e) {
                throw new RuntimeException("磁盘图搜索失败", e);
            }
        }

        @Override
        public synchronized int size() { return vectors.size(); }

        @Override
        public synchronized void clear() {
            vectors.clear(); rawVectors.clear();
            idToOrd.clear(); ordToId.clear();
            nextOrd.set(0);
            if (diskGraph != null) {
                try { diskGraph.close(); } catch (Exception ignored) {}
                diskGraph = null;
            }
            try { Files.deleteIfExists(indexPath); } catch (IOException ignored) {}
        }

        @Override
        public synchronized void close() {
            if (diskGraph != null) {
                try { diskGraph.close(); } catch (Exception ignored) {}
                diskGraph = null;
            }
        }

        private void ensureGraphBuilt() {
            if (diskGraph != null) return;
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            try (var builder = new GraphIndexBuilder(
                    rav, similarity,
                    properties.getGraphM(),
                    properties.getGraphEfConstruction(),
                    1.0f, 1.2f, false)) {
                for (int i = 0; i < vectors.size(); i++) {
                    builder.addGraphNode(i, vectors.get(i));
                }
                var memGraph = builder.build(rav);
                // 持久化到磁盘
                Files.createDirectories(indexPath.getParent());
                OnDiskGraphIndex.write(memGraph, rav, indexPath);
                memGraph.close();
                // 加载回来
                diskGraph = OnDiskGraphIndex.load(new SimpleMappedReader.Supplier(indexPath));
            } catch (Exception e) {
                throw new RuntimeException("构建磁盘图失败", e);
            }
        }

        private class CustomScore implements ScoreFunction, ScoreFunction.ExactScoreFunction {
            private final float[] query;
            CustomScore(float[] query) { this.query = query; }
            @Override public boolean isExact() { return true; }
            @Override
            public float similarityTo(int nodeOrd) {
                if (algorithm != null && nodeOrd < rawVectors.size()) {
                    return -(float) algorithm.compare(query, rawVectors.get(nodeOrd));
                }
                return Float.NEGATIVE_INFINITY;
            }
        }
    }

    /**
     * LargerThanMemoryStrategy: LARGER_THAN_MEMORY 模式，使用 PQ 压缩向量构建图，
     * 搜索时使用两阶段策略（粗排 + 精排）。
     *
     * @author CH
     */
    private static class LargerThanMemoryStrategy implements StorageStrategy {
        private final int dimension;
        private final VectorCompareAlgorithm algorithm;
        private final VectorSimilarityFunction similarity;
        private final JVectorStorageProperties properties;
        private final Path indexPath;

        private volatile ImmutableGraphIndex graph;
        private volatile PQVectors pqVectors;
        private final List<float[]> rawVectors = new ArrayList<>();
        private final List<VectorFloat<?>> vectors = new ArrayList<>();
        private final Map<String, Integer> idToOrd = new ConcurrentHashMap<>();
        private final Map<Integer, String> ordToId = new ConcurrentHashMap<>();
        private final AtomicInteger nextOrd = new AtomicInteger(0);

        LargerThanMemoryStrategy(int dimension, VectorCompareAlgorithm algorithm,
                                 VectorSimilarityFunction similarity, JVectorStorageProperties properties) {
            this.dimension = dimension;
            this.algorithm = algorithm;
            this.similarity = similarity;
            this.properties = properties;
            this.indexPath = Paths.get(properties.getIndexPath() + ".pq");
        }

        @Override
        public synchronized boolean doAdd(String id, float[] vector) {
            if (idToOrd.containsKey(id)) return false;
            var ord = nextOrd.getAndIncrement();
            idToOrd.put(id, ord);
            ordToId.put(ord, id);
            rawVectors.add(vector);
            vectors.add(VTS.createFloatVector(vector));
            // 添加后需要重新构建
            graph = null;
            pqVectors = null;
            return true;
        }

        @Override
        public synchronized List<Vector> doSearch(float[] query, int topK) {
            if (vectors.isEmpty()) return List.of();
            ensureGraphBuilt();
            if (graph == null || pqVectors == null) return List.of();

            var queryVec = VTS.createFloatVector(query);
            // 第一阶段：使用 PQ 压缩向量粗排
            var pqScore = pqVectors.precomputedScoreFunctionFor(queryVec, similarity);
            SearchScoreProvider roughProvider = new DefaultSearchScoreProvider(pqScore);

            // 第二阶段：使用原始向量精排
            CustomScore exactScore = new CustomScore(query);

            try (var searcher = new GraphSearcher(graph)) {
                int ef = Math.max(topK, (int) (topK * properties.getSearchOverquery()));
                // 粗排获取候选集
                var roughResult = searcher.search(roughProvider, ef * 2, Bits.ALL);
                // 精排 rerank
                var list = new ArrayList<Vector>();
                for (var n : roughResult.getNodes()) {
                    var id = ordToId.get(n.node);
                    if (id == null) continue;
                    float exactSimilarity = exactScore.similarityTo(n.node);
                    float[] vd = n.node < rawVectors.size() ? rawVectors.get(n.node) : new float[0];
                    list.add(new Vector(id, vd, Map.of("score", (double) exactSimilarity)));
                }
                // 按精确分数排序
                list.sort((a, b) -> Double.compare(
                        (Double) b.metadata().get("score"),
                        (Double) a.metadata().get("score")));
                return list.subList(0, Math.min(topK, list.size()));
            } catch (Exception e) {
                throw new RuntimeException("PQ 图搜索失败", e);
            }
        }

        @Override
        public synchronized int size() { return vectors.size(); }

        @Override
        public synchronized void clear() {
            vectors.clear(); rawVectors.clear();
            idToOrd.clear(); ordToId.clear();
            nextOrd.set(0);
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
            pqVectors = null;
            try { Files.deleteIfExists(indexPath); } catch (IOException ignored) {}
        }

        @Override
        public synchronized void close() {
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
            pqVectors = null;
        }

        private void ensureGraphBuilt() {
            if (graph != null && pqVectors != null) return;
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            try {
                // 1. 训练 PQ 量化器
                var pq = ProductQuantization.compute(
                        rav,
                        properties.getPqSubspaces(),
                        properties.getPqCentroidsPerSubspace(),
                        false);
                CompressedVectors compressed = pq.encodeAll(rav, ForkJoinPool.commonPool());
                if (!(compressed instanceof PQVectors)) {
                    throw new RuntimeException("PQ 编码失败：返回类型不匹配");
                }
                pqVectors = (PQVectors) compressed;

                // 2. 使用 PQ 向量构建图
                BuildScoreProvider pqBuildProvider = BuildScoreProvider.pqBuildScoreProvider(similarity, pqVectors);
                try (var builder = new GraphIndexBuilder(
                        pqBuildProvider,
                        dimension,
                        properties.getGraphM(),
                        properties.getGraphEfConstruction(),
                        1.0f, 1.2f, false)) {
                    for (int i = 0; i < vectors.size(); i++) {
                        builder.addGraphNode(i, vectors.get(i));
                    }
                    graph = builder.build(rav);
                }
            } catch (Exception e) {
                throw new RuntimeException("构建 PQ 图失败", e);
            }
        }

        private class CustomScore implements ScoreFunction, ScoreFunction.ExactScoreFunction {
            private final float[] query;
            CustomScore(float[] query) { this.query = query; }
            @Override public boolean isExact() { return true; }
            @Override
            public float similarityTo(int nodeOrd) {
                if (algorithm != null && nodeOrd < rawVectors.size()) {
                    return -(float) algorithm.compare(query, rawVectors.get(nodeOrd));
                }
                return Float.NEGATIVE_INFINITY;
            }
        }
    }
}
