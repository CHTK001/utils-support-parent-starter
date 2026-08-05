package com.chua.common.support.io.file;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 文件信息
 *
 * @author CH
 * @since 2026/7/30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInfo {

    private String name;
    private String path;
    private boolean directory;
    private boolean file;
    private boolean hidden;
    private long size;
    private long lastModified;
    private String extension;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String owner;
    private String group;
    private String permissions;

    public static FileInfo from(Path path) {
        File file = path.toFile();
        return FileInfo.builder()
                .name(file.getName())
                .path(path.toString())
                .directory(file.isDirectory())
                .file(file.isFile())
                .hidden(file.isHidden())
                .size(file.length())
                .lastModified(file.lastModified())
                .build();
    }
}
