package com.chua.filesystem.support.restore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据恢复设置
 * <p>
 * 定义恢复操作的各种配置参数
 * </p>
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestoreSetting {

    /**
     * 恢复类型
     */
    @Builder.Default
    private RestoreType restoreType = RestoreType.ALL;

    /**
     * 目标库名（可选）
     */
    private String targetSchema;

    /**
     * 目标表名（可选）
     */
    private String targetTable;

    /**
     * 是否在恢复前删除已存在的表
     */
    @Builder.Default
    private boolean dropIfExists = false;

    /**
     * 是否在恢复前清空表数据
     */
    @Builder.Default
    private boolean truncateBeforeRestore = false;

    /**
     * 批量插入大小
     */
    @Builder.Default
    private int batchSize = 1000;

    /**
     * 是否使用事务
     */
    @Builder.Default
    private boolean useTransaction = true;

    /**
     * 是否忽略错误继续执行
     */
    @Builder.Default
    private boolean continueOnError = false;

    /**
     * 是否使用LOAD DATA快速导入（仅MySQL支持）
     */
    @Builder.Default
    private boolean useLoadData = true;

    /**
     * 临时文件目录
     */
    private String tempDirectory;

    /**
     * 超时时间（秒）
     */
    @Builder.Default
    private int timeout = 3600;

    /**
     * 是否为MySQL 5.7版本（IBD文件解析）
     */
    @Builder.Default
    private boolean mysql5 = false;

    /**
     * keyring文件路径（加密IBD文件）
     */
    private String keyringFile;

    /**
     * 是否强制解析（忽略错误页）
     */
    @Builder.Default
    private boolean force = false;

    /**
     * 字符编码
     */
    @Builder.Default
    private String charset = "UTF-8";

    /**
     * 恢复类型枚举
     */
    public enum RestoreType {
        /**
         * 仅恢复表结构（DDL）
         */
        STRUCTURE,

        /**
         * 仅恢复数据
         */
        DATA,

        /**
         * 恢复表结构和数据
         */
        ALL
    }
}