package com.chua.common.support.storage.request;

import lombok.Builder;
import lombok.Data;

/**
* 文件列表请求对象。
*
* <p>用于列出指定路径下的文件，支持分页。</p>
*
* @author CH
* @since 1.0
 */
@Data
@Builder
public class ListObjectRequest {

    /**
    * 文件路径（目录路径）。
     */
    private String filePath;

    /**
    * 分页标记（请求id），用于翻页。
     */
    private String marker;

    /**
    * 每页返回的最大数量，默认 10。
     */
    @Builder.Default
    /**
    * 限制
     */
    private int limit = 10;
}
