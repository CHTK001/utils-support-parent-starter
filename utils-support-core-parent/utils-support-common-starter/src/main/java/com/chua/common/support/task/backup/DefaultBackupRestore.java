package com.chua.common.support.task.backup;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 默认备份恢复实现
 *
 * <p>支持三种恢复方式：
 * <ul>
 *   <li>按日期恢复 — 从 {backupDir}/{yyyy-MM-dd}/ 恢复原始文件</li>
 *   <li>恢复最新 — 自动选择最新的日期目录或 ZIP 压缩包</li>
 *   <li>ZIP 解压恢复 — 从 {backupDir}/archive/{yyyy-MM-dd}.zip 解压恢复</li>
 * </ul>
 *
 * <p>恢复优先级：原始目录 > ZIP 压缩包
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public class DefaultBackupRestore implements BackupRestore {

    /** 历史备份压缩包目录名 */
    private static final String ARCHIVE_DIR = "archive";
    /** 备份日期目录/压缩包的日期格式 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 恢复入口：配置了日期则按日期恢复，否则恢复最新备份。
     */
    @Override
    public RestoreResult restore(RestoreConfig config) {
        if (config.getDate() != null && !config.getDate().isBlank()) {
            LocalDate date = LocalDate.parse(config.getDate(), DATE_FMT);
            return restoreByDate(config, date);
        }
        return restoreLatest(config);
    }

    /**
     * 恢复最新一次备份：取可用日期列表中最新的一天。
     */
    @Override
    public RestoreResult restoreLatest(RestoreConfig config) {
        List<LocalDate> dates = listAvailableDates(config.getBackupDir());
        if (dates.isEmpty()) {
            return RestoreResult.failure("没有可用的备份");
        }
        return restoreByDate(config, dates.get(0));
    }

    /**
     * 按指定日期恢复：优先原始目录，回退 ZIP 压缩包。
     */
    @Override
    public RestoreResult restoreByDate(RestoreConfig config, LocalDate date) {
        long start = System.currentTimeMillis();
        String dateStr = date.format(DATE_FMT);

        try {
            Files.createDirectories(config.getTargetDir());

            // 优先从原始目录恢复
            Path dateDir = config.getBackupDir().resolve(dateStr);
            if (Files.exists(dateDir) && Files.isDirectory(dateDir)) {
                List<Path> restored = restoreFromDirectory(dateDir, config);
                return RestoreResult.success(config.getTargetDir(), restored,
                        calcTotalSize(restored), System.currentTimeMillis() - start);
            }

            // 回退到 ZIP 压缩包
            Path zipFile = config.getBackupDir().resolve(ARCHIVE_DIR).resolve(dateStr + ".zip");
            if (Files.exists(zipFile)) {
                List<Path> restored = restoreFromZip(zipFile, config);
                return RestoreResult.success(config.getTargetDir(), restored,
                        calcTotalSize(restored), System.currentTimeMillis() - start);
            }

            return RestoreResult.failure("找不到 " + dateStr + " 的备份");
        } catch (Exception e) {
            return RestoreResult.failure("恢复失败: " + e.getMessage());
        }
    }

    /**
     * 列出备份目录下全部可用日期（原始目录 + ZIP 压缩包，去重升序）。
     */
    @Override
    public List<LocalDate> listAvailableDates(Path backupDir) {
        List<LocalDate> dates = new ArrayList<>();

        // 扫描原始目录
        if (Files.exists(backupDir)) {
            try (var stream = Files.list(backupDir)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().equals(ARCHIVE_DIR))
                        .forEach(p -> {
                            try {
                                dates.add(LocalDate.parse(p.getFileName().toString(), DATE_FMT));
                            } catch (Exception ignored) {
                                // 目录名非日期格式，跳过
                            }
                        });
            } catch (IOException e) {
                log.warn("扫描备份目录失败: {}", backupDir, e);
            }
        }

        // 扫描 ZIP 压缩包
        Path archiveDir = backupDir.resolve(ARCHIVE_DIR);
        if (Files.exists(archiveDir)) {
            try (var stream = Files.list(archiveDir)) {
                stream.filter(p -> p.toString().endsWith(".zip"))
                        .forEach(p -> {
                            try {
                                String name = p.getFileName().toString().replace(".zip", "");
                                LocalDate d = LocalDate.parse(name, DATE_FMT);
                                if (!dates.contains(d)) {
                                    dates.add(d);
                                }
                            } catch (Exception ignored) {
                                // 文件名非日期格式，跳过
                            }
                        });
            } catch (IOException e) {
                log.warn("扫描压缩包目录失败: {}", archiveDir, e);
            }
        }

        dates.sort(Comparator.reverseOrder());
        return dates;
    }

    /**
     * 从目录恢复文件
     */
    private List<Path> restoreFromDirectory(Path source, RestoreConfig config) throws IOException {
        List<Path> restored = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /**
             * 恢复单个文件：命中过滤规则后复制到目标目录，失败仅告警并跳过。
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    String fileName = file.getFileName().toString();
                    if (!matchPattern(fileName, config.getIncludePattern())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path target = config.getTargetDir().resolve(source.relativize(file));
                    Files.createDirectories(target.getParent());
                    if (config.isOverwrite() || !Files.exists(target)) {
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        restored.add(target);
                    }
                } catch (IOException e) {
                    log.warn("恢复跳过无法复制的文件: {}", file, e);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (config.isDeleteAfterRestore()) {
            deleteDirectory(source);
        }
        return restored;
    }

    /**
     * 从 ZIP 恢复文件
     */
    private List<Path> restoreFromZip(Path zipFile, RestoreConfig config) throws IOException {
        List<Path> restored = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    Files.createDirectories(config.getTargetDir().resolve(entry.getName()));
                    continue;
                }
                String fileName = Paths.get(entry.getName()).getFileName().toString();
                if (!matchPattern(fileName, config.getIncludePattern())) {
                    continue;
                }
                Path target = config.getTargetDir().resolve(entry.getName());
                Files.createDirectories(target.getParent());
                if (config.isOverwrite() || !Files.exists(target)) {
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    restored.add(target);
                }
            }
        }
        return restored;
    }

    /**
     * 计算文件列表的总大小（字节）；无法读取的文件按 0 计。
     */
    private long calcTotalSize(List<Path> files) {
        return files.stream()
                .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } })
                .sum();
    }

    /**
     * 递归删除目录及其全部内容。
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            /** 删除单个文件 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /** 目录内文件删尽后删除目录本身 */
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 判断文件名是否命中 Glob 模式；模式为空视为全部命中。
     */
    private boolean matchPattern(String fileName, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return fileName.matches(regex);
    }
}
