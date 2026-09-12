package com.chua.common.support.network.download;

/**
* 下载协议。
* <p>
* DEFAULT 为内置 HTTP/HTTPS 直连下载（默认），ARIA2 委托本机 aria2c 下载。
*
* @author CH
* @since 4.0.0.42
 */
public enum DownloadProtocol {

    /** 内置 HTTP 下载（默认） */
    DEFAULT,

    /** 委托 aria2c（需安装 aria2 且 aria2c 在 PATH 中） */
    ARIA2
}
