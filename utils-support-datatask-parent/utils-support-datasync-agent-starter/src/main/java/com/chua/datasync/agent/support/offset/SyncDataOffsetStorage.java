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
     * 读取指定 源 + 映射 的偏移量（**每目标独立水位**）。
     *
     * <p>一个源可以写多个目标（多目标同步），各目标的水位必须互相独立：
     * A 表写失败不能把 B 表的水位推上去，否则 B 永远补不回那批数据。
     * 因此水位键是「源 + 映射」，而不是只有源。</p>
     *
     * <p>默认实现退回按源存储，保持既有实现兼容；支持按映射隔离的存储
     * （如文件存储）应覆盖本方法。</p>
     *
     * @param sourceId  源 标识
     * @param mappingId 映射 标识（多目标时一个目标一个映射）
     * @param defaultValue 默认值（读取失败时返回）
     * @return 偏移量
     */
    default SyncDataOffset read(String sourceId, String mappingId, SyncDataOffset defaultValue) {
        return read(sourceId, defaultValue);
    }

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
