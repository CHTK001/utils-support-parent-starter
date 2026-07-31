package com.chua.common.support.lang.code;
/**
 * 返回结果
 *
 * @author CH
 */
public record ReturnXmlResult(
        Integer code,
        String data,
        String msg
) {
}
