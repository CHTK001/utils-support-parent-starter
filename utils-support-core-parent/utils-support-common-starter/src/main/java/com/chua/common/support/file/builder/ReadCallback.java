package com.chua.common.support.file.builder;

import java.util.List;
/**
* @author CH
* @since 4.0.0.42
 */

public interface ReadCallback {
    /** OnHeader */
    default void onHeader(List<String> headers) {}
    void onBody(Object row);
    /** OnComplete */
    default void onComplete(long total) {}
}
