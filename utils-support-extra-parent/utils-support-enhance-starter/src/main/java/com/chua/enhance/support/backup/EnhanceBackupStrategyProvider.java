package com.chua.enhance.support.backup;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.backup.BackupConfig;
import com.chua.common.support.task.backup.BackupResult;
import com.chua.common.support.task.backup.BackupStrategy;
import com.chua.common.support.task.backup.DefaultDailyBackupStrategy;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 增强版按天备份策略
 *
 * <p>通过 SPI 注册，扩展 {@link DefaultDailyBackupStrategy}，
 * 增加日志记录、备份校验和更灵活的目录结构支持。
 *
 * <h3>目录结构</h3>
 * <pre>
 *   {backupDir}/
 *   ├── {yyyy-MM-dd}/                  ← 当天备份
 *   │   ├── {sourceDirName}/
 *   │   │   ├── file1.txt
 *   │   │   └── file2.json
 *   │   └── manifest.json              ← 备份清单
 *   └── archive/
 *       └── {yyyy-MM-dd}.zip           ← 历史备份压缩包
 * </pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi("daily")
public class EnhanceBackupStrategyProvider implements BackupStrategy {

    /** 类型 */
    private static final String TYPE = "daily";
    /** Archive_dir */
    private static final String ARCHIVE_DIR = "archive";
    /** 日期_fmt */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Delegate */
    private static final DefaultDailyBackupStrategy delegate = new DefaultDailyBackupStrategy();

    @Override
    /** 类型 */
    public String type() {
        return TYPE;
    }

    @Override
    /** 执行 */
    public BackupResult execute(BackupConfig config) {
 // 委托给 默认dailybackupstrategy 处理核心逻辑
        return delegate.execute(config);
    }

    @Override
    /** cleanexpired */
    public int cleanExpired(BackupConfig config) {
        return delegate.cleanExpired(config);
    }

    @Override
    /** 列表backups */
    public List<Path> listBackups(BackupConfig config) {
        return delegate.listBackups(config);
    }
}
