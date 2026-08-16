package com.chua.common.support.file.builder;

/**
 * @author CH
 * @since 4.0.0.42
 */

public interface WriteCallback {
    void onComplete(boolean success);

    default void onStart() {}

    default void onBeginWrite() {}

    default void onProgress(int current, int total) {}
}
