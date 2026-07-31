package com.chua.jvector.support.storage;

import com.chua.common.support.vector.AbstractIdOrdinalStorage;
import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import io.github.jbellis.jvector.disk.SimpleMappedReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
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
import java.util.concurrent.ForkJoinPool;

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
 * <p>注意：jvector 图构建与搜索必须使用同一度量，因此这里只依据算法{@code name()}映射到
 * jvector 的 {@link VectorSimilarityFunction}（COSINE → COSINE、DOT → DOT_PRODUCT、其余 → EUCLIDEAN），
 * 自定义 {@link VectorCompareAlgorithm#compare(float[], float[])} 实现不参与打分。</p>
 *
 * <p>三种策略均继承 {@link AbstractIdOrdinalStorage}，统一复用 id→序数去重守卫、双向映射与
 * swap-remove 逻辑，避免各策略重复实现 {@code idToOrd.containsKey} 守卫。</p>
 *
 * @author CH
 */
public class JVectorVectorStorage extends AbstractVectorStorage {

    private static final VectorTypeSupport VTS =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    private final JVectorStorageProperties properties;
    private final VectorSimilarityFunction similarity;
    private StorageStrategy delegate;

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
            case MEMORY -> new EagerMemoryStrategy(dimension(), similarity);
            case ON_DISK -> new DiskStrategy(dimension(), similarity, properties);
            case LARGER_THAN_MEMORY -> new LargerThanMemoryStrategy(dimension(), similarity, properties);
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

    @Override
    public synchronized void rebuild() {
        delegate.rebuild();
    }

    @Override
    public synchronized boolean remove(String id) {
        checkNotClosed();
        return delegate.doRemove(id);
    }

    @Override
    public synchronized boolean update(String id, float[] vector) {
        checkNotClosed();
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        return delegate.doUpdate(id, vector);
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
        boolean doRemove(String id);
        boolean doUpdate(String id, float[] vector);
        void rebuild();
    }

    private static class EagerMemoryStrategy extends AbstractIdOrdinalStorage implements StorageStrategy {
        /** 向量维度 */
        private final int dimension;
        /** 相似度度量函数 */
        private final VectorSimilarityFunction similarity;
        /** 存储配置属性（含图参数） */
        private final JVectorStorageProperties properties;
        /** 内存图索引；构建前为 null */
        private ImmutableGraphIndex graph;
        /** 原始向量深拷贝（防御调用者后续修改） */
        private final List<float[]> rawVectors = new ArrayList<>();
        /** JVector 向量视图 */
        private final List<VectorFloat<?>> vectors = new ArrayList<>();

        EagerMemoryStrategy(int dimension, VectorSimilarityFunction similarity, JVectorStorageProperties properties) {
            this.dimension = dimension;
            this.similarity = similarity;
            this.properties = properties;
        }

        @Override
        public synchronized void rebuild() {
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
        }

        @Override
        public synchronized boolean doAdd(String id, float[] vector) {
            int ord = vectors.size();
            if (!tryRegister(id, ord)) return false;
            rawVectors.add(vector.clone());
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
            // 必须使用 jvector 内置精确分数（与建图时的 VectorSimilarityFunction 一致）；
            // 自定义负分数会导致 rc.9 的 search 返回 0 结果。
            var queryVec = VTS.createFloatVector(query);
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            SearchScoreProvider ssp = DefaultSearchScoreProvider.exact(queryVec, similarity, rav);
            try (var searcher = new GraphSearcher(graph)) {
                var result = searcher.search(ssp, topK, Bits.ALL);
                var list = new ArrayList<Vector>();
                for (var n : result.getNodes()) {
                    var id = idOf(n.node);
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
        public synchronized boolean doRemove(String id) {
            Integer ord = ordinalOf(id);
            if (ord == null) return false;
            int last = vectors.size() - 1;
            if (ord != last) {
                // 把末尾元素移动到被删位置，保持序数紧凑
                String movedId = idOf(last);
                rawVectors.set(ord, rawVectors.get(last));
                vectors.set(ord, vectors.get(last));
                moveOrdinal(movedId, last, ord);
                rawVectors.remove(last);
                vectors.remove(last);
                idToOrd.remove(id);
            } else {
                rawVectors.remove(last);
                vectors.remove(last);
                unregister(id, ord);
            }
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
            return true;
        }

        @Override
        public synchronized boolean doUpdate(String id, float[] vector) {
            Integer ord = ordinalOf(id);
            if (ord == null) return false;
            rawVectors.set(ord, vector);
            vectors.set(ord, VTS.createFloatVector(vector));
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
            return true;
        }

        @Override
        public synchronized int size() { return vectors.size(); }

        @Override
        public synchronized void clear() {
            vectors.clear(); rawVectors.clear();
            resetOrdinals();
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
            try (var builder = new GraphIndexBuilder(
                    rav, similarity,
                    properties.getGraphM(),
                    properties.getGraphEfConstruction(),
                    1.0f, 1.2f, false)) {
                graph = builder.build(rav);
            } catch (Exception e) {
                throw new RuntimeException("构建内存图失败", e);
            }
        }

    }



    /**
     * DiskStrategy: ON_DISK 模式，将内存构建的图持久化到磁盘，支持加载回来搜索。
     *
     * @author CH
     */
    private static class DiskStrategy extends AbstractIdOrdinalStorage implements StorageStrategy {
        /** 向量维度 */
        private final int dimension;
        /** 相似度度量函数 */
        private final VectorSimilarityFunction similarity;
        /** 存储配置属性（含图参数） */
        private final JVectorStorageProperties properties;
        /** 磁盘索引文件路径 */
        private final Path indexPath;
        /** 磁盘图索引；构建前为 null */
        private OnDiskGraphIndex diskGraph;
        /** 原始向量深拷贝（防御调用者后续修改） */
        private final List<float[]> rawVectors = new ArrayList<>();
        /** JVector 向量视图 */
        private final List<VectorFloat<?>> vectors = new ArrayList<>();

        DiskStrategy(int dimension, VectorSimilarityFunction similarity,
                     JVectorStorageProperties properties) {
            this.dimension = dimension;
            this.similarity = similarity;
            this.properties = properties;
            this.indexPath = Paths.get(properties.getIndexPath());
            tryLoadExistingIndex();
        }

        private void tryLoadExistingIndex() {
            if (!Files.exists(indexPath)) {
                return;
            }
            try {
                diskGraph = OnDiskGraphIndex.load(new SimpleMappedReader.Supplier(indexPath));
            } catch (Exception e) {
                System.err.printf("[WARN] JVector 磁盘索引加载失败，将在下次搜索时重建: path=%s, err=%s%n",
                        indexPath, e.getMessage());
                diskGraph = null;
            }
        }

        @Override
        public synchronized void rebuild() {
            if (diskGraph != null) {
                try { diskGraph.close(); } catch (Exception ignored) {}
                diskGraph = null;
            }
            ensureGraphBuilt();
        }

        @Override
        public synchronized boolean doAdd(String id, float[] vector) {
            int ord = vectors.size();
            if (!tryRegister(id, ord)) return false;
            rawVectors.add(vector.clone());
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

            // 使用 jvector 内置精确分数（与建图时的 VectorSimilarityFunction 一致）
            var queryVec = VTS.createFloatVector(query);
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            SearchScoreProvider ssp = DefaultSearchScoreProvider.exact(queryVec, similarity, rav);

            try (var searcher = new GraphSearcher(diskGraph)) {
                int ef = Math.max(topK, (int) (topK * properties.getSearchOverquery()));
                var result = searcher.search(ssp, ef, Bits.ALL);
                var list = new ArrayList<Vector>();
                for (var n : result.getNodes()) {
                    var id = idOf(n.node);
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
        public synchronized boolean doRemove(String id) {
            Integer ord = ordinalOf(id);
            if (ord == null) return false;
            int last = vectors.size() - 1;
            if (ord != last) {
                String movedId = idOf(last);
                rawVectors.set(ord, rawVectors.get(last));
                vectors.set(ord, vectors.get(last));
                moveOrdinal(movedId, last, ord);
                rawVectors.remove(last);
                vectors.remove(last);
                idToOrd.remove(id);
            } else {
                rawVectors.remove(last);
                vectors.remove(last);
                unregister(id, ord);
            }
            diskGraph = null;
            return true;
        }

        @Override
        public synchronized boolean doUpdate(String id, float[] vector) {
            Integer ord = ordinalOf(id);
            if (ord == null) return false;
            rawVectors.set(ord, vector);
            vectors.set(ord, VTS.createFloatVector(vector));
            diskGraph = null;
            return true;
        }

        @Override
        public synchronized int size() { return vectors.size(); }

        @Override
        public synchronized void clear() {
            vectors.clear(); rawVectors.clear();
            resetOrdinals();
            if (diskGraph != null) {
                try { diskGraph.close(); } catch (Exception ignored) {}
                diskGraph = null;
            }
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
                // build() 内部会遍历全部节点添加，无需手动 addGraphNode（否则重复添加报错）
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

    }

    /**
     * LargerThanMemoryStrategy: LARGER_THAN_MEMORY 模式，使用 PQ 压缩向量构建图，
     * 搜索时使用两阶段策略（粗排 + 精排）。
     *
     * @author CH
     */
    private static class LargerThanMemoryStrategy extends AbstractIdOrdinalStorage implements StorageStrategy {
        /** 向量维度 */
        private final int dimension;
        /** 相似度度量函数 */
        private final VectorSimilarityFunction similarity;
        /** 存储配置属性（含图参数） */
        private final JVectorStorageProperties properties;
        /** PQ 索引持久化路径 */
        private final Path indexPath;
        /** 内存图索引；构建前为 null */
        private ImmutableGraphIndex graph;
        /** PQ 压缩向量；训练前为 null */
        private PQVectors pqVectors;
        /** 原始向量深拷贝（防御调用者后续修改） */
        private final List<float[]> rawVectors = new ArrayList<>();
        /** JVector 向量视图 */
        private final List<VectorFloat<?>> vectors = new ArrayList<>();

        LargerThanMemoryStrategy(int dimension, VectorSimilarityFunction similarity,
                                 JVectorStorageProperties properties) {
            this.dimension = dimension;
            this.similarity = similarity;
            this.properties = properties;
            this.indexPath = Paths.get(properties.getIndexPath() + ".pq");
        }

        @Override
        public synchronized void rebuild() {
            if (graph != null) {
                try { graph.close(); } catch (Exception ignored) {}
                graph = null;
            }
            pqVectors = null;
        }

        @Override
        public synchronized boolean doAdd(String id, float[] vector) {
            int ord = vectors.size();
            if (!tryRegister(id, ord)) return false;
            rawVectors.add(vector.clone());
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
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            // 第一阶段：使用 PQ 压缩向量粗排
            var pqScore = pqVectors.precomputedScoreFunctionFor(queryVec, similarity);
            SearchScoreProvider roughProvider = new DefaultSearchScoreProvider(pqScore);

            // 第二阶段：使用 jvector 内置精确分数精排（与建图度量一致，分数为正）
            var exactProvider = DefaultSearchScoreProvider.exact(queryVec, similarity, rav);
            var exactScore = exactProvider.exactScoreFunction();

            try (var searcher = new GraphSearcher(graph)) {
                int ef = Math.max(topK, (int) (topK * properties.getSearchOverquery()));
                // 粗排获取候选集
                var roughResult = searcher.search(roughProvider, ef * 2, Bits.ALL);
                // 精排 rerank
                var list = new ArrayList<Vector>();
                for (var n : roughResult.getNodes()) {
                    var id = idOf(n.node);
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
        public synchronized boolean doRemove(String id) {
            Integer ord = ordinalOf(id);
            if (ord == null) return false;
            int last = vectors.size() - 1;
            if (ord != last) {
                String movedId = idOf(last);
                rawVectors.set(ord, rawVectors.get(last));
                vectors.set(ord, vectors.get(last));
                moveOrdinal(movedId, last, ord);
                rawVectors.remove(last);
                vectors.remove(last);
                idToOrd.remove(id);
            } else {
                rawVectors.remove(last);
                vectors.remove(last);
                unregister(id, ord);
            }
            graph = null;
            pqVectors = null;
            return true;
        }

        @Override
        public synchronized boolean doUpdate(String id, float[] vector) {
            Integer ord = ordinalOf(id);
            if (ord == null) return false;
            rawVectors.set(ord, vector);
            vectors.set(ord, VTS.createFloatVector(vector));
            graph = null;
            pqVectors = null;
            return true;
        }

        @Override
        public synchronized int size() { return vectors.size(); }

        @Override
        public synchronized void clear() {
            vectors.clear(); rawVectors.clear();
            resetOrdinals();
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
                // 防御性钳制：jvector 要求子空间数 ≤ 维度、每个子空间码本数 ≤ 向量条数，
                // 小数据集（示例仅 10~100 条）下默认值 64/256 会导致 KMeans 抛
                // "Number of clusters N cannot exceed number of points M"。
                int numVectors = vectors.size();
                int subspaces = Math.max(1, Math.min(properties.getPqSubspaces(), dimension));
                int centroids = Math.max(1, Math.min(properties.getPqCentroidsPerSubspace(), numVectors));

                // 1. 训练 PQ 量化器
                var pq = ProductQuantization.compute(
                        rav,
                        subspaces,
                        centroids,
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
                    // build() 内部会遍历全部节点添加，无需手动 addGraphNode（否则重复添加报错）
                    graph = builder.build(rav);
                }
            } catch (Exception e) {
                throw new RuntimeException("构建 PQ 图失败", e);
            }
        }

    }
}
