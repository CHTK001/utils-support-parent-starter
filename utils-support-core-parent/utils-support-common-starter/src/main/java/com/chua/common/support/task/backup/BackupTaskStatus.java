package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时备份任务运行状态
 *
 * <p>由 {@link BackupTaskRegistry#status(String)} 返回，用于运维页面/接口
 * 展示某个定时备份任务的健康状况与最近一次执行结果。
 *
 * @author CH
 * @since 2026/11/24
 */
@Data
@Builder
public class BackupTaskStatus {

    /**
     * 任务名（注册时指定，唯一）
     */
    private String name;

    /**
     * 是否正在执行备份
     */
    private boolean running;

    /**
     * 最近一次备份开始时间（从未执行过为 null）
     */
    private LocalDateTime lastRunTime;

    /**
     * 最近一次备份结果（从未执行过为 null）
     */
    private BackupResult lastResult;

    /**
     * 最近一次失败原因（最近一次成功则为 null）
     */
    private String lastError;

    /**
     * 最近一次备份耗时（毫秒）
     */
    private long lastDurationMs;

    /**
     * 累计执行次数
     */
    private long totalRuns;

    /**
     * 累计失败次数
     */
    private long totalFailures;
}
