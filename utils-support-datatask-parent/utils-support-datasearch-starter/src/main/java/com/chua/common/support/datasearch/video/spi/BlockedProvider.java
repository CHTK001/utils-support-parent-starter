package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;

/**
* 被封 提供者 的通用响应工具
* @author CH
* @since 4.0.0
 */
public final class BlockedProvider {

    /**
    * blocked提供者。
    */
    private BlockedProvider() {}

    /**
    * 返回已封禁的搜索响应
    *
    * @param providerName 提供者名称
    * @return blocked的结果
    */
    public static ReturnPageResult<VideoInfoResult> blocked(String providerName) {
        VideoProviderRegistry.BlockReason reason = VideoProviderRegistry.getBlockReason(providerName);
        String msg = "provider [" + providerName + "] 已封禁"
                + (reason != null ? " (" + reason.label() + ")" : "")
                + "，跳过重试";
        return ReturnPageResult.error(msg);
    }

    /**
    * 返回空结果（无数据）
    *
    * @param providerName 提供者名称
    * @return 空的结果
    */
    public static ReturnPageResult<VideoInfoResult> empty(String providerName) {
        VideoProviderRegistry.block(providerName, VideoProviderRegistry.BlockReason.RATE_LIMITED);
        String msg = "provider [" + providerName + "] 返回空结果，已标记为 RATE_LIMITED";
        return ReturnPageResult.error(msg);
    }
}
