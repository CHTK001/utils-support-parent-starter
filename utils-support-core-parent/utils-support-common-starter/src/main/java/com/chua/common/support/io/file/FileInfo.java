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

    /** 名称 */
    private String name;
    /** 文件路径 */
    private String path;
    /** 是否为目录 */
    private boolean directory;
    /** 是否为文件 */
    private boolean file;
    /** 是否隐藏 */
    private boolean hidden;
    /** 大小 */
    private long size;
    /** 最后修改时间 */
    private long lastModified;
    /** 文件扩展名 */
    private String extension;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
    /** 文件所有者 */
    private String owner;
    /** 所属组 */
    private String group;
    /** 文件权限字符串 */
    private String permissions;

    /** From */
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
