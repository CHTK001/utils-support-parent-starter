package com.chua.common.support.storage.metadata;

import lombok.Builder;
import lombok.Data;

/**
 * 文件元数据。
 *
 * <p>包含文件的基本信息：名称、大小、类型、最后修改时间等。</p>
 *
 * @author CH
 * @since 1.0
 */
@Data
@Builder
public class Metadata {

    /**
     * 文件名（含扩展名）。
     */
    private String name;

    /**
     * 文件路径。
     */
    private String path;

    /**
     * 文件大小（字节）。
     */
    private long size;

    /**
     * 文件扩展名（如 ".pdf"）。
     */
    private String suffix;

    /**
     * 文件内容类型（MIME 类型，如 "application/pdf"）。
     */
    private String contentType;

    /**
     * 最后修改时间戳（毫秒）。
     */
    private long lastModified;

    /**
     * 是否为目录。
     */
    private boolean directory;

    /**
     * 文件 e标签（实体标签）。
     */
    private String etag;

    /**
     * 获取文件的完整 键。
     *
     * @return 完整 键
     */
    public String getKey() {
        if (path == null || path.isEmpty()) {
            return name;
        }
        return path.endsWith("/") ? path + name : path + "/" + name;
    }
}
