package com.chua.vector.support.storage;

import com.chua.common.support.reflection.ReflectUtils;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.vector.support.configuration.VectorStorageProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NVIDIA cuVS GPU 向量存储实现（反射调用，无需编译期 cuvs-java 依赖）。
 *
 * <p>运行时通过 {@link ReflectUtils} 反射调用 {@code com.nvidia.cuvs.*} 类。
 * 如果 cuVS native 库不可用，search 时自动降级到 CPU 暴力搜索。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CuvsVectorStorage extends AbstractVectorStorage {

    private final VectorStorageProperties properties;
    private final IndexStrategy delegate;

    /**
     * 构造 cuVS 向量存储。
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
        return switch (properties.getIndexType()) {
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
     * 内部策略接口，统一 add/search/close 等操作。
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

    private class BruteForceStrategy implements IndexStrategy {
        private final List<float[]> vectors = new ArrayList<>();
        private final Map<String, Integer> idToOrd = new java.util.HashMap<>();

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
            if (vectors.isEmpty()) {
                return List.of();
            }
            var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
            int fetchK = topK * properties.getBruteForceFetchFactor();
            List<Vector> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : idToOrd.entrySet()) {
                float dist = algo.compare(query, vectors.get(entry.getValue()));
                all.add(new Vector(entry.getKey(), vectors.get(entry.getValue()),
                        Map.of("score", (double) dist)));
            }
            all.sort((a, b) -> Double.compare(
                    (Double) a.metadata().get("score"),
                    (Double) b.metadata().get("score")));
            return all.subList(0, Math.min(fetchK, all.size()));
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
            // BruteForce 无 native 资源，无需关闭
        }

        @Override
        public boolean remove(String id) {
            Integer ord = idToOrd.remove(id);
            if (ord != null) {
                vectors.remove((int) ord.longValue());
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

    private class CagraStrategy implements IndexStrategy {
        private Object index;
        private Object resources;
        private final List<float[]> rawVectors = new ArrayList<>();
        private final Map<String, Integer> idToOrd = new java.util.HashMap<>();
        private volatile boolean indexBuilt = false;
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
            ensureIndexBuilt();
            if (index == null) {
                return fallbackSearch(query, topK);
            }
            try {
                Class<?> queryClass = forName("com.nvidia.cuvs.CagraQuery");
                Object queryObj = invokeStatic(queryClass, "newQuery", Object.class,
                        new float[][]{query}, topK, properties.getSearchEf());
                Object results = invoke(index, "search", Object.class, queryClass, queryObj);
                @SuppressWarnings("unchecked")
                List<Map<Integer, Float>> hits = (List<Map<Integer, Float>>) invoke(results, "getResults", List.class);
                List<Vector> list = new ArrayList<>();
                if (!hits.isEmpty()) {
                    Map<Integer, Float> hit = hits.get(0);
                    for (Map.Entry<Integer, Float> e : hit.entrySet()) {
                        int ord = e.getKey();
                        if (ord < rawVectors.size()) {
                            String id = idToOrd.entrySet().stream()
                                    .filter(en -> en.getValue() == ord)
                                    .findFirst()
                                    .map(Map.Entry::getKey)
                                    .orElse(String.valueOf(ord));
                            list.add(new Vector(id, rawVectors.get(ord),
                                    Map.of("score", (double) e.getValue())));
                        }
                    }
                }
                return list;
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
         * @return 排序后的向量列表
         */
        private List<Vector> fallbackSearch(float[] query, int topK) {
            if (rawVectors.isEmpty()) {
                return List.of();
            }
            var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
            List<Vector> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : idToOrd.entrySet()) {
                float dist = algo.compare(query, rawVectors.get(entry.getValue()));
                all.add(new Vector(entry.getKey(), rawVectors.get(entry.getValue()),
                        Map.of("score", (double) dist)));
            }
            all.sort((a, b) -> Double.compare(
                    (Double) a.metadata().get("score"),
                    (Double) b.metadata().get("score")));
            return all.subList(0, Math.min(topK, all.size()));
        }

        /**
         * 确保 GPU 索引已构建（线程安全，单例缓存）。
         */
        private synchronized void ensureIndexBuilt() {
            if (indexBuilt || index != null || rawVectors.isEmpty() || building) {
                return;
            }
            building = true;
            try {
                Class<?> resourcesClass = forName("com.nvidia.cuvs.CuVSResources");
                resources = invokeStatic(resourcesClass, "create", Object.class);
                int deviceId = (int) invoke(resources, "deviceId", int.class);
                log.info("[vector-starter] cuVS CAGRA using device: {}", deviceId);

                Object params = invokeStatic(forName("com.nvidia.cuvs.CagraIndexParams"), "builder", Object.class);
                Class<?> paramsClass = params.getClass();
                invoke(params, "withGraphDegree", Object.class, (long) properties.getGraphDegree());
                invoke(params, "withIntermediateGraphDegree", Object.class, (long) properties.getIntermediateGraphDegree());
                invoke(params, "withMetric", Object.class, cuvsDistanceType());

                index = invokeStatic(forName("com.nvidia.cuvs.CagraIndex"), "newBuilder",
                        Object.class, resourcesClass, resources);
                Class<?> builderClass = index.getClass();
                invoke(index, "withDataset", Object.class, rawVectors.toArray(new float[0][]));
                invoke(index, "withIndexParams", Object.class, forName("com.nvidia.cuvs.CagraIndexParams"), params);
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
         * 根据算法映射 cuVS 距离类型。
         *
         * @return cuVS 距离类型枚举值
         */
        private Object cuvsDistanceType() throws Exception {
            VectorCompareAlgorithm algo = getAlgorithm();
            Class<?> distanceTypeClass = forName("com.nvidia.cuvs.CuvsDistanceType");
            if (algo == null) {
                return ReflectUtils.getField(null, "L2Expanded", distanceTypeClass);
            }
            return switch (algo.name().toUpperCase()) {
                case "COSINE" -> ReflectUtils.getField(null, "CosineExpanded", distanceTypeClass);
                case "DOT", "DOT_PRODUCT", "IP" -> ReflectUtils.getField(null, "InnerProduct", distanceTypeClass);
                default -> ReflectUtils.getField(null, "L2Expanded", distanceTypeClass);
            };
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
                rawVectors.remove((int) ord.longValue());
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

    private class HnswStrategy implements IndexStrategy {
        private Object index;
        private Object resources;
        private final List<float[]> rawVectors = new ArrayList<>();
        private final Map<String, Integer> idToOrd = new java.util.HashMap<>();
        private volatile boolean indexBuilt = false;
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
            ensureIndexBuilt();
            if (index == null) {
                return fallbackSearch(query, topK);
            }
            try {
                Class<?> queryClass = forName("com.nvidia.cuvs.HnswQuery");
                Object queryObj = invokeStatic(queryClass, "newQuery", Object.class,
                        new float[][]{query}, topK, properties.getSearchEf());
                Object results = invoke(index, "search", Object.class, queryClass, queryObj);
                @SuppressWarnings("unchecked")
                List<Map<Integer, Float>> hits = (List<Map<Integer, Float>>) invoke(results, "getResults", List.class);
                List<Vector> list = new ArrayList<>();
                if (!hits.isEmpty()) {
                    Map<Integer, Float> hit = hits.get(0);
                    for (Map.Entry<Integer, Float> e : hit.entrySet()) {
                        int ord = e.getKey();
                        if (ord < rawVectors.size()) {
                            String id = idToOrd.entrySet().stream()
                                    .filter(en -> en.getValue() == ord)
                                    .findFirst()
                                    .map(Map.Entry::getKey)
                                    .orElse(String.valueOf(ord));
                            list.add(new Vector(id, rawVectors.get(ord),
                                    Map.of("score", (double) e.getValue())));
                        }
                    }
                }
                return list;
            } catch (Throwable t) {
                log.warn("[vector-starter] HNSW search failed, falling back to CPU: {}", t.getMessage());
                return fallbackSearch(query, topK);
            }
        }

        private List<Vector> fallbackSearch(float[] query, int topK) {
            if (rawVectors.isEmpty()) {
                return List.of();
            }
            var algo = getAlgorithm() != null ? getAlgorithm() : VectorCompareAlgorithm.euclidean();
            List<Vector> all = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : idToOrd.entrySet()) {
                float dist = algo.compare(query, rawVectors.get(entry.getValue()));
                all.add(new Vector(entry.getKey(), rawVectors.get(entry.getValue()),
                        Map.of("score", (double) dist)));
            }
            all.sort((a, b) -> Double.compare(
                    (Double) a.metadata().get("score"),
                    (Double) b.metadata().get("score")));
            return all.subList(0, Math.min(topK, all.size()));
        }

        private synchronized void ensureIndexBuilt() {
            if (indexBuilt || index != null || rawVectors.isEmpty() || building) {
                return;
            }
            building = true;
            try {
                Class<?> resourcesClass = forName("com.nvidia.cuvs.CuVSResources");
                resources = invokeStatic(resourcesClass, "create", Object.class);

                Object params = invokeStatic(forName("com.nvidia.cuvs.HnswIndexParams"), "builder", Object.class);
                Class<?> paramsClass = params.getClass();
                invoke(params, "withM", Object.class, properties.getGraphDegree());
                invoke(params, "withEfConstruction", Object.class, properties.getSearchEf());
                invoke(params, "withMetric", Object.class, cuvsDistanceType());

                index = invokeStatic(forName("com.nvidia.cuvs.HnswIndex"), "newBuilder",
                        Object.class, resourcesClass, resources);
                Class<?> builderClass = index.getClass();
                invoke(index, "withDataset", Object.class, rawVectors.toArray(new float[0][]));
                invoke(index, "withIndexParams", Object.class, forName("com.nvidia.cuvs.HnswIndexParams"), params);
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

        private Object cuvsDistanceType() throws Exception {
            VectorCompareAlgorithm algo = getAlgorithm();
            Class<?> distanceTypeClass = forName("com.nvidia.cuvs.CuvsDistanceType");
            if (algo == null) {
                return ReflectUtils.getField(null, "L2Expanded", distanceTypeClass);
            }
            return switch (algo.name().toUpperCase()) {
                case "COSINE" -> ReflectUtils.getField(null, "CosineExpanded", distanceTypeClass);
                case "DOT", "DOT_PRODUCT", "IP" -> ReflectUtils.getField(null, "InnerProduct", distanceTypeClass);
                default -> ReflectUtils.getField(null, "L2Expanded", distanceTypeClass);
            };
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
                rawVectors.remove((int) ord.longValue());
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
}
