package com.chua.common.support.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullUnmarked;

/**
 * 分片标签，用于完成分片上传时标识每个分片。
 *
 * @author CH
 * @since 1.0
 */
@NullUnmarked
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartETag {

    /**
     * 分片序号（1-based）
     */
    private Integer partNumber;

    /**
     * 分片 ETag
     */
    private String etag;
}