package com.chua.lucene.support.engine;

import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lucene 引擎的 DataSync OUTPUT Provider。
 * <p>将 {@link Flux}&lt;Map&gt; 批量写入 Lucene 索引。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LuceneDataSyncSource implements DataSyncSource {

    /**
     * 默认批大小
     */
    private static final int DEFAULT_BATCH = 1000;

    /**
     * 底层 Lucene 引擎
     */
    private final LuceneEngine engine;

    /**
     * 索引表名
     */
    private final String tableName;

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
     * @param engine    Lucene 引擎
     * @param tableName 表名
     * @param sourceId  源标识
     * @param agentId   Agent 标识
     * @param batchSize 批大小
     */
    private LuceneDataSyncSource(LuceneEngine engine, String tableName,
                                 String sourceId, String agentId, int batchSize) {
        this.engine = engine;
        this.tableName = tableName;
        this.sourceId = sourceId;
        this.agentId = agentId;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH;
    }

    /**
     * 默认批大小创建。
     *
     * @param engine    Lucene 引擎
     * @param tableName 表名
     * @param sourceId  源标识
     * @param agentId   Agent 标识
     * @return 实例
     */
    public static LuceneDataSyncSource output(LuceneEngine engine, String tableName,
                                              String sourceId, String agentId) {
        return new LuceneDataSyncSource(engine, tableName, sourceId, agentId, DEFAULT_BATCH);
    }

    /**
     * 自定义批大小创建。
     *
     * @param engine    Lucene 引擎
     * @param tableName 表名
     * @param sourceId  源标识
     * @param agentId   Agent 标识
     * @param batchSize 批大小
     * @return 实例
     */
    public static LuceneDataSyncSource output(LuceneEngine engine, String tableName,
                                              String sourceId, String agentId, int batchSize) {
        return new LuceneDataSyncSource(engine, tableName, sourceId, agentId, batchSize);
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
        Directory directory = engine.getOrCreateDirectory(tableName);
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            List<Document> buffer = new ArrayList<>(batchSize);
            for (Map<String, Object> row : rows) {
                Document doc = new Document();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    LuceneEngine.addFieldToDoc(doc, entry.getKey(), entry.getValue());
                }
                buffer.add(doc);
                if (buffer.size() >= batchSize) {
                    for (Document d : buffer) {
                        writer.addDocument(d);
                    }
                    writer.commit();
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                for (Document d : buffer) {
                    writer.addDocument(d);
                }
                writer.commit();
            }
        } catch (IOException e) {
            throw new RuntimeException("LuceneDataSyncSource write failed: " + tableName, e);
        }
    }

    @Override
    public void close() {
    }
}
