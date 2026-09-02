package com.chua.common.support.network.download;

import java.io.IOException;

/**
 * 下载异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DownloadException extends IOException {
    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
