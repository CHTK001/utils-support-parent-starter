package com.chua.common.support.file.builder;

import java.util.List;
/**
* @author CH
* @since 4.0.0.42
 */

public interface ReadCallback {
    /** OnHeader */
    default void onHeader(List<String> headers) {}
    /**
     * 响应请求体。
     *
     * @param row 行，不允许为 null
     */
    void onBody(Object row);
    /** OnComplete */
    default void onComplete(long total) {}
}
