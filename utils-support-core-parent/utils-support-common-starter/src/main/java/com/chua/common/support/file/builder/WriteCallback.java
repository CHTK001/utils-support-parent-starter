package com.chua.common.support.file.builder;

/**
 * @author CH
 * @since 4.0.0.42
 */

public interface WriteCallback {
    /**
     * 响应Complete。
     *
     * @param success success（布尔开关）
     */
    void onComplete(boolean success);

    /**
     * On开始
    */
    default void onStart() {}

    /**
     * OnBegin写入
    */
    default void onBeginWrite() {}

    /**
     * OnProgress
    */
    default void onProgress(int current, int total) {}
}
