package com.chua.common.support.file.builder;

/**
 * @author CH
 */

public interface WriteCallback {
    void onComplete(boolean success);

    default void onStart() {}

    default void onBeginWrite() {}

    default void onProgress(int current, int total) {}
}
