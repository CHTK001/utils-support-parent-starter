package com.chua.common.support.file.builder;

import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public interface ReadCallback {
    default void onHeader(List<String> headers) {}
    void onBody(Object row);
    default void onComplete(long total) {}
}
