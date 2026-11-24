package com.chua.common.support.task.backup;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 备份产物校验器
 *
 * <p>按 {@link BackupManifest} 逐文件核对字节数与 SHA-256 校验和，
 * 复用备份侧写入的清单，无需数据库连接即可离线验证备份完整性。
 *
 * <p>支持三种校验目标：
 * <ul>
 *   <li>备份目录 — 读取目录内 {@link BackupManifest#MANIFEST_FILE_NAME}；</li>
 *   <li>ZIP 归档 — 读取归档内 {@link BackupManifest#MANIFEST_FILE_NAME}，流式计算各条目校验和；</li>
 *   <li>单个 .sql — 与其同目录的 manifest 比对该文件。</li>
 * </ul>
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
public final class BackupVerifier {

    private BackupVerifier() {
    }

    /**
     * 校验备份产物完整性。
     *
     * @param target 备份目录、.sql 脚本或 .zip 归档
     * @return 校验结果（永不返回 null）
     */
    public static VerificationResult verify(Path target) {
        if (target == null || !Files.exists(target)) {
            return VerificationResult.fail("校验目标不存在: " + target);
        }
        try {
            String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) {
                return verifyZip(target);
            }
            if (Files.isDirectory(target)) {
                return verifyDirectory(target);
            }
            if (name.endsWith(".sql")) {
                return verifySingleFile(target);
            }
            return VerificationResult.fail("不支持的校验目标（需目录、.sql 或 .zip）: " + target);
        } catch (Exception e) {
            log.warn("[backup] 备份校验失败: {} - {}", target, e.getMessage());
            return VerificationResult.fail("校验失败: " + e.getMessage());
        }
    }

    /**
     * 校验备份目录：逐条目核对文件存在、字节数与 SHA-256；
     * 目录中未被清单收录的 .sql 以警告形式报告（不判失败）。
     *
     * <p>同时兼容两种目录形态：备份产物直接位于该目录（manifest 就在本目录），
     * 或按 {@code {backupDir}/{日期}/} 分层（manifest 在日期子目录，逐个子目录校验后合并）。
     */
    private static VerificationResult verifyDirectory(Path dir) throws IOException {
        Path manifestFile = dir.resolve(BackupManifest.MANIFEST_FILE_NAME);
        if (!Files.exists(manifestFile)) {
            List<Path> dateDirs;
            try (var stream = Files.list(dir)) {
                dateDirs = stream.filter(Files::isDirectory)
                        .filter(sub -> Files.exists(sub.resolve(BackupManifest.MANIFEST_FILE_NAME)))
                        .toList();
            }
            if (dateDirs.isEmpty()) {
                return VerificationResult.fail("未找到 " + BackupManifest.MANIFEST_FILE_NAME + ": " + dir);
            }
            int checked = 0;
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (Path dateDir : dateDirs) {
                VerificationResult part = verifyDirectory(dateDir);
                checked += part.filesChecked();
                errors.addAll(part.errors());
                warnings.addAll(part.warnings());
            }
            return new VerificationResult(errors.isEmpty(), checked, List.copyOf(errors), List.copyOf(warnings));
        }
        BackupManifest manifest = BackupManifest.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8));
        Set<String> declared = new HashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int checked = 0;
        for (BackupManifest.FileEntry entry : manifest.files()) {
            declared.add(entry.fileName());
            Path file = dir.resolve(entry.fileName());
            if (!Files.exists(file)) {
                errors.add("文件缺失: " + entry.fileName());
                continue;
            }
            checked++;
            if (Files.size(file) != entry.sizeBytes()) {
                errors.add("字节数不匹配: " + entry.fileName()
                        + ", 期望 " + entry.sizeBytes() + ", 实际 " + Files.size(file));
            }
            String actual = BackupManifest.sha256Hex(file);
            if (!actual.equalsIgnoreCase(entry.sha256())) {
                errors.add("SHA-256 不匹配: " + entry.fileName()
                        + ", 期望 " + entry.sha256() + ", 实际 " + actual);
            }
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.toLowerCase(Locale.ROOT).endsWith(".sql") && !declared.contains(fileName)) {
                    warnings.add("未收录的脚本: " + fileName);
                }
            }
        }
        return new VerificationResult(errors.isEmpty(), checked, List.copyOf(errors), List.copyOf(warnings));
    }

    /**
     * 校验单个 .sql 脚本（与其同目录 manifest 比对）。
     */
    private static VerificationResult verifySingleFile(Path script) throws IOException {
        Path parent = script.getParent();
        if (parent == null || !Files.exists(parent.resolve(BackupManifest.MANIFEST_FILE_NAME))) {
            return VerificationResult.fail("未找到同目录 " + BackupManifest.MANIFEST_FILE_NAME + ": " + script);
        }
        return verifyDirectoryEntry(parent, script.getFileName().toString());
    }

    /**
     * 在目录内校验单个声明文件。
     */
    private static VerificationResult verifyDirectoryEntry(Path dir, String fileName) throws IOException {
        BackupManifest manifest = BackupManifest.fromJson(
                Files.readString(dir.resolve(BackupManifest.MANIFEST_FILE_NAME), StandardCharsets.UTF_8));
        BackupManifest.FileEntry entry = manifest.entryFor(fileName);
        if (entry == null) {
            return VerificationResult.fail("备份清单缺少文件条目: " + fileName);
        }
        Path file = dir.resolve(fileName);
        List<String> errors = new ArrayList<>();
        if (Files.size(file) != entry.sizeBytes()) {
            errors.add("字节数不匹配: " + fileName
                    + ", 期望 " + entry.sizeBytes() + ", 实际 " + Files.size(file));
        }
        String actual = BackupManifest.sha256Hex(file);
        if (!actual.equalsIgnoreCase(entry.sha256())) {
            errors.add("SHA-256 不匹配: " + fileName + ", 期望 " + entry.sha256() + ", 实际 " + actual);
        }
        return new VerificationResult(errors.isEmpty(), 1, List.copyOf(errors), List.of());
    }

    /**
     * 校验 ZIP 归档：定位归档内 manifest，流式计算各 .sql 条目校验和比对。
     */
    private static VerificationResult verifyZip(Path zipFile) throws IOException {
        BackupManifest manifest = null;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int checked = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String baseName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (baseName.equalsIgnoreCase(BackupManifest.MANIFEST_FILE_NAME)) {
                    manifest = BackupManifest.fromJson(new String(readAll(zip), StandardCharsets.UTF_8));
                    continue;
                }
            }
        }
        if (manifest == null) {
            return VerificationResult.fail("ZIP 归档内未找到 " + BackupManifest.MANIFEST_FILE_NAME + ": " + zipFile);
        }
        Set<String> declared = new HashSet<>();
        for (BackupManifest.FileEntry fileEntry : manifest.files()) {
            declared.add(fileEntry.fileName());
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String baseName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (!baseName.toLowerCase(Locale.ROOT).endsWith(".sql")) {
                    continue;
                }
                BackupManifest.FileEntry fileEntry = manifest.entryFor(baseName);
                if (fileEntry == null) {
                    warnings.add("未收录的脚本: " + baseName);
                    continue;
                }
                checked++;
                byte[] content = readAll(zip);
                if (content.length != fileEntry.sizeBytes()) {
                    errors.add("字节数不匹配: " + baseName
                            + ", 期望 " + fileEntry.sizeBytes() + ", 实际 " + content.length);
                }
                String actual = sha256Hex(content);
                if (!actual.equalsIgnoreCase(fileEntry.sha256())) {
                    errors.add("SHA-256 不匹配: " + baseName
                            + ", 期望 " + fileEntry.sha256() + ", 实际 " + actual);
                }
            }
        }
        for (BackupManifest.FileEntry fileEntry : manifest.files()) {
            if (!declared.isEmpty() && !existsInZip(zipFile, fileEntry.fileName())) {
                errors.add("归档缺少声明文件: " + fileEntry.fileName());
            }
        }
        return new VerificationResult(errors.isEmpty(), checked, List.copyOf(errors), List.copyOf(warnings));
    }

    /**
     * 判断 ZIP 归档内是否存在指定条目名（按 basename 比对）。
     */
    private static boolean existsInZip(Path zipFile, String fileName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String baseName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (baseName.equals(fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 读尽当前 ZIP 条目。
     */
    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /**
     * 字节数组 SHA-256 十六进制串（小写）。
     */
    private static String sha256Hex(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(BackupManifest.ALGORITHM_SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest(content)) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 校验结果
     *
     * @param success      是否全部通过
     * @param filesChecked 实际核对完好的文件数
     * @param errors       错误清单（导致失败）
     * @param warnings     警告清单（不导致失败）
     */
    public record VerificationResult(boolean success, int filesChecked,
                                     List<String> errors, List<String> warnings) {

        /**
         * 紧凑构造器：清单防御性拷贝。
         */
        public VerificationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /**
         * 快速构造失败结果。
         */
        static VerificationResult fail(String error) {
            return new VerificationResult(false, 0, List.of(error), List.of());
        }
    }
}
