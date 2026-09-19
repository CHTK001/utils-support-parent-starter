package com.chua.common.support.storage.result;

import com.chua.common.support.storage.MultipartStorage;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 分片上传结果。
 *
 * @author CH
 * @since 1.0
 */
@Getter
@Setter
@SuperBuilder
public class MultipartPartResult extends ObjectResult {

    /**
     * 上传任务 标识
     */
    private String uploadId;

    /**
     * 分片序号（1-based）
     */
    private Integer partNumber;

    /**
     * 分片 e标签
     */
    private String etag;

    /**
     * 分片大小（字节）
     */
    private Long partSize;
}
