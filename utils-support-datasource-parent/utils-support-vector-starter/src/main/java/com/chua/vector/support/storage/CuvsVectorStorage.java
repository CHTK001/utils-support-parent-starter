package com.chua.vector.support.storage;

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
 * NVIDIA cuVS GPU 鍚戦噺瀛樺偍瀹炵幇锛堝弽灏勮皟鐢紝鏃犻渶缂栬瘧鏈?cuvs-java 渚濊禆锛夈€? *
 * <p>杩愯鏃堕€氳繃 {@link ReflectUtils} 鍙嶅皠璋冪敤 {@code com.nvidia.cuvs.*} 绫汇€? * 濡傛灉 cuVS native 搴撲笉鍙敤锛宻earch 鏃惰嚜鍔ㄩ檷绾у埌 CPU 鏆村姏鎼滅储銆?/p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CuvsVectorStorage extends AbstractVectorStorage {

    private final VectorStorageProperties properties;
    private final IndexStrategy delegate;

    /**
     * 鏋勯€?cuVS 鍚戦噺瀛樺偍銆?     *
     * @param dimension  鍚戦噺缁村害
     * @param algorithm  姣旇緝绠楁硶
     * @param properties 瀛樺偍閰嶇疆灞炴€?     */
    public CuvsVectorStorage(int dimension, VectorCompareAlgorithm algorithm,
                              VectorStorageProperties properties) {
        super(dimension, algorithm);
        this.properties = properties;
        this.delegate = createStrategy();
    }

    /**
     * 鏍规嵁绱㈠紩绫诲瀷鍒涘缓瀵瑰簲鐨勭瓥鐣ュ疄渚嬨€?     *
     * @return 绱㈠紩绛栫暐瀹炰緥
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
     * 鍐呴儴绛栫暐鎺ュ彛锛岀粺涓€ add/search/close 绛夋搷浣溿€?     */
    private interface IndexStrategy {
        boolean add(String id, float[] vector);
        List<Vector> search(float[] query, int topK);
        int size();
        void clear();
        void close();
        boolean remove(String id);
        boolean update(String id, float[] vector);
    }

    // ==================== BruteForce 绛栫暐 ====================

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
            // BruteForce 鏃?native 璧勬簮锛屾棤闇€鍏抽棴
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

    // ==================== CAGRA 绛栫暐锛圙PU 鍔犻€燂紝鍙嶅皠璋冪敤锛?====================

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
                Class<?> queryClass = ReflectUtils.forName("com.nvidia.cuvs.CagraQuery");
                Object queryObj = ReflectUtils.invokeStatic(queryClass, "newQuery", Object.class,
                        new float[][]{query}, topK, properties.getSearchEf());
                Object results = ReflectUtils.invoke(index, "search", Object.class, queryClass, queryObj);
                @SuppressWarnings("unchecked")
                List<Map<Integer, Float>> hits = (List<Map<Integer, Float>>) ReflectUtils.invoke(results, "getResults", List.class);
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
         * CPU 闄嶇骇鎼滅储锛屽綋 GPU 绱㈠紩涓嶅彲鐢ㄦ椂鎵ц銆?         *
         * @param query 鏌ヨ鍚戦噺
         * @param topK  杩斿洖鏁伴噺
         * @return 鎺掑簭鍚庣殑鍚戦噺鍒楄〃
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
         * 纭繚 GPU 绱㈠紩宸叉瀯寤猴紙绾跨▼瀹夊叏锛屽崟渚嬬紦瀛橈級銆?         */
        private synchronized void ensureIndexBuilt() {
            if (indexBuilt || index != null || rawVectors.isEmpty() || building) {
                return;
            }
            building = true;
            try {
                Class<?> resourcesClass = ReflectUtils.forName("com.nvidia.cuvs.CuVSResources");
                resources = ReflectUtils.invokeStatic(resourcesClass, "create", Object.class);
                int deviceId = (int) ReflectUtils.invoke(resources, "deviceId", int.class);
                log.info("[vector-starter] cuVS CAGRA using device: {}", deviceId);

                Object params = ReflectUtils.invokeStatic(ReflectUtils.forName("com.nvidia.cuvs.CagraIndexParams"), "builder", Object.class);
                Class<?> paramsClass = params.getClass();
                ReflectUtils.invoke(params, "withGraphDegree", Object.class, (long) properties.getGraphDegree());
                ReflectUtils.invoke(params, "withIntermediateGraphDegree", Object.class, (long) properties.getIntermediateGraphDegree());
                ReflectUtils.invoke(params, "withMetric", Object.class, cuvsDistanceType());

                index = ReflectUtils.invokeStatic(ReflectUtils.forName("com.nvidia.cuvs.CagraIndex"), "newBuilder",
                        Object.class, resourcesClass, resources);
                Class<?> builderClass = index.getClass();
                ReflectUtils.invoke(index, "withDataset", Object.class, rawVectors.toArray(new float[0][]));
                ReflectUtils.invoke(index, "withIndexParams", Object.class, ReflectUtils.forName("com.nvidia.cuvs.CagraIndexParams"), params);
                ReflectUtils.invoke(index, "build", Object.class);
                indexBuilt = true;
            } catch (Throwable t) {
                log.warn("[vector-starter] CAGRA index build failed, will retry: {}", t.getMessage());
                releaseResources();
            } finally {
                building = false;
            }
        }

        /**
         * 閲婃斁 GPU 璧勬簮銆?         */
        private void releaseResources() {
            if (resources != null) {
                try {
                    ReflectUtils.invoke(resources, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close CuVSResources", ignored);
                }
                resources = null;
            }
        }

        /**
         * 鏍规嵁绠楁硶鏄犲皠 cuVS 璺濈绫诲瀷銆?         *
         * @return cuVS 璺濈绫诲瀷鏋氫妇鍊?         */
        private Object cuvsDistanceType() throws Exception {
            VectorCompareAlgorithm algo = getAlgorithm();
            Class<?> distanceTypeClass = ReflectUtils.ReflectUtils.forName("com.nvidia.cuvs.CuvsDistanceType");
            Object[] constants = (Object[]) ReflectUtils.invoke(null, "values", Object[].class, distanceTypeClass);
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

        @SuppressWarnings("unchecked")
        private static Object findEnumByName(Object[] constants, String name) {
            for (Object c : constants) {
                if (name.equals(ReflectUtils.invoke(c, "name", String.class))) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown cuVS distance type: " + name);
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
                    ReflectUtils.invoke(index, "close", Object.class);
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

    // ==================== HNSW 绛栫暐锛圙PU 鍔犻€燂紝鍙嶅皠璋冪敤锛?====================

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
                Class<?> queryClass = ReflectUtils.forName("com.nvidia.cuvs.HnswQuery");
                Object queryObj = ReflectUtils.invokeStatic(queryClass, "newQuery", Object.class,
                        new float[][]{query}, topK, properties.getSearchEf());
                Object results = ReflectUtils.invoke(index, "search", Object.class, queryClass, queryObj);
                @SuppressWarnings("unchecked")
                List<Map<Integer, Float>> hits = (List<Map<Integer, Float>>) ReflectUtils.invoke(results, "getResults", List.class);
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
                Class<?> resourcesClass = ReflectUtils.forName("com.nvidia.cuvs.CuVSResources");
                resources = ReflectUtils.invokeStatic(resourcesClass, "create", Object.class);

                Object params = ReflectUtils.invokeStatic(ReflectUtils.forName("com.nvidia.cuvs.HnswIndexParams"), "builder", Object.class);
                Class<?> paramsClass = params.getClass();
                ReflectUtils.invoke(params, "withM", Object.class, properties.getGraphDegree());
                ReflectUtils.invoke(params, "withEfConstruction", Object.class, properties.getSearchEf());
                ReflectUtils.invoke(params, "withMetric", Object.class, cuvsDistanceType());

                index = ReflectUtils.invokeStatic(ReflectUtils.forName("com.nvidia.cuvs.HnswIndex"), "newBuilder",
                        Object.class, resourcesClass, resources);
                Class<?> builderClass = index.getClass();
                ReflectUtils.invoke(index, "withDataset", Object.class, rawVectors.toArray(new float[0][]));
                ReflectUtils.invoke(index, "withIndexParams", Object.class, ReflectUtils.forName("com.nvidia.cuvs.HnswIndexParams"), params);
                ReflectUtils.invoke(index, "build", Object.class);
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
                    ReflectUtils.invoke(resources, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-starter] Failed to close CuVSResources", ignored);
                }
                resources = null;
            }
        }

        private Object cuvsDistanceType() throws Exception {
            VectorCompareAlgorithm algo = getAlgorithm();
            Class<?> distanceTypeClass = ReflectUtils.ReflectUtils.forName("com.nvidia.cuvs.CuvsDistanceType");
            Object[] constants = (Object[]) ReflectUtils.invoke(null, "values", Object[].class, distanceTypeClass);
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

        @SuppressWarnings("unchecked")
        private static Object findEnumByName(Object[] constants, String name) {
            for (Object c : constants) {
                if (name.equals(ReflectUtils.invoke(c, "name", String.class))) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Unknown cuVS distance type: " + name);
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
                    ReflectUtils.invoke(index, "close", Object.class);
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
