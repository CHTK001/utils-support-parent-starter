package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 备份事件
 *
 * <p>{@link BackupListener} 各回调的事件载荷，携带本次备份的类型、
 * 目标库、进度（总表数/已完成表数/已导出行数）与结果。
 *
 * @author CH
 * @since 2026/11/24
 */
@Data
@Builder(toBuilder = true)
public class BackupEvent {

    /**
     * 事件阶段：开始备份（表清单已确定，尚未导出）
     */
    public static final String PHASE_START = "START";
    /**
     * 事件阶段：单表导出完成
     */
    public static final String PHASE_TABLE = "TABLE";
    /**
     * 事件阶段：备份全部成功
     */
    public static final String PHASE_SUCCESS = "SUCCESS";
    /**
     * 事件阶段：备份失败
     */
    public static final String PHASE_FAILURE = "FAILURE";

    /**
     * 备份类型（database）
     */
    private String type;

    /**
     * 数据库名
     */
    private String dbName;

    /**
     * 备份根目录
     */
    private Path backupDir;

    /**
     * 事件时间
     */
    private LocalDateTime time;

    /**
     * 事件阶段（{@link #PHASE_START} 等）
     */
    private String phase;

    /**
     * 当前导出完成的表名（TABLE 阶段）
     */
    private String table;

    /**
     * 当前表导出行数（TABLE 阶段）
     */
    private long tableRows;

    /**
     * 已导出总行数
     */
    private long totalRows;

    /**
     * 导出的表总数
     */
    private int totalTables;

    /**
     * 已完成的表数
     */
    private int finishedTables;

    /**
     * 备份结果（SUCCESS/FAILURE 阶段）
     */
    private BackupResult result;

    /**
     * 错误信息（FAILURE 阶段）
     */
    private String errorMessage;
}
