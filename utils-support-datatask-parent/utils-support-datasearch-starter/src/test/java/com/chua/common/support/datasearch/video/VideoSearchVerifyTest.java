package com.chua.common.support.datasearch.video;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.VideoSearchService;

/**
 * 视频聚合检索真实数据验证。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSearchVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        VideoSearchService service = new VideoSearchService();
        ReturnPageResult<VideoInfoResult> result = service.search(new VideoSearch("三体"));
        if (result == null || result.getData() == null) {
            System.out.println("聚合检索返回 null");
            return;
        }
        System.out.println("聚合检索 三体: total=" + result.getData().getTotal()
                + " 返回=" + result.getData().getData().size());
        result.getData().getData().stream().limit(8).forEach(v ->
                System.out.printf("  [%s] %s%n",
                        v.getVideoPlatform() == null ? "?" : v.getVideoPlatform(),
                        v.getVideoTitle() == null ? "?" : v.getVideoTitle()));
    }
}
