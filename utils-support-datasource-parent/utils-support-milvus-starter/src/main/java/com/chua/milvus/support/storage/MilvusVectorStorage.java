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
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import com.chua.common.support.utils.CollectionUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Milvus 向量数据库的 {@link AbstractVectorStorage} 实现。
 *
 * <p>通过 MilvusClientV2 SDK 连接远程 Milvus 服务，使用 collection 存储向量。
 * 构造函数自动创建 集合，写入数据后需调用 {@link #release()} 刷新索引，
 * 搜索前需确保 集合 已加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MilvusVectorStorage extends AbstractVectorStorage {

    /**
     * Milvus 客户端
     */
    private final MilvusClientV2 client;

    /**
     * 集合 名称
     */
    private final String collectionName;

    /**
     * 距离度量类型
     */
    private final IndexParam.MetricType algorithmName;

    /**
     * 认证令牌
     */
    private final String token;

    /**
     * 是否已释放资源
     */
    private boolean released;

    /**
     * 构造 Milvus 向量存储。
     *
     * <p>初始化连接、创建 collection、加载 collection。</p>
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param host       Milvus 服务地址（支持完整 URI，如 https://...）
     * @param port       Milvus 服务端口（仅当 主机 不含协议时使用）
     * @param collection 集合名称
     * @param token      认证令牌（可选）
     */
    public MilvusVectorStorage(int dimension, VectorCompareAlgorithm algorithm,
                               String host, int port, String collection, String token) {
        super(dimension, algorithm);
        this.collectionName = collection != null ? collection : "vector_store";
        this.algorithmName = toMilvusMetricType(algorithm);
        this.token = token;

        String uri = host.contains("://") ? host : "http://" + host + ":" + port;
        ConnectConfig config = ConnectConfig.builder()
                .uri(uri)
                .token(token)
                .build();
        this.client = new MilvusClientV2(config);

        initCollection();
    }

    /**
     * 初始化 Milvus 集合。
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
                    .idType(io.milvus.v2.common.DataType.VarChar)
                    .maxLength(64)
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
     * @param algo algo
     * @return 转为milvus指标类型的结果
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
    /** 执行添加 */
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

    /**
    * 删除指定 标识 的向量。
    *
    * <p>通过 Milvus {@code delete} 接口按主键 id 删除，删除后置空已刷新标记，
    * 下次搜索前自动重新 flush。</p>
    *
    * @param id 向量标识
    * @return 是否删除成功（id 不存在时返回 false）
    */
    @Override
    public synchronized boolean remove(String id) {
        checkNotClosed();
        DeleteResp resp = client.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .ids(List.of(id))
                .build());
        long deleted = resp != null ? resp.getDeleteCnt() : 0L;
        if (deleted > 0) {
            released = false;
        }
        return deleted > 0;
    }

    /**
     * 更新指定 标识 的向量数据。
     *
     * <p>先查询确认 id 存在，再通过 Milvus {@code upsert} 覆盖写入（主键相同即更新）。
     * 维度不匹配时抛出 {@link IllegalArgumentException}，标识 不存在时返回 false。</p>
     *
     * @param id     向量标识
     * @param vector 新的向量数据
     * @return 是否更新成功（id 不存在时返回 false）
     */
    @Override
    public synchronized boolean update(String id, float[] vector) {
        checkNotClosed();
        if (vector.length != dimension()) {
            throw new IllegalArgumentException(
                    "维度不匹配: 期望 " + dimension() + ", 实际 " + vector.length);
        }
        QueryResp query = client.query(QueryReq.builder()
                .collectionName(collectionName)
                .ids(List.of(id))
                .build());
        if (query == null || CollectionUtils.isEmpty(query.getQueryResults())) {
            return false;
        }
        com.google.gson.JsonObject entity = new com.google.gson.JsonObject();
        entity.addProperty("id", id);
        entity.add("vector", gsonFloatArray(vector));
        client.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(entity))
                .build());
        released = false;
        return true;
    }

    @Override
    /** 执行搜索 */
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        if (!released) {
            release();
        }
        // 多取 5 倍候选，供自定义算法二次过滤
        int fetchK = topK * 5;
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
     * 从 Milvus 返回的 实体 中提取向量数据。
     * @param entity 实体
     * @return extract向量的结果
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
    /** 获取大小 */
    public int size() {
        var resp = client.getCollectionStats(
                io.milvus.v2.service.collection.request.GetCollectionStatsReq.builder()
                        .collectionName(collectionName)
                        .build());
        if (resp == null) {
            return 0;
        }
        Long num = resp.getNumOfEntities();
        return num != null ? num.intValue() : 0;
    }

    @Override
    /** Clear */
    public void clear() {
        client.dropCollection(io.milvus.v2.service.collection.request.DropCollectionReq.builder()
                .collectionName(collectionName)
                .build());
    }

    @Override
    /** 关闭 */
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }

    /**
    * gsonfloatarray
    *
    * @param data 数据
    * @return gsonFloatArray的结果
    */
    private static com.google.gson.JsonArray gsonFloatArray(float[] data) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (float v : data) {
            arr.add(v);
        }
        return arr;
    }
}
