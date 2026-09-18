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
* @版本 1.0.0
* @since 4.0.0.42
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
    /** 行数量 */
    private long rowCount = 0;

    /**
    * 执行耗时（毫秒）
    */
    @Builder.Default
    /** 持续时间 */
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
    /** 警告 */
    private List<String> warnings = new ArrayList<>();

    /**
    * 创建成功结果
    * @param schemaName 模式名称
    * @param tableName table名称
    * @param rowCount row数量
    * @return 成功的结果
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
    * @param schemaName 模式名称
    * @param tableName table名称
    * @param ddl ddl
    * @return 结构成功的结果
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
    * @param schemaName 模式名称
    * @param tableName table名称
    * @param rowCount row数量
    * @return 数据成功的结果
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
    * @param errorMessage 错误消息
    * @return 失败的结果
    */
    public static RestoreResult failure(String errorMessage) {
        return RestoreResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
    * 创建失败结果（带异常）
    * @param errorMessage 错误消息
    * @param exception 异常
    * @return 失败的结果
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
    * @param warning 警告
    * @return 添加警告的结果
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
    * @return 是否包含警告的结果
    */
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}
