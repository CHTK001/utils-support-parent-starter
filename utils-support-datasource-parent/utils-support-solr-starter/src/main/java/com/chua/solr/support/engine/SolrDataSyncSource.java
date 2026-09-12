package com.chua.solr.support.engine;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.common.SolrInputDocument;
import reactor.core.publisher.Flux;

import java.util.*;

/**
* Solr 引擎的 数据同步 输出 提供者。
* <p>将 {@link Flux}&lt;Map&gt; 批量写入 Solr Collection。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class SolrDataSyncSource implements DataSyncSource {

    /** 默认_批量 */
    private static final int DEFAULT_BATCH = 1000;

    /** 引擎 */
    private final SolrEngine engine;
    /** 集合名称 */
    private final String collectionName;
    /** 来源标识 */
    private final String sourceId;
    /** 智能体id */
    private final String agentId;
    /** 批量尺寸 */
    private final int batchSize;

    /**
    * 创建 Solr数据同步源 实例
    * @param engine engine
    * @param collectionName 集合名称
    * @param sourceId 源标识
    * @param agentId 智能体标识
    * @param batchSize 批量大小
     */
    private SolrDataSyncSource(SolrEngine engine, String collectionName,
                               String sourceId, String agentId, int batchSize) {
        this.engine = engine;
        this.collectionName = collectionName;
        this.sourceId = sourceId;
        this.agentId = agentId;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH;
    }

    /**
    * 输出
    * @param engine engine
    * @param collectionName 集合名称
    * @param sourceId 源标识
    * @param agentId 智能体标识
     */
    public static SolrDataSyncSource output(SolrEngine engine, String collectionName,
                                            String sourceId, String agentId) {
        return new SolrDataSyncSource(engine, collectionName, sourceId, agentId, DEFAULT_BATCH);
    }

    /**
    * 输出
    * @param engine engine
    * @param collectionName 集合名称
    * @param sourceId 源标识
    * @param agentId 智能体标识
    * @param batchSize 批量大小
     */
    public static SolrDataSyncSource output(SolrEngine engine, String collectionName,
                                            String sourceId, String agentId, int batchSize) {
        return new SolrDataSyncSource(engine, collectionName, sourceId, agentId, batchSize);
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
    /** 智能体id */
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
    /** 关闭 */
    public void close() {
    }
}
