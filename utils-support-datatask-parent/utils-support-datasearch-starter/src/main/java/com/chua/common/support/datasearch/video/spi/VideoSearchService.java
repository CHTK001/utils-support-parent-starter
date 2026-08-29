package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 视频聚合检索服务。
 *
 * <p>遍历全部 {@link ResourceProvider}（跳过 {@link VideoProviderRegistry} 已封禁的源），
 * 将各数据源的搜索结果聚合为一份分页结果。单个数据源失败自动跳过，不影响整体。</p>
 *
 * <p>用法：{@code new VideoSearchService().search(new VideoSearch("keyword"))}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSearchService {

    private static final Logger log = LoggerFactory.getLogger(VideoSearchService.class);

    /**
     * 聚合搜索全部可用视频数据源。
     *
     * @param search 搜索参数
     * @return 聚合分页结果（各源结果合并，total 为各源之和）
     */
    public ReturnPageResult<VideoInfoResult> search(VideoSearch search) {
        List<VideoInfoResult> all = new ArrayList<>();
        long total = 0;
        int sources = 0;
        Map<String, ResourceProvider> providers = ServiceProvider.of(ResourceProvider.class).list();
        if (providers != null) {
            for (Map.Entry<String, ResourceProvider> entry : providers.entrySet()) {
                String name = entry.getKey();
                if (VideoProviderRegistry.isBlocked(name)) {
                    log.debug("[VideoSearchService] 跳过被封数据源: {}", name);
                    continue;
                }
                try {
                    ReturnPageResult<VideoInfoResult> result = entry.getValue().searchResource(search);
                    if (result != null && result.getData() != null && result.getData().getData() != null) {
                        all.addAll(result.getData().getData());
                        total += result.getData().getTotal();
                        sources++;
                    }
                } catch (Exception e) {
                    log.debug("[VideoSearchService] 数据源[{}]检索失败: {}", name, e.getMessage());
                }
            }
        }
        PageResult<VideoInfoResult> page = PageResult.<VideoInfoResult>builder()
                .pageNo(search.getPage())
                .pageSize(search.getPageSize())
                .total(total)
                .data(all)
                .build();
        log.info("[VideoSearchService] 聚合检索完成: keyword={}, 数据源={}, 结果={}", search.getKeyword(), sources, all.size());
        return ReturnPageResult.of(page);
    }
}
