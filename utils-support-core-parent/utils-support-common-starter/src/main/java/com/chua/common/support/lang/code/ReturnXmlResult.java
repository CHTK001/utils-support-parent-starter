package com.chua.common.support.lang.code;

import org.jspecify.annotations.NullUnmarked;
/**
 * 返回结果
 *
 * @author CH
 */
@NullUnmarked
public record ReturnXmlResult(
        Integer code,
        String data,
        String msg
) {
}
