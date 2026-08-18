package com.chua.common.support.storage.result;

import com.chua.common.support.storage.metadata.Metadata;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 文件列表结果对象。
 *
 * <p>包含指定路径下的文件元数据列表，以及用于分页的下一页标记。</p>
 *
 * @author CH
 * @since 1.0
 */
@Getter
@Setter
@SuperBuilder
public class ListObjectResult extends ObjectResult {

    /** 空结果实例 */
    /** 是否为空 */
    public static final ListObjectResult EMPTY = ListObjectResult.builder().build();

    /**
     * 文件元数据列表。
     */
    private List<Metadata> metadata;

    /**
     * 下一页的分页标记（marker）。
     *
     * <p>如果为 null 或空字符串，表示已翻到最后一页，没有更多数据。<br>
     * 非空时，将此值传入 {@link com.chua.common.support.storage.request.ListObjectRequest#marker}
     * 即可获取下一页数据。</p>
     */
    private String marker;
}
