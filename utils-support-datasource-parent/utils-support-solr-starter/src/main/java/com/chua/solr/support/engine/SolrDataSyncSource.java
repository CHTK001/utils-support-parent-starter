package com.chua.solr.support.engine;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.common.SolrInputDocument;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Solr 引擎的 DataSync OUTPUT Provider。
 * <p>将 {@link Flux}&lt;Map&gt; 批量写入 Solr Collection。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SolrDataSyncSource implements DataSyncSource {

    /** Default_batch */
    private static final int DEFAULT_BATCH = 1000;

    /** 引擎 */
    private final SolrEngine engine;
    /** Collection名称 */
    private final String collectionName;
    /** 来源ID */
    private final String sourceId;
    /** AgentID */
    private final String agentId;
    /** Batch尺寸 */
    private final int batchSize;

    private SolrDataSyncSource(SolrEngine engine, String collectionName,
                               String sourceId, String agentId, int batchSize) {
        this.engine = engine;
        this.collectionName = collectionName;
        this.sourceId = sourceId;
        this.agentId = agentId;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH;
    }

    public static SolrDataSyncSource output(SolrEngine engine, String collectionName,
                                            String sourceId, String agentId) {
        return new SolrDataSyncSource(engine, collectionName, sourceId, agentId, DEFAULT_BATCH);
    }

    public static SolrDataSyncSource output(SolrEngine engine, String collectionName,
                                            String sourceId, String agentId, int batchSize) {
        return new SolrDataSyncSource(engine, collectionName, sourceId, agentId, batchSize);
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
        org.apache.solr.client.solrj.SolrClient sc = engine.getClient();
        if (sc == null) {
            log.warn("Solr 客户端未初始化，跳过 write: {}", collectionName);
            return;
        }
        try {
            int count = 0;
            for (Map<String, Object> row : rows) {
                SolrInputDocument doc = new SolrInputDocument();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    doc.addField(entry.getKey(), entry.getValue());
                }
                if (!row.containsKey(SolrFields.ID)) {
                    doc.addField(SolrFields.ID, UUID.randomUUID().toString());
                }
                sc.add(collectionName, doc);
                count++;
                if (count >= batchSize) {
                    sc.commit(collectionName);
                    count = 0;
                }
            }
            if (count > 0) {
                sc.commit(collectionName);
            }
        } catch (Exception e) {
            throw new RuntimeException("SolrDataSyncSource write failed: " + collectionName, e);
        }
    }

    @Override
    public void close() {
    }
}
