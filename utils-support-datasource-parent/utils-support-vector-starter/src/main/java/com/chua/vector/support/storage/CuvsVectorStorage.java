package com.chua.vector.support.storage;

import static com.chua.common.support.reflection.ReflectUtils.forName;
import static com.chua.common.support.reflection.ReflectUtils.invoke;
import static com.chua.common.support.reflection.ReflectUtils.invokeStatic;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.vector.support.configuration.VectorStorageProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NVIDIA cuvs GPU 向量存储实现（反射调用，无需编译期 cuvs-Java 依赖）。
 *
 * <p>运行时通过反射调用 {@code com.nvidia.cuvs.*} 类。
 * 如果 cuvs NAT 库不可用，搜索 时自动降级到 CPU 暴力搜索。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CuvsVectorStorage extends AbstractVectorStorage {

    private final VectorStorageProperties properties; // 属性
    private final IndexStrategy delegate; // delegate

    /**
     * 构造 cuvs 向量存储。
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties 存储配置属性
     */
    public CuvsVectorStorage(int dimension, VectorCompareAlgorithm algorithm,
                              VectorStorageProperties properties) {
        super(dimension, algorithm);
        this.properties = properties;
        this.delegate = createStrategy();
    }

    /**
     * 根据索引类型创建对应的策略实例。
     *
     * @return 索引策略实例
     */
    private IndexStrategy createStrategy() {
        return switch (properties.indexType()) {
            case BRUTE_FORCE -> new BruteForceStrategy();
            case HNSW -> new HnswStrategy();
            case CAGRA -> new CagraStrategy();
        };
    }

    @Override
    protected synchronized boolean doAdd(String id, float[] vector) {
        return delegate.add(id, vector);
    }

    @Override
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        return delegate.search(query, topK);
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
    public synchronized boolean remove(String id) {
        return delegate.remove(id);
    }

    @Override
    public synchronized boolean update(String id, float[] vector) {
        return delegate.update(id, vector);
    }

    /**
     * 内部策略接口，统一 添加/搜索/关闭 等操作。
     * @author CH
     * @since 4.0.0
     */
    private interface IndexStrategy {
        boolean add(String id, float[] vector);
        List<Vector> search(float[] query, int topK);
        int size();
        void clear();
        void close();
        boolean remove(String id);
        boolean update(String id, float[] vector);
    }

    // ==================== BruteForce 策略 ====================
    /**
     * BruteForceStrategy类。
     *
     * @author CH
     * @since 4.0.0
     */

    private class BruteForceStrategy implements IndexStrategy {
        private final List<float[]> vectors = new ArrayList<>(); // 向量
        private final Map<String, Integer> idToOrd = new java.util.HashMap<>(); // 标识转为ord

        @Override
        public boolean add(String id, float[] vector) {
            if (idToOrd.containsKey(id)) {
                return false;
            }
            int ord = vectors.size();
            vectors.add(vector.clone());
            idToOrd.put(id, ord);
            return true;
        }

        @Override
        public List<Vector> search(float[] query, int topK) {
            if (vectors.isEmpty() || topK <= 0) {
                return List.of();
            }
            var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
            List<Vector> all = new ArrayList<>(vectors.size());
            for (Map.Entry<String, Integer> entry : idToOrd.entrySet()) {
                float sim = algo.compare(query, vectors.get(entry.getValue()));
                all.add(new Vector(entry.getKey(), vectors.get(entry.getValue()),
                        Map.of("score", (double) sim)));
            }
            // compare() 语义为相似度（越大越相似），必须降序取最优 topK
            all.sort((a, b) -> Double.compare(
                    (Double) b.metadata().get("score"),
                    (Double) a.metadata().get("score")));
            return new ArrayList<>(all.subList(0, Math.min(topK, all.size())));
        }

        @Override
        public int size() {
            return vectors.size();
        }

        @Override
        public void clear() {
            vectors.clear();
            idToOrd.clear();
        }

        @Override
        public void close() {
        }

        @Override
        public boolean remove(String id) {
            Integer ord = idToOrd.remove(id);
            if (ord != null) {
                vectors.remove(ord.intValue());
                // 其后序数整体前移，必须同步重映射，否则 id 指向错误向量
                idToOrd.replaceAll((k, v) -> v > ord ? v - 1 : v);
            }
            return ord != null;
        }

        @Override
        public boolean update(String id, float[] vector) {
            Integer ord = idToOrd.get(id);
            if (ord == null) {
                return false;
            }
            vectors.set(ord, vector.clone());
            return true;
        }
    }

    // ==================== CAGRA 策略（GPU 加速，反射调用） ====================
    /**
     * CagraStrategy类。
     *
     * @author CH
     * @since 4.0.0
     */

    private class CagraStrategy implements IndexStrategy {
        private Object index; // 索引
        private Object resources; // resources
        private final List<float[]> rawVectors = new ArrayList<>(); // raw向量
        private final Map<String, Integer> idToOrd = new java.util.HashMap<>(); // 标识转为ord
        private volatile boolean indexBuilt = false; // 索引built
        private volatile boolean building = false; // 构建

        @Override
        public boolean add(String id, float[] vector) {
            if (idToOrd.containsKey(id)) {
                return false;
            }
            int ord = rawVectors.size();
            rawVectors.add(vector.clone());
            idToOrd.put(id, ord);
            indexBuilt = false;
            return true;
        }

        @Override
        public List<Vector> search(float[] query, int topK) {
            ensureIndexBuilt();
            if (index == null) {
                return fallbackSearch(query, topK);
            }
            try {
                Object queryObj = invokeStatic("com.nvidia.cuvs.CagraQuery", "newQuery",
                        Object.class, new float[][]{query}, topK * 5, properties.searchEf());
                Object results = invoke(index, "search", Object.class,
                        forName("com.nvidia.cuvs.CagraQuery"), queryObj);
                @SuppressWarnings("unchecked")
                List<Map<Integer, Float>> hits = (List<Map<Integer, Float>>) invoke(results, "getResults", List.class);
                List<Vector> candidates = new ArrayList<>();
                if (!hits.isEmpty()) {
                    Map<Integer, Float> hit = hits.getFirst();
                    for (Map.Entry<Integer, Float> e : hit.entrySet()) {
                        int ord = e.getKey();
                        if (ord < rawVectors.size()) {
                            String id = findIdByOrd(ord, idToOrd);
                            if (id == null) {
                                continue;
                            }
                            candidates.add(new Vector(id, rawVectors.get(ord),
                                    Map.of("score", (double) e.getValue(), "origOrd", ord)));
                        }
                    }
                }
                return reRank(candidates, query, topK);
            } catch (Throwable t) {
                log.warn("[vector-starter] CAGRA search failed, falling back to CPU: {}", t.getMessage());
                return fallbackSearch(query, topK);
            }
        }

        /**
         * CPU 降级搜索，当 GPU 索引不可用时执行。
         *
         * @param query 查询向量
         * @param topK  返回数量
         * @return 按相似度降序排列的向量列表
         */
        private List<Vector> fallbackSearch(float[] query, int topK) {
            if (rawVectors.isEmpty() || topK <= 0) {
                return List.of();
            }
            var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
            List<Vector> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : idToOrd.entrySet()) {
                float sim = algo.compare(query, rawVectors.get(entry.getValue()));
                all.add(new Vector(entry.getKey(), rawVectors.get(entry.getValue()),
                        Map.of("score", (double) sim)));
            }
            // compare() 语义为相似度（越大越相似），必须降序取最优 topK
            all.sort((a, b) -> Double.compare(
                    (Double) b.metadata().get("score"),
                    (Double) a.metadata().get("score")));
            return new ArrayList<>(all.subList(0, Math.min(topK, all.size())));
        }

        /**
         * 确保 GPU 索引已构建（线程安全，单例缓存）。
         */
        private synchronized void ensureIndexBuilt() {
            if (indexBuilt || rawVectors.isEmpty() || building) {
                return;
            }
            // 数据变更后旧索引已失效，必须先关闭旧索引并释放旧 resources，否则会泄漏
            if (index != null) {
                try {
                    invoke(index, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close CagraIndex", ignored);
                }
                index = null;
            }
            releaseResources();
            building = true;
            try {
                resources = invokeStatic("com.nvidia.cuvs.CuVSResources", "create", Object.class);
                int deviceId = (int) invoke(resources, "deviceId", int.class);
                log.info("[vector-starter] cuVS CAGRA using device: {}", deviceId);

                Object params = invokeStatic("com.nvidia.cuvs.CagraIndexParams", "builder", Object.class);
                invoke(params, "withGraphDegree", Object.class, (long) properties.graphDegree());
                invoke(params, "withIntermediateGraphDegree", Object.class,
                        (long) properties.intermediateGraphDegree());
                invoke(params, "withMetric", Object.class, cuvsDistanceType());

                index = invokeStatic("com.nvidia.cuvs.CagraIndex", "newBuilder",
                        Object.class, forName("com.nvidia.cuvs.CuVSResources"), resources);
                invoke(index, "withDataset", Object.class,
                        new Object[]{rawVectors.toArray(new float[0][])});
                invoke(index, "withIndexParams", Object.class,
                        forName("com.nvidia.cuvs.CagraIndexParams"), params);
                invoke(index, "build", Object.class);
                indexBuilt = true;
            } catch (Throwable t) {
                log.warn("[vector-starter] CAGRA index build failed, will retry: {}", t.getMessage());
                releaseResources();
            } finally {
                building = false;
            }
        }

        /**
         * 释放 GPU 资源。
         */
        private void releaseResources() {
            if (resources != null) {
                try {
                    invoke(resources, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close CuVSResources", ignored);
                }
                resources = null;
            }
        }

        /**
         * 根据算法映射 cuvs 距离类型。
         *
         * @return cuVS 距离类型枚举值
         */
        @SuppressWarnings("unchecked")
        private Object cuvsDistanceType() throws Exception {
            VectorCompareAlgorithm algo = getAlgorithm();
            Class<?> distanceTypeClass = forName("com.nvidia.cuvs.CuvsDistanceType");
            Object[] constants = (Object[]) invoke(null, "values", Object[].class, distanceTypeClass);
            if (algo == null) {
                return findEnumByName(constants, "L2Expanded");
            }
            String target = switch (algo.name().toUpperCase()) {
                case "COSINE" -> "CosineExpanded";
                case "DOT", "DOT_PRODUCT", "IP" -> "InnerProduct";
                default -> "L2Expanded";
            };
            return findEnumByName(constants, target);
        }

        @Override
        public int size() {
            return rawVectors.size();
        }

        @Override
        public void clear() {
            rawVectors.clear();
            idToOrd.clear();
            indexBuilt = false;
            close();
        }

        @Override
        public void close() {
            if (index != null) {
                try {
                    invoke(index, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close CagraIndex", ignored);
                }
                index = null;
            }
            releaseResources();
        }

        @Override
        public boolean remove(String id) {
            Integer ord = idToOrd.remove(id);
            if (ord != null) {
                rawVectors.remove(ord.intValue());
                // 其后序数整体前移，必须同步重映射，否则 id 指向错误向量
                idToOrd.replaceAll((k, v) -> v > ord ? v - 1 : v);
                indexBuilt = false;
            }
            return ord != null;
        }

        @Override
        public boolean update(String id, float[] vector) {
            Integer ord = idToOrd.get(id);
            if (ord == null) {
                return false;
            }
            rawVectors.set(ord, vector.clone());
            indexBuilt = false;
            return true;
        }
    }

    // ==================== HNSW 策略（GPU 加速，反射调用） ====================
    /**
     * HNSW 索引策略，通过反射调用 cuvs GPU 实现，索引不可用时降级 CPU 暴力搜索。
     *
     * @author CH
     * @since 4.0.0
     */

    private class HnswStrategy implements IndexStrategy {
        private Object index; // 索引
        private Object resources; // resources
        private final List<float[]> rawVectors = new ArrayList<>(); // raw向量
        private final Map<String, Integer> idToOrd = new java.util.HashMap<>(); // 标识转为ord
        private volatile boolean indexBuilt = false; // 索引built
        private volatile boolean building = false;

        @Override
        public boolean add(String id, float[] vector) {
            if (idToOrd.containsKey(id)) {
                return false;
            }
            int ord = rawVectors.size();
            rawVectors.add(vector.clone());
            idToOrd.put(id, ord);
            indexBuilt = false;
            return true;
        }

        @Override
        public List<Vector> search(float[] query, int topK) {
            if (topK <= 0) {
                return List.of();
            }
            ensureIndexBuilt();
            if (index == null) {
                return fallbackSearch(query, topK);
            }
            try {
                Object queryObj = invokeStatic("com.nvidia.cuvs.HnswQuery", "newQuery",
                        Object.class, new float[][]{query}, topK * 5, properties.searchEf());
                Object results = invoke(index, "search", Object.class,
                        forName("com.nvidia.cuvs.HnswQuery"), queryObj);
                @SuppressWarnings("unchecked")
                List<Map<Integer, Float>> hits = (List<Map<Integer, Float>>) invoke(results, "getResults", List.class);
                List<Vector> candidates = new ArrayList<>();
                if (!hits.isEmpty()) {
                    Map<Integer, Float> hit = hits.getFirst();
                    for (Map.Entry<Integer, Float> e : hit.entrySet()) {
                        int ord = e.getKey();
                        if (ord < rawVectors.size()) {
                            String id = findIdByOrd(ord, idToOrd);
                            if (id == null) {
                                continue;
                            }
                            candidates.add(new Vector(id, rawVectors.get(ord),
                                    Map.of("score", (double) e.getValue(), "origOrd", ord)));
                        }
                    }
                }
                return reRank(candidates, query, topK);
            } catch (Throwable t) {
                log.warn("[vector-starter] HNSW search failed, falling back to CPU: {}", t.getMessage());
                return fallbackSearch(query, topK);
            }
        }

        private List<Vector> fallbackSearch(float[] query, int topK) {
            if (rawVectors.isEmpty() || topK <= 0) {
                return List.of();
            }
            var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
            List<Vector> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : idToOrd.entrySet()) {
                float sim = algo.compare(query, rawVectors.get(entry.getValue()));
                all.add(new Vector(entry.getKey(), rawVectors.get(entry.getValue()),
                        Map.of("score", (double) sim)));
            }
            // compare() 语义为相似度（越大越相似），必须降序取最优 topK
            all.sort((a, b) -> Double.compare(
                    (Double) b.metadata().get("score"),
                    (Double) a.metadata().get("score")));
            return new ArrayList<>(all.subList(0, Math.min(topK, all.size())));
        }

        private synchronized void ensureIndexBuilt() {
            if (indexBuilt || rawVectors.isEmpty() || building) {
                return;
            }
            // 数据变更后旧索引已失效，必须先关闭旧索引并释放旧 resources，否则会泄漏
            if (index != null) {
                try {
                    invoke(index, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close HnswIndex", ignored);
                }
                index = null;
            }
            releaseResources();
            building = true;
            try {
                resources = invokeStatic("com.nvidia.cuvs.CuVSResources", "create", Object.class);

                Object params = invokeStatic("com.nvidia.cuvs.HnswIndexParams", "builder", Object.class);
                invoke(params, "withM", Object.class, properties.graphDegree());
                invoke(params, "withEfConstruction", Object.class, properties.searchEf());
                invoke(params, "withMetric", Object.class, cuvsDistanceType());

                index = invokeStatic("com.nvidia.cuvs.HnswIndex", "newBuilder",
                        Object.class, forName("com.nvidia.cuvs.CuVSResources"), resources);
                invoke(index, "withDataset", Object.class,
                        new Object[]{rawVectors.toArray(new float[0][])});
                invoke(index, "withIndexParams", Object.class,
                        forName("com.nvidia.cuvs.HnswIndexParams"), params);
                invoke(index, "build", Object.class);
                indexBuilt = true;
            } catch (Throwable t) {
                log.warn("[vector-starter] HNSW index build failed, will retry: {}", t.getMessage());
                releaseResources();
            } finally {
                building = false;
            }
        }

        private void releaseResources() {
            if (resources != null) {
                try {
                    invoke(resources, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close CuVSResources", ignored);
                }
                resources = null;
            }
        }

        @SuppressWarnings("unchecked")
        private Object cuvsDistanceType() throws Exception {
            VectorCompareAlgorithm algo = getAlgorithm();
            Class<?> distanceTypeClass = forName("com.nvidia.cuvs.CuvsDistanceType");
            Object[] constants = (Object[]) invoke(null, "values", Object[].class, distanceTypeClass);
            if (algo == null) {
                return findEnumByName(constants, "L2Expanded");
            }
            String target = switch (algo.name().toUpperCase()) {
                case "COSINE" -> "CosineExpanded";
                case "DOT", "DOT_PRODUCT", "IP" -> "InnerProduct";
                default -> "L2Expanded";
            };
            return findEnumByName(constants, target);
        }

        @Override
        public int size() {
            return rawVectors.size();
        }

        @Override
        public void clear() {
            rawVectors.clear();
            idToOrd.clear();
            indexBuilt = false;
            close();
        }

        @Override
        public void close() {
            if (index != null) {
                try {
                    invoke(index, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close HnswIndex", ignored);
                }
                index = null;
            }
            releaseResources();
        }

        @Override
        public boolean remove(String id) {
            Integer ord = idToOrd.remove(id);
            if (ord != null) {
                rawVectors.remove(ord.intValue());
                // 其后序数整体前移，必须同步重映射，否则 id 指向错误向量
                idToOrd.replaceAll((k, v) -> v > ord ? v - 1 : v);
                indexBuilt = false;
            }
            return ord != null;
        }

        @Override
        public boolean update(String id, float[] vector) {
            Integer ord = idToOrd.get(id);
            if (ord == null) {
                return false;
            }
            rawVectors.set(ord, vector.clone());
            indexBuilt = false;
            return true;
        }
    }

    /**
     * 按名称查找 cuVS 距离类型枚举值。
     *
     * @param constants 枚举常量数组
     * @param name 目标枚举名
     * @return 枚举值，找不到抛 IllegalArgumentException
     */
    private static Object findEnumByName(Object[] constants, String name) {
        for (Object c : constants) {
            if (name.equals(invoke(c, "name", String.class))) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown cuVS distance type: " + name);
    }

    /**
     * 两阶段重排序：先用 cuvs 原生算法粗筛候选，再用自定义算法按相似度降序精排取 topK。
     *
     * @param candidates 候选向量
     * @param query 查询向量
     * @param topK 取 topK
     * @return 重排序结果
     */
    private List<Vector> reRank(List<Vector> candidates, float[] query, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        var algo = getAlgorithm();
        if (algo == null) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
        // compare() 语义为相似度（越大越相似），必须降序精排
        candidates.sort((a, b) -> Double.compare(
                (double) algo.compare(query, b.data()),
                (double) algo.compare(query, a.data())));
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }

    /**
     * 反查序数对应的标识。
     *
     * @param ord 序数
     * @param idToOrd 标识到序数的映射
     * @return 对应标识，无法解析时返回 空（调用方必须跳过该候选）
     */
    private static String findIdByOrd(int ord, Map<String, Integer> idToOrd) {
        return idToOrd.entrySet().stream()
                .filter(e -> e.getValue() == ord)
                .findFirst()
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
