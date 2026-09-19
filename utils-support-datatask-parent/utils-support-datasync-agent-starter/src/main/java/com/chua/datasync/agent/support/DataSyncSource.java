package com.chua.datasync.agent.support;

import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 数据同步 源，提供按偏移量读、写及方向信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DataSyncSource {

    /**
     * 输入标识
    */
    String INPUT_ID = "sourceId";

    /**
     * 获取方向。
     *
     * @return 方向
     */
    Direction direction();

    /**
     * 获取 源 实例唯一标识。
     *
     * @return Source 标识
     */
    String sourceId();

    /**
     * 获取 Agent 唯一标识。
     *
     * @return Agent 标识
     */
    String agentId();

    /**
     * 读取数据流。
     *
     * @param offset 当前偏移量（无则传 空，首次运行或全量读取）
     * @param params 读取参数
     * @return 数据流
     */
    Flux<Map<String, Object>> read(SyncDataOffset offset, Map<String, Object> params);

    /**
     * 获取当前偏移量（本次读取最后一条数据的偏移量）。
     *
     * @return 当前偏移量
     */
    SyncDataOffset currentOffset();

    /**
     * 写入数据流。
     *
     * @param data 数据流
     */
    void write(Flux<Map<String, Object>> data);

    /**
     * 关闭 源，释放资源。
     */
    void close();
}
