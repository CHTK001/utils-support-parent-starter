package com.chua.vector.support.storage;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.vector.support.configuration.VectorStorageProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NVIDIA cuVS GPU 向量存储实现。
 *
 * <p>支持 CAGRA、BruteForce、HNSW 三种索引策略。
 * 无 CUDA 环境时由 {@link com.chua.vector.support.spi.VectorStorageProviderFactory}
 * 自动降级到 jvector CPU 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CuvsVectorStorage extends AbstractVectorStorage {

    private final VectorStorageProperties properties;
    private final IndexStrategy delegate;

    public CuvsVectorStorage(int dimension, VectorCompareAlgorithm algorithm,
                              VectorStorageProperties properties) {
        super(dimension, algorithm);
        this.properties = properties;
        this.delegate = createStrategy();
    }

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

    // ==================== CAGRA 策略（GPU 加速） ====================

    private class CagraStrategy implements IndexStrategy {
        private com.nvidia.cuvs.CagraIndex index;
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
                return List.of();
            }
            try {
                com.nvidia.cuvs.CagraQuery queryObj = new com.nvidia.cuvs.CagraQuery(
                        new float[][]{query}, topK, properties.getSearchEf());
                com.nvidia.cuvs.SearchResults results = index.search(queryObj);
                List<Map<Integer, Float>> hits = results.getResults();
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
                log.error("[vector-starter] CAGRA search failed, falling back to CPU brute-force", t);
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
                com.nvidia.cuvs.CuVSResources resources = com.nvidia.cuvs.CuVSResources.create();
                try {
                    com.nvidia.cuvs.CagraIndexParams params =
                            new com.nvidia.cuvs.CagraIndexParams.Builder()
                                    .withGraphDegree(properties.getGraphDegree())
                                    .withIntermediateGraphDegree(properties.getIntermediateGraphDegree())
                                    .withMetric(toCuvsDistance())
                                    .build();
                    index = com.nvidia.cuvs.CagraIndex.newBuilder(resources)
                            .withDataset(rawVectors.toArray(new float[0][]))
                            .withIndexParams(params)
                            .build();
                    indexBuilt = true;
                } finally {
                    resources.close();
                }
            } catch (Throwable t) {
                log.warn("[vector-starter] CAGRA index build failed, will retry on next search: {}",
                        t.getMessage());
            } finally {
                building = false;
            }
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
            if (index != null) {
                try {
                    index.close();
                } catch (Exception ignored) {
                }
                index = null;
            }
        }

        @Override
        public void close() {
            if (index != null) {
                try {
                    index.close();
                } catch (Exception ignored) {
                }
                index = null;
            }
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

    // ==================== HNSW 策略（GPU 加速） ====================

    private class HnswStrategy implements IndexStrategy {
        private com.nvidia.cuvs.HnswIndex index;
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
                return List.of();
            }
            try {
                com.nvidia.cuvs.HnswQuery queryObj = new com.nvidia.cuvs.HnswQuery(
                        new float[][]{query}, topK, properties.getSearchEf());
                com.nvidia.cuvs.SearchResults results = index.search(queryObj);
                List<Map<Integer, Float>> hits = results.getResults();
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
                log.error("[vector-starter] HNSW search failed, falling back to CPU", t);
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
                com.nvidia.cuvs.CuVSResources resources = com.nvidia.cuvs.CuVSResources.create();
                try {
                    com.nvidia.cuvs.HnswIndexParams params =
                            new com.nvidia.cuvs.HnswIndexParams.Builder()
                                    .withM(properties.getGraphDegree())
                                    .withEfConstruction(properties.getSearchEf())
                                    .withMetric(toCuvsDistance())
                                    .build();
                    index = com.nvidia.cuvs.HnswIndex.newBuilder(resources)
                            .withDataset(rawVectors.toArray(new float[0][]))
                            .withIndexParams(params)
                            .build();
                    indexBuilt = true;
                } finally {
                    resources.close();
                }
            } catch (Throwable t) {
                log.warn("[vector-starter] HNSW index build failed, will retry: {}", t.getMessage());
            } finally {
                building = false;
            }
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
            if (index != null) {
                try {
                    index.close();
                } catch (Exception ignored) {
                }
                index = null;
            }
        }

        @Override
        public void close() {
            if (index != null) {
                try {
                    index.close();
                } catch (Exception ignored) {
                }
                index = null;
            }
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

    // ==================== 工具方法 ====================

    private static com.nvidia.cuvs.CuvsDistanceType toCuvsDistance() {
        VectorCompareAlgorithm algo = getAlgorithm();
        if (algo == null) {
            return com.nvidia.cuvs.CuvsDistanceType.L2Expanded;
        }
        return switch (algo.name().toUpperCase()) {
            case "COSINE" -> com.nvidia.cuvs.CuvsDistanceType.CosineExpanded;
            case "DOT", "DOT_PRODUCT", "IP" -> com.nvidia.cuvs.CuvsDistanceType.InnerProduct;
            default -> com.nvidia.cuvs.CuvsDistanceType.L2Expanded;
        };
    }
}
