package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ListReturnResult;

/**
* 视频下载链接提供者接口。
* 用于定义搜索视频下载链接的标准行为。
*
* @author CH
* @since 4.0.0.42
 */
public interface DownloadLinkProvider {

    /**
    * 根据关键词搜索视频下载链接。
    *
    * @param keyword 搜索关键词，用于定位目标视频资源。
    * @return 包含搜索结果列表的返回对象，可能为空或包含多个下载链接。
    */
    ListReturnResult<String> searchDownloadUrls(String keyword);
}
