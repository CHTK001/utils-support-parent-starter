package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 数据库恢复事件
 *
 * <p>{@link RestoreListener} 各回调的事件载荷，携带本次恢复的进度
 * （脚本数/已完成脚本数/已处理与已执行语句数）、校验和开关与结果。
 *
 * @author CH
 * @since 2026/11/24
 */
@Data
@Builder
public class RestoreEvent {

    /**
     * 事件阶段：恢复开始（脚本清单与校验和已就绪，尚未执行）
     */
    public static final String PHASE_START = "START";
    /**
     * 事件阶段：恢复进行中（按语句批次/脚本完成粒度推进）
     */
    public static final String PHASE_PROGRESS = "PROGRESS";
    /**
     * 事件阶段：恢复全部成功
     */
    public static final String PHASE_SUCCESS = "SUCCESS";
    /**
     * 事件阶段：恢复失败
     */
    public static final String PHASE_FAILURE = "FAILURE";

    /**
     * 事件时间
     */
    private LocalDateTime time;

    /**
     * 事件阶段（{@link #PHASE_START} 等）
     */
    private String phase;

    /**
     * 当前正在恢复（或刚完成）的脚本文件
     */
    private Path currentFile;

    /**
     * 脚本总数
     */
    private int filesTotal;

    /**
     * 已完成的脚本数
     */
    private int filesDone;

    /**
     * 已处理的语句数（含 dryRun 解析与失败跳过）
     */
    private long statementsProcessed;

    /**
     * 已成功执行的语句数
     */
    private long statementsExecuted;

    /**
     * 失败后跳过的语句数（continueOnError = true 时可能大于 0）
     */
    private long statementsSkipped;

    /**
     * 是否试运行（只解析不执行）
     */
    private boolean dryRun;

    /**
     * 是否启用恢复前 SHA-256 校验和验证
     */
    private boolean checksumVerified;

    /**
     * 附加说明（当前动作描述）
     */
    private String message;

    /**
     * 错误信息（FAILURE 阶段）
     */
    private String errorMessage;

    /**
     * 恢复结果（SUCCESS/FAILURE 阶段）
     */
    private DatabaseRestoreResult result;
}
