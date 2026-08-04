package com.chua.common.support.storage.request;

import com.chua.common.support.storage.MultipartStorage;
import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

/**
 * 分片上传请求。
 *
 * @author CH
 * @since 1.0
 */
@NullUnmarked
@Data
@Builder
public class MultipartUploadPartRequest {

    /**
     * 上传任务 ID
     */
    private String uploadId;

    /**
     * 分片序号（1-based）
     */
    private Integer partNumber;

    /**
     * 分片内容
     */
    private byte[] content;

    /**
     * 是否为最后一个分片
     */
    private Boolean isLastPart;
}