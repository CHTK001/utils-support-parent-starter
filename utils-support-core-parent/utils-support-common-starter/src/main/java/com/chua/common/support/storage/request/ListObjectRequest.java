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
     *
     * 限制
     */
    @Builder.Default
    // [P3C A20 豁免]
    private int limit = 10;

    /**
     * 跳过前 N 条记录（从排序后的列表偏移开始），默认 0。
     *
     * <p>与 {@link #limit} 配合使用：先按名称枚举排序，再从 offset 开始
     * 仅读取 limit 个对象的属性，适用于大目录分页。</p>
     */
    @Builder.Default
    private int offset = 0;
}
