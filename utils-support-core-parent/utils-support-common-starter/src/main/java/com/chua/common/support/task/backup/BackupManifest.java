package com.chua.common.support.task.backup;

import com.chua.common.support.lang.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 数据库备份清单
 *
 * <p>{@link DatabaseBackupStrategy} 备份完成后写入备份目录的 {@link #MANIFEST_FILE_NAME}，
 * 记录每个 SQL 脚本的字节数与 SHA-256 校验和；{@link DatabaseRestoreStrategy} 在
 * {@code verifyChecksum = true} 时于恢复前逐文件重算校验和比对，防止备份文件损坏或被篡改后
 * 静默恢复出脏数据。
 *
 * <p>序列化/反序列化使用 common-starter 的 {@link Json}（Jackson 支撑）；
 * 时间字段用 ISO-8601 字符串，避免引入 Java 时间类型的序列化依赖。
 *
 * @author CH
 * @since 2026/11/24
 */
public record BackupManifest(String database, String createdAt, String checksumAlgorithm, List<FileEntry> files) {

    /**
     * 清单文件名（与 SQL 脚本同目录）
     */
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    /**
     * 校验和算法标识
     */
    public static final String ALGORITHM_SHA256 = "SHA-256";

    /**
     * 紧凑构造器：files 防御性拷贝，保证清单不可变。
     */
    public BackupManifest {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /**
     * 单个备份文件的校验条目
     *
     * @param fileName 文件名（不含路径）
     * @param sizeBytes 文件字节数
     * @param sha256 文件内容的 SHA-256 十六进制串（小写）
     */
    public record FileEntry(String fileName, long sizeBytes, String sha256) {
    }

    /**
     * 按文件名查找校验条目。
     *
     * @param fileName 文件名（不含路径）
     * @return 对应条目；不存在返回 null
     */
    public FileEntry entryFor(String fileName) {
        for (FileEntry entry : files) {
            if (entry.fileName().equals(fileName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 计算文件内容的 SHA-256 十六进制串（小写）。
     *
     * @param file 待计算文件
     * @return 64 位十六进制字符串
     * @throws IOException 文件读取失败
     */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM_SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    /**
     * 序列化为格式化 JSON（写入 manifest.json 的内容）。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return Json.toPrettyJson(this);
    }

    /**
     * 从 manifest.json 内容反序列化。
     *
     * @param json JSON 字符串
     * @return 备份清单
     */
    public static BackupManifest fromJson(String json) {
        return Json.fromJson(json, BackupManifest.class);
    }

    /**
     * 字节数组转十六进制字符串（小写）。
     */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
