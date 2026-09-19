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
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

/**
 * j向量 向量存储门面，根据 {@link JVectorStorageProperties} 的 mode 选择底层策略。
 *
 * @author CH
 * @since 2025/01/15
 */
@Slf4j
public class JVectorVectorStorage extends AbstractVectorStorage {

    /**
     * j向量 向量类型支持实例
     */
    private static final VectorTypeSupport VTS =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    /**
     * 存储配置
     */
    private final JVectorStorageProperties properties;

    /**
     * jvector 相似度函数
     */
    private final VectorSimilarityFunction similarity;

    /**
     * 当前存储策略
     */
    private StorageStrategy delegate;

    /**
     * 默认构造。
     *
     * @param dimension 向量维度
     * @param algorithm 相似度算法
     */
    public JVectorVectorStorage(int dimension, VectorCompareAlgorithm algorithm) {
        this(dimension, algorithm, null);
    }

    /**
     * 全参数构造。
     *
     * @param dimension 向量维度
     * @param algorithm 相似度算法
     * @param properties 存储配置
     */
    public JVectorVectorStorage(int dimension,
                                VectorCompareAlgorithm algorithm,
                                JVectorStorageProperties properties) {
        super(dimension, algorithm);
        this.properties = properties != null ? properties : new JVectorStorageProperties();
        this.similarity = toJVectorSim(algorithm);
        this.delegate = createStrategy();
    }

    /**
     * 创建当前模式对应的存储策略。
     *
     * @return 存储策略实例
     */
    private StorageStrategy createStrategy() {
        return switch (properties.getMode()) {
            case MEMORY -> new EagerMemoryStrategy(dimension(), similarity, properties, getAlgorithm());
            case ON_DISK -> new DiskStrategy(dimension(), similarity, properties, getAlgorithm());
            case LARGER_THAN_MEMORY -> new LargerThanMemoryStrategy(dimension(), similarity, properties, getAlgorithm());
        };
    }

    @Override
    /**
     * 执行添加
    */
    protected synchronized boolean doAdd(String id, float[] vector) {
        return delegate.doAdd(id, vector);
    }

    @Override
    /**
     * 执行搜索
    */
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        return delegate.doSearch(query, topK);
    }

    @Override
    /**
     * 获取大小
    */
    public synchronized int size() {
        return delegate.size();
    }

    @Override
    /**
     * Clear
    */
    public synchronized void clear() {
        delegate.clear();
    }

    @Override
    /**
     * 关闭
    */
    public synchronized void close() {
        delegate.close();
    }

    @Override
    /**
     * Rebuild
    */
    public synchronized void rebuild() {
        checkNotClosed();
        delegate.rebuild();
    }

    @Override
    /**
     * 移除
    */
    public synchronized boolean remove(String id) {
        checkNotClosed();
        return delegate.doRemove(id);
    }

    @Override
    /**
     * 更新
    */
    public synchronized boolean update(String id, float[] vector) {
        checkNotClosed();
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        return delegate.doUpdate(id, vector);
    }

    /**
     * 转为j向量sim
     *
     * @param algo algo
     * @return 转为j向量sim的结果
     * @author CH
     * @since 4.0.0
     */
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
        /**
         * 向量维度
        */
        private final int dimension;
        /**
         * 相似度度量函数
        */
        private final VectorSimilarityFunction similarity;
        /**
         * 存储配置属性（含图参数）
        */
        private final JVectorStorageProperties properties;
        /**
         * 比较算法
        */
        private final VectorCompareAlgorithm algorithm;
        /**
         * 内存图索引；构建前为 空
        */
        private ImmutableGraphIndex graph;
        /**
         * 原始向量深拷贝（防御调用者后续修改）
        */
        private final List<float[]> rawVectors = new ArrayList<>();
        /**
         * j向量 向量视图
        */
        private final List<VectorFloat<?>> vectors = new ArrayList<>();

        EagerMemoryStrategy(int dimension, VectorSimilarityFunction similarity,
                            JVectorStorageProperties properties, VectorCompareAlgorithm algorithm) {
            this.dimension = dimension;
            this.similarity = similarity;
            this.properties = properties;
            this.algorithm = algorithm;
        }

        @Override
        /**
         * Rebuild
        */
        public synchronized void rebuild() {
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
        }

        @Override
        /**
         * 执行添加
        */
        public synchronized boolean doAdd(String id, float[] vector) {
            int ord = vectors.size();
            if (!tryRegister(id, ord)) {
                return false;
            }
            rawVectors.add(vector.clone());
            vectors.add(VTS.createFloatVector(vector));
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
            return true;
        }

        @Override
        /**
         * 执行向量搜索。
         * 优先走图搜索（HNSW），配置了重排算法时再对结果二次排序；空数据集直接返回空列表。
         *
         * @param query 查询向量，不能为空
         * @param topK  返回的最大结果数
         * @return 按相似度排序的向量列表
         */
        public synchronized List<Vector> doSearch(float[] query, int topK) {
            if (vectors.isEmpty()) {
                return List.of();
            }
            var algo = algorithm;
            List<Vector> graphResults = graphSearch(query, topK * 5);
            return algo != null ? reRank(graphResults, query, algo, topK) : graphResults;
        }

        private List<Vector> graphSearch(float[] query, int fetchK) {
            if (vectors.isEmpty()) {
                return List.of();
            }
            try {
                if (graph == null) {
                    buildGraph();
                }
                var queryVec = VTS.createFloatVector(query);
                var rav = new ListRandomAccessVectorValues(vectors, dimension);
                SearchScoreProvider ssp = DefaultSearchScoreProvider.exact(queryVec, similarity, rav);
                try (var searcher = new GraphSearcher(graph)) {
                    var result = searcher.search(ssp, fetchK, Bits.ALL);
                    var list = new ArrayList<Vector>();
                    for (var n : result.getNodes()) {
                        var id = idOf(n.node);
                        float[] vd = n.node < rawVectors.size() ? rawVectors.get(n.node) : new float[0];
                        list.add(new Vector(id, vd, Map.of("score", (double) n.score)));
                        if (list.size() >= fetchK) {
                            break;
                        }
                    }
                    return list;
                }
            } catch (Exception e) {
                if (graph != null) {
                    try {
                        graph.close();
                    } catch (Exception ignored) {}
                    graph = null;
                }
                log.warn("[jvector] 图搜索失败，降级为暴力扫描: {}", e.getMessage());
            }
            return bruteForceSearch(query, fetchK);
        }

        private List<Vector> reRank(List<Vector> candidates, float[] query, VectorCompareAlgorithm algo, int topK) {
            candidates.sort((a, b) -> Double.compare(
                    algo.compare(query, a.data()),
                    algo.compare(query, b.data())));
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }

        /**
         * 暴力线性扫描，使用当前配置的算法计算距离并选出 topK。
         * 遍历全部向量与查询向量计算距离，按距离排序取前 topK 个候选。
         *
         * @param query 查询向量
         * @param topK  返回的最大结果数
         * @return 按距离排序的向量列表
         */
        private List<Vector> bruteForceSearch(float[] query, int topK) {
            var algo = algorithm;
            if (algo == null) {
                return bruteForceCosine(query, topK);
            }
            int n = vectors.size();
            int k = Math.min(topK, n);
            String[] topIds = new String[k];
            float[] topScores = new float[k];
            Arrays.fill(topScores, Float.POSITIVE_INFINITY);
            for (int i = 0; i < n; i++) {
                float[] vec = rawVectors.get(i);
                float dist = algo.compare(query, vec);
                int pos = k - 1;
                while (pos >= 0 && topScores[pos] > dist) {
                    topIds[pos + 1] = topIds[pos];
                    topScores[pos + 1] = topScores[pos];
                    pos--;
                }
                topIds[pos + 1] = idOf(i);
                topScores[pos + 1] = dist;
            }
            var result = new ArrayList<Vector>();
            for (int i = 0; i < k; i++) {
                if (topIds[i] == null) {
                    break;
                }
                int idx = ordinalOf(topIds[i]);
                if (idx < 0 || idx >= rawVectors.size()) {
                    continue;
                }
                result.add(new Vector(topIds[i], rawVectors.get(idx),
                        Map.of("score", (double) topScores[i])));
            }
            return result;
        }

        private List<Vector> bruteForceCosine(float[] query, int topK) {
            int n = vectors.size();
            int k = Math.min(topK, n);
            String[] topIds = new String[k];
            float[] topScores = new float[k];
            Arrays.fill(topScores, Float.NEGATIVE_INFINITY);
            double qNorm = 0;
            for (float f : query) { qNorm += f * f; }
            qNorm = Math.sqrt(qNorm);
            if (qNorm == 0) {
                return List.of();
            }
            for (int i = 0; i < n; i++) {
                float[] vec = rawVectors.get(i);
                double dot = 0, vNorm = 0;
                for (int d = 0; d < dimension; d++) {
                    dot += (double) query[d] * vec[d];
                    vNorm += (double) vec[d] * vec[d];
                }
                vNorm = Math.sqrt(vNorm);
                if (vNorm == 0) {
                    continue;
                }
                float sim = (float) (dot / (qNorm * vNorm));
                int pos = k - 1;
                while (pos >= 0 && topScores[pos] < sim) {
                    topIds[pos + 1] = topIds[pos];
                    topScores[pos + 1] = topScores[pos];
                    pos--;
                }
                topIds[pos + 1] = idOf(i);
                topScores[pos + 1] = sim;
            }
            var result = new ArrayList<Vector>();
            for (int i = 0; i < k; i++) {
                if (topIds[i] == null) {
                    break;
                }
                int idx = ordinalOf(topIds[i]);
                if (idx < 0 || idx >= rawVectors.size()) {
                    continue;
                }
                result.add(new Vector(topIds[i], rawVectors.get(idx),
                        Map.of("score", (double) topScores[i])));
            }
            return result;
        }

        @Override
        /**
         * 执行移除
        */
        public synchronized boolean doRemove(String id) {
            Integer ord = ordinalOf(id);
            if (ord == null) {
                return false;
            }
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
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
            return true;
        }

        @Override
        /**
         * 执行更新
        */
        public synchronized boolean doUpdate(String id, float[] vector) {
            Integer ord = ordinalOf(id);
            if (ord == null) {
                return false;
            }
            rawVectors.set(ord, vector.clone());
            vectors.set(ord, VTS.createFloatVector(vector));
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
            return true;
        }

        @Override
        /**
         * 获取大小
        */
        public synchronized int size() { return vectors.size(); }

        @Override
        /**
         * Clear
        */
        public synchronized void clear() {
            vectors.clear();
            rawVectors.clear();
            resetOrdinals();
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
        }

        @Override
        /**
         * 关闭
        */
        public synchronized void close() {
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
        }

        /**
         * 构建图计算
        */
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
     * diskstrategy: ON_DISK 模式，将内存构建的图持久化到磁盘，支持加载回来搜索。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class DiskStrategy extends AbstractIdOrdinalStorage implements StorageStrategy {
        /**
         * 向量维度
        */
        private final int dimension;
        /**
         * 相似度度量函数
        */
        private final VectorSimilarityFunction similarity;
        /**
         * 存储配置属性（含图参数）
        */
        private final JVectorStorageProperties properties;
        /**
         * 磁盘索引文件路径
        */
        private final Path indexPath;
        /**
         * 磁盘图索引；构建前为 空
        */
        private OnDiskGraphIndex diskGraph;
        /**
         * 原始向量深拷贝（防御调用者后续修改）
        */
        private final List<float[]> rawVectors = new ArrayList<>();
        /**
         * j向量 向量视图
        */
        private final List<VectorFloat<?>> vectors = new ArrayList<>();
        /**
         * 向量持久化文件路径
        */
        private final Path vectorDataPath;
        /**
         * 向量是否被修改且未持久化
        */
        private boolean vectorsDirty;
        /**
         * 比较算法
        */
        private final VectorCompareAlgorithm algorithm;

        DiskStrategy(int dimension, VectorSimilarityFunction similarity,
                      JVectorStorageProperties properties, VectorCompareAlgorithm algorithm) {
            this.dimension = dimension;
            this.similarity = similarity;
            this.properties = properties;
            this.algorithm = algorithm;
            this.indexPath = Paths.get(properties.getIndexPath());
            this.vectorDataPath = Paths.get(properties.getIndexPath() + ".vectors");
            this.vectorsDirty = false;
            tryLoadExistingIndex();
        }

        /**
         * 尝试加载existing索引
        */
        private void tryLoadExistingIndex() {
            if (!Files.exists(indexPath)) {
                return;
            }
            loadVectors();
            try {
                diskGraph = OnDiskGraphIndex.load(new SimpleMappedReader.Supplier(indexPath));
            } catch (Exception e) {
                log.warn("[jvector-storage] 磁盘索引加载失败，将在下次搜索时重建: path={}, err={}",
                        indexPath, e.getMessage());
                diskGraph = null;
            }
        }

        /**
         * 加载向量
        */
        private void loadVectors() {
            if (!Files.exists(vectorDataPath)) {
                return;
            }
            try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(vectorDataPath)))) {
                int count = dis.readInt();
                rawVectors.clear();
                vectors.clear();
                resetOrdinals();
                for (int i = 0; i < count; i++) {
                    String id = dis.readUTF();
                    int len = dis.readInt();
                    float[] v = new float[len];
                    for (int j = 0; j < len; j++) {
                        v[j] = dis.readFloat();
                    }
                    rawVectors.add(v);
                    vectors.add(VTS.createFloatVector(v));
                    idToOrd.put(id, i);
                    ordToId.put(i, id);
                }
                vectorsDirty = false;
            } catch (Exception e) {
                log.warn("[jvector-storage] 磁盘向量数据加载失败: path={}, err={}",
                        vectorDataPath, e.getMessage());
            }
        }

        /**
         * 保存向量
        */
        private void saveVectors() {
            try {
                Files.createDirectories(vectorDataPath.getParent());
                try (DataOutputStream dos = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(vectorDataPath)))) {
                    dos.writeInt(rawVectors.size());
                    for (int i = 0; i < rawVectors.size(); i++) {
                        String id = ordToId.get(i);
                        if (id == null) {
                            id = "";
                        }
                        dos.writeUTF(id);
                        float[] v = rawVectors.get(i);
                        dos.writeInt(v.length);
                        for (float f : v) {
                            dos.writeFloat(f);
                        }
                    }
                }
                vectorsDirty = false;
            } catch (Exception e) {
                log.warn("[jvector-storage] 磁盘向量数据保存失败: path={}, err={}",
                        vectorDataPath, e.getMessage());
            }
        }


        @Override
        /**
         * 执行添加
        */
        public synchronized boolean doAdd(String id, float[] vector) {
            int ord = vectors.size();
            if (!tryRegister(id, ord)) {
                return false;
            }
            rawVectors.add(vector.clone());
            vectors.add(VTS.createFloatVector(vector));
            // 添加后需要重新构建图
            diskGraph = null;
            vectorsDirty = true;
            return true;
        }

        @Override
        /**
         * 执行搜索
        */
        public synchronized List<Vector> doSearch(float[] query, int topK) {
            if (diskGraph != null && vectors.isEmpty()) {
                loadVectors();
            }
            if (vectors.isEmpty()) {
                return List.of();
            }
            ensureGraphBuilt();
            if (diskGraph == null) {
                return List.of();
            }

 // 使用 jvector 内置精确分数（与建图时的 向量相似度function 一致）
            var queryVec = VTS.createFloatVector(query);
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            SearchScoreProvider ssp = DefaultSearchScoreProvider.exact(queryVec, similarity, rav);

            try (var searcher = new GraphSearcher(diskGraph)) {
                int ef = Math.max(topK, (int) (topK * properties.getSearchOverquery()));
                var result = searcher.search(ssp, ef, Bits.ALL);
                var list = new ArrayList<Vector>();
                for (var n : result.getNodes()) {
                    var id = idOf(n.node);
                    float[] vd = n.node < rawVectors.size() ? rawVectors.get(n.node) : new float[0];
                    list.add(new Vector(id, vd, Map.of("score", (double) n.score)));
                    if (list.size() >= topK) {
                        break;
                    }
                }
                return list;
            } catch (Exception e) {
                throw new RuntimeException("磁盘图搜索失败", e);
            }
        }

        @Override
        /**
         * 执行移除
        */
        public synchronized boolean doRemove(String id) {
            Integer ord = ordinalOf(id);
            if (ord == null) {
                return false;
            }
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
            vectorsDirty = true;
            return true;
        }

        @Override
        /**
         * 执行更新
        */
        public synchronized boolean doUpdate(String id, float[] vector) {
            Integer ord = ordinalOf(id);
            if (ord == null) {
                return false;
            }
            rawVectors.set(ord, vector.clone());
            vectors.set(ord, VTS.createFloatVector(vector));
            diskGraph = null;
            vectorsDirty = true;
            return true;
        }

        @Override
        /**
         * 获取大小
        */
        public synchronized int size() { return vectors.size(); }

        @Override
        /**
         * Clear
        */
        public synchronized void clear() {
            vectors.clear();
            rawVectors.clear();
            resetOrdinals();
            vectorsDirty = false;
            if (diskGraph != null) {
                try {
                    diskGraph.close();
                } catch (Exception ignored) {}
                diskGraph = null;
            }
            try {
                Files.deleteIfExists(vectorDataPath);
            } catch (IOException ignored) {}
        }

        @Override
        /**
         * 关闭
        */
        public synchronized void close() {
            if (vectorsDirty || diskGraph == null) {
                ensureGraphBuilt();
            }
            if (diskGraph != null) {
                try {
                    diskGraph.close();
                } catch (Exception ignored) {}
                diskGraph = null;
            }
            if (vectorsDirty) {
                saveVectors();
            }
        }

        @Override
        /**
         * Rebuild
        */
        public synchronized void rebuild() {
            if (diskGraph != null) {
                try {
                    diskGraph.close();
                } catch (Exception ignored) {}
                diskGraph = null;
            }
            saveVectors();
            ensureGraphBuilt();
        }

        /**
         * ensure图计算built
        */
        private void ensureGraphBuilt() {
            if (diskGraph != null) {
                return;
            }
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
     * largerthan内存strategy: LARGER_THAN_内存 模式，使用 PQ 压缩向量构建图，
     * 搜索时使用两阶段策略（粗排 + 精排）。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class LargerThanMemoryStrategy extends AbstractIdOrdinalStorage implements StorageStrategy {
        /**
         * 向量维度
        */
        private final int dimension;
        /**
         * 相似度度量函数
        */
        private final VectorSimilarityFunction similarity;
        /**
         * 存储配置属性（含图参数）
        */
        private final JVectorStorageProperties properties;
        /**
         * PQ 索引持久化路径
        */
        private final Path indexPath;
        /**
         * 内存图索引；构建前为 空
        */
        private ImmutableGraphIndex graph;
        /**
         * PQ 压缩向量；训练前为 空
        */
        private PQVectors pqVectors;
        /**
         * 原始向量深拷贝（防御调用者后续修改）
        */
        private final List<float[]> rawVectors = new ArrayList<>();
        /**
         * j向量 向量视图
        */
        private final List<VectorFloat<?>> vectors = new ArrayList<>();
        /**
         * 比较算法
        */
        private final VectorCompareAlgorithm algorithm;

        LargerThanMemoryStrategy(int dimension, VectorSimilarityFunction similarity,
                                 JVectorStorageProperties properties, VectorCompareAlgorithm algorithm) {
            this.dimension = dimension;
            this.similarity = similarity;
            this.properties = properties;
            this.algorithm = algorithm;
            this.indexPath = Paths.get(properties.getIndexPath() + ".pq");
        }

        @Override
        /**
         * Rebuild
        */
        public synchronized void rebuild() {
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
            pqVectors = null;
        }

        @Override
        /**
         * 执行添加
        */
        public synchronized boolean doAdd(String id, float[] vector) {
            int ord = vectors.size();
            if (!tryRegister(id, ord)) {
                return false;
            }
            rawVectors.add(vector.clone());
            vectors.add(VTS.createFloatVector(vector));
            // 添加后需要重新构建
            graph = null;
            pqVectors = null;
            return true;
        }

        @Override
        /**
         * 执行搜索
        */
        public synchronized List<Vector> doSearch(float[] query, int topK) {
            if (vectors.isEmpty()) {
                return List.of();
            }
            ensureGraphBuilt();
            if (graph == null || pqVectors == null) {
                return List.of();
            }

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
        /**
         * 执行移除
        */
        public synchronized boolean doRemove(String id) {
            Integer ord = ordinalOf(id);
            if (ord == null) {
                return false;
            }
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
        /**
         * 执行更新
        */
        public synchronized boolean doUpdate(String id, float[] vector) {
            Integer ord = ordinalOf(id);
            if (ord == null) {
                return false;
            }
            rawVectors.set(ord, vector.clone());
            vectors.set(ord, VTS.createFloatVector(vector));
            graph = null;
            pqVectors = null;
            return true;
        }

        @Override
        /**
         * 获取大小
        */
        public synchronized int size() { return vectors.size(); }

        @Override
        /**
         * Clear
        */
        public synchronized void clear() {
            vectors.clear();
            rawVectors.clear();
            resetOrdinals();
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
            pqVectors = null;
        }

        @Override
        /**
         * 关闭
        */
        public synchronized void close() {
            if (graph != null) {
                try {
                    graph.close();
                } catch (Exception ignored) {}
                graph = null;
            }
            pqVectors = null;
        }

        /**
         * ensure图计算built
        */
        private void ensureGraphBuilt() {
            if (graph != null && pqVectors != null) {
                return;
            }
            var rav = new ListRandomAccessVectorValues(vectors, dimension);
            try {
                // 防御性钳制：jvector 要求子空间数 ≤ 维度、每个子空间码本数 ≤ 向量条数，
 // 小数据集（示例仅 10~100 条）下默认值 64/256 会导致 kmeans 抛
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
                CompressedVectors compressed;
                if (properties.getPqParallelism() > 0) {
                    ForkJoinPool pool = new ForkJoinPool(properties.getPqParallelism());
                    try {
                        compressed = pq.encodeAll(rav, pool);
                    } finally {
                        pool.shutdown();
                    }
                } else {
                    compressed = pq.encodeAll(rav, ForkJoinPool.commonPool());
                }
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
