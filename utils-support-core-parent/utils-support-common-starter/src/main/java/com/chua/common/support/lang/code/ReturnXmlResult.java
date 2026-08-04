package com.chua.common.support.lang.code;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * 返回结果
 *
 * @author CH
 */
@NullMarked
public record ReturnXmlResult(
        Integer code,
        @Nullable String data,
        String msg
) {
}
