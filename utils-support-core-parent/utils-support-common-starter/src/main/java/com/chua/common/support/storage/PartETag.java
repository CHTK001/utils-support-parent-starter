package com.chua.common.support.storage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分片标签，用于完成分片上传时标识每个分片。
 *
 * @author CH
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartETag {

    /**
     * 分片序号（1-based）
     */
    private Integer partNumber;

    /**
      * 分片 e标签
     */
    private String etag;
}