package com.chua.elasticsearch.support.engine;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Elasticsearch 引擎的 DataSync OUTPUT Provider。
 * <p>将 {@link Flux}&lt;Map&gt; 逐条写入 ES Index，使用文档内 id 保证幂等。</p>
 *
 * @author CH
 * @since 2026-07-22
 */
@Slf4j
public class EsDataSyncSource implements DataSyncSource {

    private static final int DEFAULT_BATCH = 1000;

    private final ElasticsearchEngine engine;
    private final String indexName;
    private final String sourceId;
    private final String agentId;
    private final int batchSize;

    private static final String ID = "id";

    private EsDataSyncSource(ElasticsearchEngine engine, String indexName,
                             String sourceId, String agentId, int batchSize) {
        this.engine = engine;
        this.indexName = indexName;
        this.sourceId = sourceId;
        this.agentId = agentId;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH;
    }

    public static EsDataSyncSource output(ElasticsearchEngine engine, String indexName,
                                          String sourceId, String agentId) {
        return new EsDataSyncSource(engine, indexName, sourceId, agentId, DEFAULT_BATCH);
    }

    public static EsDataSyncSource output(ElasticsearchEngine engine, String indexName,
                                          String sourceId, String agentId, int batchSize) {
        return new EsDataSyncSource(engine, indexName, sourceId, agentId, batchSize);
    }

    @Override
    public Direction direction() {
        return Direction.OUTPUT;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public Flux<Map<String, Object>> read(SyncDataOffset offset, Map<String, Object> params) {
        return Flux.empty();
    }

    @Override
    public SyncDataOffset currentOffset() {
        return null;
    }

    @Override
    public void write(Flux<Map<String, Object>> data) {
        List<Map<String, Object>> rows = data.collectList().block();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        ElasticsearchClient client = engine.getClient();
        if (client == null) {
            log.warn("ES 客户端未初始化，跳过 write: {}", indexName);
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
    public void close() {
    }
}
