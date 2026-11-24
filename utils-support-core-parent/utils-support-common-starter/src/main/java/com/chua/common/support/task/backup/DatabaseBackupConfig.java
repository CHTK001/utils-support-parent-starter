package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据库备份配置
 *
 * <p>「当前数据库」通过标准 JDBC 连接描述：宿主将自身数据源的连接信息填入本配置，
 * 即可对当前数据库执行逻辑备份（导出建表语句 + 全量数据 INSERT 语句为 SQL 脚本）。
 * 不依赖 Spring DataSource，也不依赖数据库原生 dump 工具。
 *
 * @author CH
 * @since 2026/11/24
 */
@Data
@Builder
public class DatabaseBackupConfig {

    // ==================== 数据库连接 ====================

    /**
     * JDBC 连接串，如 jdbc:mysql://127.0.0.1:3306/monitordb
     */
    private String jdbcUrl;

    /**
     * 数据库用户名
     */
    private String username;

    /**
     * 数据库密码
     */
    private String password;

    /**
     * 驱动类名（可选；JDBC 4+ 通常可由 jdbcUrl 自动发现驱动，无需填写）
     */
    private String driverClass;

    /**
     * 限定导出的 schema（可选；为空时使用连接默认 schema）
     */
    private String schema;

    // ==================== 备份范围 ====================

    /**
     * 仅备份这些表（可选；为空表示备份全部表）
     */
    @Builder.Default
    private Set<String> includeTables = Set.of();

    /**
     * 排除这些表（可选）
     */
    @Builder.Default
    private Set<String> excludeTables = Set.of();

    /**
     * 数据导出批次大小（每 N 行 flush 一次到磁盘）
     */
    @Builder.Default
    private int batchSize = 1000;

    // ==================== 输出与保留 ====================

    /**
     * 备份根目录（目录结构与文件备份一致：{backupDir}/{yyyy-MM-dd}/）
     */
    private Path backupDir;

    /**
     * 保留天数，超过则清理历史备份，默认 30 天
     */
    @Builder.Default
    private int retentionDays = 30;

    /**
     * 是否将昨日备份目录归档压缩为 ZIP，默认开启
     */
    @Builder.Default
    private boolean compressArchives = true;

    // ==================== 远端上传（可选） ====================

    /**
     * 远端上传配置（可选）
     *
     * <p>配置后备份文件将通过 {@link BackupUploader} SPI 上传到远端服务器。
     * SFTP 协议依赖 <b>utils-support-ssh-starter</b> 模块
     * （见 {@link BackupRemoteConfig}）；未引入该模块时上传步骤跳过并告警，
     * 本地备份不受影响。
     */
    private BackupRemoteConfig remote;

    // ==================== 事件监听（可选） ====================

    /**
     * 备份事件监听器（可选）
     *
     * <p>在备份开始、单表导出完成、成功、失败时收到 {@link BackupEvent} 通知；
     * 监听器抛出的异常只记录告警，不会中断备份流程。
     */
    @Builder.Default
    private List<BackupListener> listeners = new ArrayList<>();
}
