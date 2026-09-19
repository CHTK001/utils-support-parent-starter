package com.chua.common.support.network.download;

import java.io.IOException;

/**
 * 下载异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DownloadException extends IOException {
    /**
     * 构造方法，创建 DownloadException 实例。
     *
     * @param message 消息，不允许为 null
     */
    public DownloadException(String message) {
        super(message);
    }

    /**
     * 构造方法，创建 DownloadException 实例。
     *
     * @param message 消息，不允许为 null
     * @param cause 方法入参 cause
     */
    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
