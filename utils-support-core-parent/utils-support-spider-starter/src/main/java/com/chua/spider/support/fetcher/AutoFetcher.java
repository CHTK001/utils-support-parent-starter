package com.chua.spider.support.fetcher;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderFetcher;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 自动降级抓取器。
 *
 * <p>SPI 名称：{@code auto}
 *
 * <p>内部收集所有可用的 {@link SpiderFetcher} SPI 实现，
 * 按优先级排序。执行 {@link #fetch(SpiderRequest)} 时，
 * 先尝试优先级最高的实现，如果返回失败则依次降级到下一个实现，
 * 直到某个实现成功获取内容或所有实现尝试完毕。
 *
 * <p>适用于不确定目标站点兼容性的场景，自动选择可用的抓取方式：
 * <ol>
 *   <li>优先尝试 {@code http}（JDK HttpClient，零依赖）</li>
 *   <li>如果失败则尝试其他 SPI 注册的 Fetcher</li>
 *   <li>所有尝试均失败后返回最后一次失败的结果</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("auto")
public class AutoFetcher implements SpiderFetcher {

    /**
     * 可用的 Fetcher 实现列表，按优先级排序
     */
    private final List<SpiderFetcher> fetchers;

    /**
     * 默认构造器，从 SPI 收集所有可用的 Fetcher 实现。
     */
    public AutoFetcher() {
        ServiceProvider<SpiderFetcher> provider = ServiceProvider.of(SpiderFetcher.class);
        // 过滤掉自身，避免递归死循环
        this.fetchers = provider.collectNew().stream()
                .filter(f -> !(f instanceof AutoFetcher))
                .collect(Collectors.toList());
        log.info("[spider-fetcher] AutoFetcher 加载了 {} 个 Fetcher 实现", fetchers.size());
    }

    @Override
    /**
     * 获取
    */
    public SpiderResponse fetch(SpiderRequest request) {
        if (fetchers.isEmpty()) {
            return SpiderResponse.builder()
                    .request(request)
                    .statusCode(0)
                    .error("没有可用的 Fetcher SPI 实现")
                    .build();
        }

        // 按优先级依次尝试，直到某个成功
        SpiderResponse lastResponse = null;
        for (int i = 0; i < fetchers.size(); i++) {
            SpiderFetcher fetcher = fetchers.get(i);
            try {
                log.debug("[spider-fetcher] AutoFetcher 尝试第 {} 个实现 ({}): {}",
                        i + 1, fetcher.getClass().getSimpleName(), request.getUrl());
                SpiderResponse response = fetcher.fetch(request);
                lastResponse = response;

                if (response.isSuccess()) {
                    log.info("[spider-fetcher] AutoFetcher 第 {} 个实现 ({}) 成功: {} ({}ms)",
                            i + 1, fetcher.getClass().getSimpleName(),
                            request.getUrl(), response.getFetchTimeMs());
                    return response;
                }
                log.warn("[spider-fetcher] AutoFetcher 第 {} 个实现 ({}) 失败: {} - {}",
                        i + 1, fetcher.getClass().getSimpleName(),
                        request.getUrl(), response.getError());
            } catch (Exception e) {
                log.warn("[spider-fetcher] AutoFetcher 第 {} 个实现 ({}) 异常: {} - {}",
                        i + 1, fetcher.getClass().getSimpleName(),
                        request.getUrl(), e.getMessage());
                lastResponse = SpiderResponse.builder()
                        .request(request)
                        .statusCode(0)
                        .error("自动降级异常: " + e.getMessage())
                        .build();
            }
        }

        // 所有实现都失败，返回最后一次失败的响应
        log.warn("[spider-fetcher] AutoFetcher 所有实现均失败: {}", request.getUrl());
        return lastResponse;
    }
}
