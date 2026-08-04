package com.chua.common.support.task.backup;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 目录备份实现
 *
 * <p>备份指定目录的所有文件，支持增量备份（仅备份变更文件）。
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
public class DirectoryBackup implements BackupStrategy {

    /**
     * 类型
     */
    private static final String TYPE = "directory";
    private final DefaultDailyBackupStrategy delegate = new DefaultDailyBackupStrategy();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public BackupResult execute(BackupConfig config) {
        return delegate.execute(config);
    }

    @Override
    public BackupResult executeIncremental(BackupConfig config, long lastBackupTime) {
        long start = System.currentTimeMillis();
        try {
            Files.createDirectories(config.getBackupDir());
            String today = java.time.LocalDate.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Path targetDir = config.getBackupDir().resolve(today);
            List<Path> changedFiles = copyChangedFiles(config.getSourceDir(), targetDir, config, lastBackupTime);

            if (changedFiles.isEmpty()) {
                return BackupResult.builder()
                        .success(true)
                        .backupPath(targetDir)
                        .fileCount(0)
                        .durationMillis(System.currentTimeMillis() - start)
                        .build();
            }

            long totalSize = changedFiles.stream()
                    .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } })
                    .sum();

            return BackupResult.success(targetDir, changedFiles, totalSize, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return BackupResult.failure("增量备份失败: " + e.getMessage());
        }
    }

    /**
     * 仅拷贝变更的文件
     */
    private List<Path> copyChangedFiles(Path source, Path target, BackupConfig config, long since) throws IOException {
        List<Path> changed = new ArrayList<>();
        if (!Files.exists(source)) {
            return changed;
        }
        Files.createDirectories(target);

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    // 仅拷贝修改时间晚于 since 的文件
                    if (attrs.lastModifiedTime().toMillis() <= since) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path dest = target.resolve(source.relativize(file));
                    Files.createDirectories(dest.getParent());
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                    changed.add(dest);
                } catch (IOException ignored) {
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return changed;
    }
}
