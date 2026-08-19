package com.chua.common.support.task.backup;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 默认按天备份策略
 *
 * <p>目录结构：
 * <pre>
 *   {backupDir}/
 *   ├── {yyyy-MM-dd}/              ← 当天备份（原始文件拷贝）
 *   │   ├── file1.txt
 *   │   └── file2.json
 *   └── archive/                   ← 历史备份（按天压缩为 ZIP）
 *       ├── 2026-07-15.zip
 *       └── 2026-07-14.zip
 * </pre>
 *
 * <p>执行流程：
 * <ol>
 *   <li>将源目录文件拷贝到 {backupDir}/{today}/</li>
 *   <li>将昨天的备份压缩为 {backupDir}/archive/{yesterday}.zip</li>
 *   <li>清理超过保留天数的历史备份</li>
 * </ol>
 *
 * @author CH
 * @since 2026/07/16
 */
public class DefaultDailyBackupStrategy implements BackupStrategy {

    /**
     * 类型
     */
    private static final String TYPE = "daily";
    /** Archive_dir */
    private static final String ARCHIVE_DIR = "archive";
    /** Date_fmt */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    /** Type */
    public String type() {
        return TYPE;
    }

    @Override
    /** 执行 */
    public BackupResult execute(BackupConfig config) {
        long start = System.currentTimeMillis();
        try {
            // 1. 确保目录存在
            Files.createDirectories(config.getBackupDir());
            Files.createDirectories(config.getBackupDir().resolve(ARCHIVE_DIR));

            // 2. 拷贝当天文件
            String today = LocalDate.now().format(DATE_FMT);
            Path todayDir = config.getBackupDir().resolve(today);
            List<Path> copiedFiles = copyDirectory(config.getSourceDir(), todayDir, config);

            long totalSize = copiedFiles.stream()
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0; }
                    })
                    .sum();

            // 3. 压缩昨天的备份
            String yesterday = LocalDate.now().minusDays(1).format(DATE_FMT);
            Path yesterdayDir = config.getBackupDir().resolve(yesterday);
            if (Files.exists(yesterdayDir) && config.isCompressArchives()) {
                compressToZip(yesterdayDir, config.getBackupDir().resolve(ARCHIVE_DIR).resolve(yesterday + ".zip"));
                deleteDirectory(yesterdayDir);
            }

            // 4. 清理过期备份
            cleanExpired(config);

            long duration = System.currentTimeMillis() - start;
            return BackupResult.success(todayDir, copiedFiles, totalSize, duration);
        } catch (Exception e) {
            return BackupResult.failure("备份失败: " + e.getMessage());
        }
    }

    @Override
    /** CleanExpired */
    public int cleanExpired(BackupConfig config) {
        int count = 0;
        Path archiveDir = config.getBackupDir().resolve(ARCHIVE_DIR);
        if (!Files.exists(archiveDir)) {
            return 0;
        }

        try {
            LocalDate cutoff = LocalDate.now().minusDays(config.getRetentionDays());
            try (var stream = Files.list(archiveDir)) {
                for (Path zip : stream.toList()) {
                    String name = zip.getFileName().toString().replace(".zip", "");
                    try {
                        LocalDate date = LocalDate.parse(name, DATE_FMT);
                        if (date.isBefore(cutoff)) {
                            Files.deleteIfExists(zip);
                            count++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            // 清理过期的当天目录
            try (var stream = Files.list(config.getBackupDir())) {
                for (Path dir : stream.toList()) {
                    if (Files.isDirectory(dir) && !dir.getFileName().toString().equals(ARCHIVE_DIR)) {
                        try {
                            LocalDate date = LocalDate.parse(dir.getFileName().toString(), DATE_FMT);
                            if (date.isBefore(cutoff)) {
                                deleteDirectory(dir);
                                count++;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return count;
    }

    @Override
    /** ListBackups */
    public List<Path> listBackups(BackupConfig config) {
        List<Path> backups = new ArrayList<>();
        Path archiveDir = config.getBackupDir().resolve(ARCHIVE_DIR);
        if (Files.exists(archiveDir)) {
            try (var stream = Files.list(archiveDir)) {
                stream.filter(p -> p.toString().endsWith(".zip"))
                        .sorted(Comparator.reverseOrder())
                        .forEach(backups::add);
            } catch (IOException ignored) {
            }
        }
        return backups;
    }

    /**
     * 拷贝目录
     */
    private List<Path> copyDirectory(Path source, Path target, BackupConfig config) throws IOException {
        List<Path> copied = new ArrayList<>();
        if (!Files.exists(source)) {
            return copied;
        }
        Files.createDirectories(target);

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            /** VisitFile */
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String fileName = file.getFileName().toString();
                    // 过滤检查
                    if (!matchPattern(fileName, config.getIncludePattern())
                            || matchPattern(fileName, config.getExcludePattern())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path dest = target.resolve(source.relativize(file));
                    Files.createDirectories(dest.getParent());
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                    copied.add(dest);
                } catch (IOException e) {
                    // 跳过无法复制的文件
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return copied;
    }

    /**
     * 压缩为 ZIP
     */
    private void compressToZip(Path source, Path zipFile) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                /** VisitFile */
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entryName = source.relativize(file).toString().replace("\\", "/");
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            /** VisitFile */
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            /** PostVisitDirectory */
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Glob 模式匹配
     */
    private boolean matchPattern(String fileName, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }
}
