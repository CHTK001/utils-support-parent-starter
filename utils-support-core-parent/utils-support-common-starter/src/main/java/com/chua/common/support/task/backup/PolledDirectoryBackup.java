package com.chua.common.support.task.backup;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 轮询目录备份实现
 *
 * <p>专门用于备份轮询目录中已处理的记录（deleted → insert）。
 * 当轮询目录中的文件被标记为删除（或移动到归档目录）时，
 * 本策略将这些记录备份为 插入 格式的文件，便于审计和恢复。
 *
 * <h3>工作流程</h3>
 * <pre>
 *   轮询目录中的文件（已被处理/删除）
 *       ↓ 读取内容
 *   转换为 insert 格式记录
 *       ↓ 写入
 *   备份目录/{yyyy-MM-dd}/{sourceName}/{filename}.insert.json
 * </pre>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>消息队列消费后的消息备份</li>
 *   <li>文件处理后的源文件归档</li>
 *   <li>数据库轮询删除后的记录备份</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public class PolledDirectoryBackup implements BackupStrategy {

    /**
     * 类型
     */
    private static final String TYPE = "polled";
    /**
     * 日期_fmt
    */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /**
     * 时间_fmt
    */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 记录转换器
     *
     * <p>将轮询目录中的文件内容转换为 insert 格式记录。
     * @author CH
     * @since 4.0.0
     */
    @FunctionalInterface
    public interface RecordTransformer {

        /**
         * 转换文件内容为 插入 记录
         *
         * @param fileName 文件名
         * @param content  文件内容
         * @return insert 格式记录，返回 空 表示跳过
         */
        String transform(String fileName, String content);
    }

    /**
     * 默认转换器：包装为 JSON 插入 格式
    */
    private static final RecordTransformer DEFAULT_TRANSFORMER = (fileName, content) -> {
        return "{\"type\":\"insert\",\"source\":\"" + fileName + "\","
                + "\"timestamp\":\"" + LocalDateTime.now().format(TIME_FMT) + "\","
                + "\"data\":" + content + "}";
    };

    /**
     * Transformer
    */
    private RecordTransformer transformer = DEFAULT_TRANSFORMER;

    /**
     * 创建 polled目录backup 实例
     *
     * @return polled目录backup的结果
     */
    public PolledDirectoryBackup() {
    }

    /**
     * 创建 polled目录backup 实例
     * @param transformer transformer
     * @return polled目录backup的结果
     */
    public PolledDirectoryBackup(RecordTransformer transformer) {
        this.transformer = transformer;
    }

    /**
     * 策略类型标识：polled。
     */
    @Override
    public String type() {
        return TYPE;
    }

    /**
     * 执行轮询目录备份：转换已处理文件并写入备份目录。
     */
    @Override
    public BackupResult execute(BackupConfig config) {
        long start = System.currentTimeMillis();
        try {
            Files.createDirectories(config.getBackupDir());
            String today = LocalDate.now().format(DATE_FMT);
            Path targetDir = config.getBackupDir().resolve(today);

            List<Path> backedUp = backupPolledFiles(config.getSourceDir(), targetDir, config);

            long totalSize = backedUp.stream()
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();

            return BackupResult.success(targetDir, backedUp, totalSize, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return BackupResult.failure("轮询目录备份失败: " + e.getMessage());
        }
    }

    /**
     * 备份轮询目录中的已处理文件
     *
     * <p>读取源目录中的文件，通过转换器转换为 insert 格式，
     * 写入备份目录的子目录中。
     *
     * @param source 源轮询目录
     * @param target 备份目标目录
     * @param config 备份配置
     * @return 备份的文件列表
     */
    private List<Path> backupPolledFiles(Path source, Path target, BackupConfig config) throws IOException {
        List<Path> backedUp = new ArrayList<>();
        if (!Files.exists(source)) {
            return backedUp;
        }

        // 按源目录名分子目录备份
        String sourceName = source.getFileName() != null ? source.getFileName().toString() : "default";
        Path backupSubDir = target.resolve(sourceName);
        Files.createDirectories(backupSubDir);

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /**
             * 转换并备份单个已处理文件，失败仅告警并跳过
            */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String fileName = file.getFileName().toString();

 // 跳过已备份的文件（.插入.json 后缀）
                    if (fileName.endsWith(".insert.json")) {
                        return FileVisitResult.CONTINUE;
                    }

                    // 读取文件内容
                    String content = Files.readString(file);

 // 转换为 插入 记录
                    String insertRecord = transformer.transform(fileName, content);
                    if (insertRecord == null) {
                        return FileVisitResult.CONTINUE;
                    }

                    // 写入备份文件
                    String backupFileName = fileName + ".insert.json";
                    Path backupFile = backupSubDir.resolve(backupFileName);
                    Files.writeString(backupFile, insertRecord);
                    backedUp.add(backupFile);

                } catch (IOException e) {
                    log.warn("轮询备份跳过文件: {}", file, e);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return backedUp;
    }

    /**
     * 清理过期备份，委托给 {@link DefaultDailyBackupStrategy}。
     */
    @Override
    public int cleanExpired(BackupConfig config) {
        DefaultDailyBackupStrategy delegate = new DefaultDailyBackupStrategy();
        return delegate.cleanExpired(config);
    }

    /**
     * 列出全部备份文件，委托给 {@link DefaultDailyBackupStrategy}。
     */
    @Override
    public List<Path> listBackups(BackupConfig config) {
        DefaultDailyBackupStrategy delegate = new DefaultDailyBackupStrategy();
        return delegate.listBackups(config);
    }
}
