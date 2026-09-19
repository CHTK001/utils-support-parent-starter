package com.chua.elasticsearch.support.engine;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Elasticsearch 引擎的 数据同步 输出 提供者。
 * <p>将 {@link Flux}&lt;Map&gt; 逐条写入 ES Index，使用文档内 id 保证幂等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EsDataSyncSource implements DataSyncSource {

    /**
     * 默认批大小
     */
    private static final int DEFAULT_BATCH = 1000;

    /**
     * 标识 字段名
     */
    private static final String ID = "id";

    /**
     * 底层 ES 引擎
     */
    private final ElasticsearchEngine engine;

    /**
     * 目标索引名
     */
    private final String indexName;

    /**
     * 源标识
     */
    private final String sourceId;

    /**
     * Agent 标识
     */
    private final String agentId;

    /**
     * 批大小
     */
    private final int batchSize;

    /**
     * 私有构造。
     *
     * @param engine    ES 引擎
     * @param indexName 索引名
     * @param sourceId  源标识
     * @param agentId   Agent 标识
     * @param batchSize 批大小
     */
    private EsDataSyncSource(ElasticsearchEngine engine, String indexName,
                             String sourceId, String agentId, int batchSize) {
        this.engine = engine;
        this.indexName = indexName;
        this.sourceId = sourceId;
        this.agentId = agentId;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH;
    }

    /**
     * 默认批大小创建。
     *
     * @param engine    ES 引擎
     * @param indexName 索引名
     * @param sourceId  源标识
     * @param agentId   Agent 标识
     * @return 实例
     */
    public static EsDataSyncSource output(ElasticsearchEngine engine, String indexName,
                                          String sourceId, String agentId) {
        return new EsDataSyncSource(engine, indexName, sourceId, agentId, DEFAULT_BATCH);
    }

    /**
     * 自定义批大小创建。
     *
     * @param engine    ES 引擎
     * @param indexName 索引名
     * @param sourceId  源标识
     * @param agentId   Agent 标识
     * @param batchSize 批大小
     * @return 实例
     */
    public static EsDataSyncSource output(ElasticsearchEngine engine, String indexName,
                                          String sourceId, String agentId, int batchSize) {
        return new EsDataSyncSource(engine, indexName, sourceId, agentId, batchSize);
    }

    @Override
    /** Direction */
    public Direction direction() {
        return Direction.OUTPUT;
    }

    @Override
    /** 源id */
    public String sourceId() {
        return sourceId;
    }

    @Override
    /** Agentid */
    public String agentId() {
        return agentId;
    }

    @Override
    /** 读取 */
    public Flux<Map<String, Object>> read(SyncDataOffset offset, Map<String, Object> params) {
        return Flux.empty();
    }

    @Override
    /** 当前偏移量 */
    public SyncDataOffset currentOffset() {
        return null;
    }

    @Override
    /** 写入 */
    public void write(Flux<Map<String, Object>> data) {
        List<Map<String, Object>> rows = data.collectList().block();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ElasticsearchClient client = engine.getClient();
        if (client == null) {
            log.warn("[elasticsearch-datasource] 客户端未初始化，跳过写入: {}", indexName);
            return;
        }
        try {
            for (Map<String, Object> row : rows) {
                String id = row.containsKey(ID) ? String.valueOf(row.get(ID)) : null;
                client.index(i -> i
                        .index(indexName)
                        .id(id != null ? id : UUID.randomUUID().toString())
                        .document(row)
                );
            }
        } catch (Exception e) {
            throw new RuntimeException("EsDataSyncSource write failed: " + indexName, e);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
    }
}
