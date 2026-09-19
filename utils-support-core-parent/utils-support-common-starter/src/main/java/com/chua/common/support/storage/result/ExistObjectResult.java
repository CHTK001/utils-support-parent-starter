package com.chua.common.support.storage.result;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * 文件存在性检查结果对象。
 *
 * @author CH
 * @since 1.0
 */
@Getter
@Setter
@SuperBuilder
public class ExistObjectResult extends ObjectResult {

    /** 空结果实例 */
    public static final ExistObjectResult EMPTY = ExistObjectResult.builder().build();

    /**
    * 文件是否存在。
    */
    private boolean exists;
}
