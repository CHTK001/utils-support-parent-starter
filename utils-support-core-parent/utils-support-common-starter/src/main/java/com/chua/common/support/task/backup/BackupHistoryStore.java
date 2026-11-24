package com.chua.common.support.task.backup;

import java.util.List;

/**
 * 数据库备份历史存储 SPI
 *
 * <p>宿主实现本接口并通过
 * {@link DatabaseBackupConfig#setHistoryStore(BackupHistoryStore)} 挂载，
 * 即可在每次备份成功/失败后收到 {@link BackupHistoryRecord} 审计记录。
 * 内置 {@link JsonlBackupHistoryStore}（JSONL 文件，位于备份根目录）。
 *
 * <p>实现要求：{@link #append} 与 {@link #list} 均需线程安全；
 * 存储故障只应记录告警，不允许中断备份流程（由调用方兜底）。
 *
 * @author CH
 * @since 2026/11/24
 */
public interface BackupHistoryStore {

    /**
     * 追加一条备份历史记录（成功与失败都应记录）。
     *
     * @param record 备份历史记录
     */
    void append(BackupHistoryRecord record);

    /**
     * 查询最近的备份历史（按开始时间倒序）。
     *
     * @param limit 最多返回条数（&le;0 时返回空列表）
     * @return 历史记录列表（不可变）
     */
    List<BackupHistoryRecord> list(int limit);
}
