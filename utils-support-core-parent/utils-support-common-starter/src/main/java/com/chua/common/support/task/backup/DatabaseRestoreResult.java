package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库恢复结果
 *
 * @author CH
 * @since 2026/11/24
 */
@Data
@Builder
public class DatabaseRestoreResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 恢复来源脚本（zip 归档时为解压后的首个脚本）
     */
    private Path scriptFile;

    /**
     * 已执行（或 dryRun 时解析到）的语句数
     */
    private int statements;

    /**
     * 因失败被跳过的语句数（continueOnError = true 时可能大于 0）
     */
    private int skippedStatements;

    /**
     * dropBeforeCreate 执行的 DROP TABLE 数
     */
    private int droppedTables;

    /**
     * 耗时（毫秒）
     */
    private long durationMs;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 逐条错误（continueOnError = true 时收集，最多保留全部）
     */
    @Builder.Default
    private List<String> errors = new ArrayList<>();

    /**
     * 构造成功结果。
     */
    public static DatabaseRestoreResult success(Path scriptFile, int statements, int skippedStatements,
                                                int droppedTables, long durationMs) {
        return DatabaseRestoreResult.builder()
                .success(true)
                .scriptFile(scriptFile)
                .statements(statements)
                .skippedStatements(skippedStatements)
                .droppedTables(droppedTables)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 构造失败结果。
     */
    public static DatabaseRestoreResult failure(String errorMessage) {
        return DatabaseRestoreResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
