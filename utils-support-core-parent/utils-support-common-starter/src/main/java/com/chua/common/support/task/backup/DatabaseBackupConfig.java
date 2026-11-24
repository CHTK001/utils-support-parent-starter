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

    /**
     * ResultSet 抓取批次大小（{@code Statement#setFetchSize}），用于大表流式导出。
     * 默认 0 表示不设置（使用驱动默认行为）；MySQL 真流式需设为 {@link Integer#MIN_VALUE}，
     * 其他数据库建议 100~10000。正负值都会原样传给驱动。
     */
    @Builder.Default
    private int fetchSize = 0;

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

    /**
     * 是否在备份目录写入 {@link BackupManifest#MANIFEST_FILE_NAME}（记录每个 SQL 脚本的
     * 字节数与 SHA-256 校验和），供恢复侧 {@code verifyChecksum} 恢复前校验，默认开启。
     */
    @Builder.Default
    private boolean writeManifest = true;

    /**
     * 一致性快照导出：备份期间使用单连接事务（REPEATABLE_READ，不支持时回退
     * SERIALIZABLE）包住全表导出，保证多表数据取自同一快照，避免导出中途
     * 其他事务写入导致跨表数据不一致。默认开启。
     */
    @Builder.Default
    private boolean consistentSnapshot = true;

    /**
     * 最大备份份数：按 {@code database-*} 脚本计数（含 archive 内压缩备份），
     * 超出后从最旧开始清理；只清理本策略命名空间（database-* 脚本、对应
     * manifest 与清空后的日期目录），不触碰其他文件。0 表示不按份数清理，默认 0。
     */
    @Builder.Default
    private int maxBackupCount = 0;

    /**
     * 最大保留天数：超过该天数的 {@code database-*} 备份被清理（仅清理本策略
     * 命名空间）。&gt;0 时优先于 {@code retentionDays}；0 表示沿用 {@code retentionDays}。
     */
    @Builder.Default
    private int maxAgeDays = 0;

    /**
     * 备份历史存储（可选）：每次备份成功/失败后追加一条 {@link BackupHistoryRecord}；
     * 未配置时默认使用内置 {@link JsonlBackupHistoryStore}（JSONL 文件，位于备份根目录）。
     */
    private BackupHistoryStore historyStore;

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
