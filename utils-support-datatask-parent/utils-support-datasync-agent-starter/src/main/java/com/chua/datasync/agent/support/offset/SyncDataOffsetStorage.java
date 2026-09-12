package com.chua.datasync.agent.support.offset;

import com.chua.datasync.agent.support.model.SyncDataOffset;

import java.util.List;

/**
 * 偏移量存储接口，定义偏移量的读取与持久化契约。
 * <p>
   * 源 通过此接口实现增量读取能力的偏移量持久化。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SyncDataOffsetStorage {

    /**
      * 读取指定 源 的偏移量。
     *
     * @param sourceId 源 标识
     * @param defaultValue 默认值（读取失败时返回）
     * @return 偏移量
     */
    SyncDataOffset read(String sourceId, SyncDataOffset defaultValue);

    /**
     * 写入偏移量。
     *
     * @param offset 偏移量
     */
    void write(SyncDataOffset offset);

    /**
      * 删除指定 源 的偏移量。
     *
     * @param sourceId 源 标识
     */
    void delete(String sourceId);

    /**
     * 列出所有偏移量。
     *
     * @return 偏移量列表
     */
    List<SyncDataOffset> listAll();
}