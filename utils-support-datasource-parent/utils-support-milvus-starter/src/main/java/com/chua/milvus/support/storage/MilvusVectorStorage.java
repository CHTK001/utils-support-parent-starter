package com.chua.milvus.support.storage;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Milvus 向量数据库的 {@link AbstractVectorStorage} 实现。
 *
 * <p>通过 MilvusClientV2 SDK 连接远程 Milvus 服务，使用 collection 存储向量。
 * 构造函数自动创建 collection，写入数据后需调用 {@link #release()} 刷新索引，
 * 搜索前需确保 collection 已加载。</p>
 *
 * @author CH
 */
public class MilvusVectorStorage extends AbstractVectorStorage {

    private final MilvusClientV2 client;
    private final String collectionName;
    private final IndexParam.MetricType algorithmName;
    private boolean released;

    /**
     * 构造 Milvus 向量存储。
     *
     * <p>初始化连接、创建 collection、加载 collection。</p>
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param host       Milvus 服务地址
     * @param port       Milvus 服务端口
     * @param collection 集合名称
     */
    public MilvusVectorStorage(int dimension, VectorCompareAlgorithm algorithm,
                               String host, int port, String collection) {
        super(dimension, algorithm);
        this.collectionName = collection != null ? collection : "vector_store";
        this.algorithmName = toMilvusMetricType(algorithm);

        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build();
        this.client = new MilvusClientV2(config);

        initCollection();
    }

    /**
     * 初始化 Milvus collection。
     */
    private void initCollection() {
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        if (!exists) {
            CreateCollectionReq req = CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .dimension(dimension())
                    .metricType(algorithmName.name())
                    .primaryFieldName("id")
                    .vectorFieldName("vector")
                    .idType(io.milvus.v2.common.DataType.Int64)
                    .autoID(false)
                    .enableDynamicField(true)
                    .build();
            client.createCollection(req);
        }
        client.loadCollection(io.milvus.v2.service.collection.request.LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
    }

    /**
     * 将业务层算法名称映射为 Milvus {@link IndexParam.MetricType}。
     */
    private static IndexParam.MetricType toMilvusMetricType(VectorCompareAlgorithm algo) {
        if (algo == null) {
            return IndexParam.MetricType.COSINE;
        }
        return switch (algo.name().toUpperCase()) {
            case "EUCLIDEAN", "L2" -> IndexParam.MetricType.L2;
            case "DOT", "DOT_PRODUCT", "IP" -> IndexParam.MetricType.IP;
            default -> IndexParam.MetricType.COSINE;
        };
    }

    /**
     * 刷新索引，使新插入的向量可被搜索。
     */
    public void release() {
        if (!released) {
            client.flush(FlushReq.builder()
                    .collectionNames(List.of(collectionName))
                    .build());
            released = true;
        }
    }

    @Override
    protected synchronized boolean doAdd(String id, float[] vector) {
        com.google.gson.JsonObject entity = new com.google.gson.JsonObject();
        entity.addProperty("id", id);
        entity.add("vector", gsonFloatArray(vector));
        InsertReq req = InsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(entity))
                .build();
        client.insert(req);
        released = false;
        return true;
    }

    @Override
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        if (!released) {
            release();
        }
        // 多取 3 倍候选，供自定义算法二次过滤
        int fetchK = topK * 3;
        SearchReq req = SearchReq.builder()
                .collectionName(collectionName)
                .data(List.of(new FloatVec(query)))
                .topK(fetchK)
                .metricType(algorithmName)
                .outputFields(List.of("vector"))
                .build();
        SearchResp resp = client.search(req);
        List<Vector> candidates = new ArrayList<>();
        for (List<SearchResp.SearchResult> hits : resp.getSearchResults()) {
            for (SearchResp.SearchResult hit : hits) {
                String id = String.valueOf(hit.getId());
                float[] vectorData = extractVector(hit.getEntity());
                Float score = hit.getScore();
                candidates.add(new Vector(id, vectorData, Map.of("score", score != null ? (double) score : 0.0)));
            }
        }
        // 使用自定义算法二次过滤
        return rerank(candidates, query, topK);
    }

    /**
     * 从 Milvus 返回的 entity 中提取向量数据。
     */
    private static float[] extractVector(Map<String, Object> entity) {
        if (entity == null) {
            return new float[0];
        }
        Object vec = entity.get("vector");
        if (vec instanceof List<?> list) {
            float[] result = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = ((Number) list.get(i)).floatValue();
            }
            return result;
        }
        return new float[0];
    }

    @Override
    public int size() {
        var resp = client.getCollectionStats(
                io.milvus.v2.service.collection.request.GetCollectionStatsReq.builder()
                        .collectionName(collectionName)
                        .build());
        return resp != null ? 1 : 0;
    }

    @Override
    public void clear() {
        client.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq.builder()
                .collectionName(collectionName)
                .build());
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }

    private static com.google.gson.JsonArray gsonFloatArray(float[] data) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (float v : data) {
            arr.add(v);
        }
        return arr;
    }
}
