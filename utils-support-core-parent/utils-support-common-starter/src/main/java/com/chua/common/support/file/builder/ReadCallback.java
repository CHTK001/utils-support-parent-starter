package com.chua.common.support.file.builder;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;
/**
 * @author CH
 */

@NullUnmarked
public interface ReadCallback {
    default void onHeader(List<String> headers) {}
    void onBody(Object row);
    default void onComplete(long total) {}
}
