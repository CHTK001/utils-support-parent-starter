package com.chua.common.support.ai.memory;

import java.util.List;

/**
* 记忆存储接口
*
* <p>定义记忆条目的持久化操作，支持保存、搜索、删除和备份。
* 默认实现为 {@code FileMemoryStore}（基于工作间文件），可通过 SPI 替换为数据库等实现。
*
* @author CH
* @since 2026/07/16
 */
public interface MemoryStore extends AutoCloseable {

    /**
    * 保存一条记忆
    *
    * @param entry 记忆条目
     */
    void save(MemoryEntry entry);

    /**
    * 按关键词搜索记忆
    *
    * <p>在记忆内容中进行模糊匹配，返回相关度最高的记忆列表。
    *
    * @param keyword 搜索关键词
    * @param limit   最大返回数量
    * @return 匹配的记忆列表，按相关度降序
     */
    List<MemoryEntry> search(String keyword, int limit);

    /**
    * 按类型检索记忆
    *
    * @param type  记忆类型
    * @param limit 最大返回数量
    * @return 该类型的记忆列表
     */
    List<MemoryEntry> listByType(String type, int limit);

    /**
    * 按会话 ID 获取记忆
    *
    * @param sessionId 会话 ID
    * @return 该会话产生的记忆列表
     */
    List<MemoryEntry> listBySession(String sessionId);

    /**
    * 删除指定记忆
    *
    * @param id 记忆 ID
    * @return 是否删除成功
     */
    boolean delete(String id);

    /**
    * 获取记忆总数
    *
    * @return 当前存储的记忆条数
     */
    int count();

    /**
    * 备份所有记忆到指定路径
    *
    * @param backupPath 备份目标路径
     */
    void backup(String backupPath);

    /**
    * 从备份恢复记忆
    *
    * @param backupPath 备份文件路径
     */
    void restore(String backupPath);

    @Override
    /** 关闭 */
    default void close() {
    }
}
