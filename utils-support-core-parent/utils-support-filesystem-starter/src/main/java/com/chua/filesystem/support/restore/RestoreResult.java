package com.chua.filesystem.support.restore;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 数据恢复结果
 * <p>
 * 包含恢复操作的执行结果信息
 * </p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/12/25
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class RestoreResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 恢复的库名
     */
    private String schemaName;

    /**
     * 恢复的表名
     */
    private String tableName;

    /**
     * 恢复的行数
     */
    @Builder.Default
    private long rowCount = 0;

    /**
     * 执行耗时（毫秒）
     */
    @Builder.Default
    private long duration = 0;

    /**
     * 是否恢复了表结构
     */
    private boolean structureRestored;

    /**
     * 是否恢复了数据
     */
    private boolean dataRestored;

    /**
     * 执行的DDL语句
     */
    private String ddl;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 异常对象
     */
    private Throwable exception;

    /**
     * 警告信息列表
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * 创建成功结果
     */
    public static RestoreResult success(String schemaName, String tableName, long rowCount) {
        return RestoreResult.builder()
                .success(true)
                .schemaName(schemaName)
                .tableName(tableName)
                .rowCount(rowCount)
                .structureRestored(true)
                .dataRestored(true)
                .build();
    }

    /**
     * 创建结构恢复成功结果
     */
    public static RestoreResult structureSuccess(String schemaName, String tableName, String ddl) {
        return RestoreResult.builder()
                .success(true)
                .schemaName(schemaName)
                .tableName(tableName)
                .ddl(ddl)
                .structureRestored(true)
                .dataRestored(false)
                .build();
    }

    /**
     * 创建数据恢复成功结果
     */
    public static RestoreResult dataSuccess(String schemaName, String tableName, long rowCount) {
        return RestoreResult.builder()
                .success(true)
                .schemaName(schemaName)
                .tableName(tableName)
                .rowCount(rowCount)
                .structureRestored(false)
                .dataRestored(true)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static RestoreResult failure(String errorMessage) {
        return RestoreResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 创建失败结果（带异常）
     */
    public static RestoreResult failure(String errorMessage, Throwable exception) {
        return RestoreResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .exception(exception)
                .build();
    }

    /**
     * 添加警告信息
     */
    public RestoreResult addWarning(String warning) {
        if (this.warnings == null) {
            this.warnings = new ArrayList<>();
        }
        this.warnings.add(warning);
        return this;
    }

    /**
     * 是否有警告
     */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}
