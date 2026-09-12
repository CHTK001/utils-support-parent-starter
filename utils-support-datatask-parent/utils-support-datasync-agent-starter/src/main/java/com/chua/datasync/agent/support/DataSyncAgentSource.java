package com.chua.datasync.agent.support;

import com.chua.datasync.agent.support.model.SyncDataOffset;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
* 数据同步 源 接口，定义数据读取能力。
*
* @author CH
* @since 4.0.0.42
 */
public interface DataSyncAgentSource {

    /**
    * 获取 源 实例唯一标识。
    *
    * @return Source 实例 标识
     */
    String sourceId();

    /**
    * 获取输入标识，用于 Mapping 绑定。
    *
    * @return 输入标识
     */
    String inputId();

    /**
    * 读取数据流。
    *
    * @param params 读取参数
    * @return 数据流
     */
    Flux<Map<String, Object>> read(Map<String, Object> params);

    /**
    * 读取偏移量（由 源 通过存储接口自行恢复）。
    *
    * @param params 读取参数
    * @return 偏移量，可能为空（首次运行时）
     */
    default SyncDataOffset readOffset(Map<String, Object> params) {
        return null;
    }

    /**
    * 写入偏移量（由 源 通过存储接口自行持久化）。
    *
    * @param offset 偏移量对象
     */
    default void writeOffset(SyncDataOffset offset) {
    }

    /**
    * 获取本次读取的最后偏移量（由 源 在 读取() 内部追踪）。
    *
    * @return 最后偏移量值，若不可追踪则返回 空
     */
    default Object getLastReadOffset() {
        return null;
    }

    /**
    * 关闭 源，释放资源。
     */
    void close();
}