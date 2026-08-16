package com.chua.filesystem.support.filesearch.model;

import javax.annotation.Nullable;

/**
 * 文件信息（WizTree 全量字段）
 *
 * @param name          文件名（含扩展名）
 * @param path          完整路径
 * @param size          文件大小（字节）
 * @param sizeFormatted 人类可读大小（如 "1.5 GB"）
 * @param parentDir     父目录路径
 * @param percentage    占父目录体积比例（百分比），0 表示未计算
 * @param lastModified  最后修改时间戳（毫秒）
 * @param isDirectory   是否为目录
 * @param extension     文件扩展名（不含点），null 表示无扩展名
 * @param attributes    文件属性位掩码（Windows: FILE_ATTRIBUTE_*，Unix: st_mode & 0o7777）
 * @param usnRecordId   USN 记录 ID（Windows NTFS，非 Windows 为 0）
 * @param parentFileId  父目录文件引用号（Windows NTFS，非 Windows 为 0）
 * @param fileCount     包含文件数（仅目录有效，否则为 0）
 * @param dirCount      包含子目录数（仅目录有效，否则为 0）
 * @param allocatedSize 磁盘占用空间（字节），MFT 扫描时来自 $DATA non-resident 分配大小，walkdir 降级时等于 size
 * @author CH
 * @since 4.0.0.42
 */
public record FileInfo(
        String name,
        String path,
        long size,
        String sizeFormatted,
        String parentDir,
        double percentage,
        long lastModified,
        boolean isDirectory,
        @Nullable String extension,
        int attributes,
        long usnRecordId,
        long parentFileId,
        long fileCount,
        long dirCount,
        long allocatedSize
) {
    public FileInfo {
        if (attributes == 0 && !isDirectory && size >= 0) {
            attributes = 0x20;
        }
    }

    public FileInfo(String name, String path, long size, String sizeFormatted,
                    String parentDir, double percentage, long lastModified, boolean isDirectory,
                    String extension) {
        this(name, path, size, sizeFormatted, parentDir, percentage, lastModified,
                isDirectory, extension, 0, 0, 0, 0, 0, 0);
    }
}
