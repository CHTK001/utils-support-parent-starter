package com.chua.spider.support.parser;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spider.support.SpiderParser;
import com.chua.spider.support.model.SpiderResponse;
import com.chua.spider.support.model.SpiderResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 自动降级解析器。
 *
 * <p>SPI 名称：{@code auto}
 *
 * <p>内部收集所有可用的 {@link SpiderParser} SPI 实现，
 * 按优先级排序。执行 {@link #parse(SpiderResponse)} 时，
 * 先尝试优先级最高的实现，如果返回 null 则依次降级到下一个实现，
 * 直到某个实现成功解析或所有实现尝试完毕。
 *
 * <p>适用于内容类型不确定的场景：
 * <ol>
 *   <li>优先尝试 {@code html}（JSoup 解析）</li>
 *   <li>如果无法解析则尝试其他 SPI 注册的 Parser</li>
 *   <li>所有尝试均失败后返回 null</li>
 * </ol>
 *
 * @author CH
 * @since 2026/07/17
 */
@Slf4j
@Spi("auto")
public class AutoParser implements SpiderParser {

    /**
     * 可用的 Parser 实现列表，按优先级排序
     */
    private final List<SpiderParser> parsers;

    /**
     * 默认构造器，从 SPI 收集所有可用的 Parser 实现。
     *
     * <p>注意：不能使用 {@code collectNew()} 收集，因为 SPI 注册表中包含
     * AutoParser 自身，{@code collectNew()} 会递归实例化 AutoParser，
     * 导致无限递归（StackOverflowError）。因此这里直接遍历服务定义，
     * 显式排除自身后再实例化。</p>
     */
    public AutoParser() {
        ServiceProvider<SpiderParser> provider = ServiceProvider.of(SpiderParser.class);
        // 遍历所有服务定义，排除 AutoParser 自身，再实例化，避免递归死循环
        this.parsers = provider.getDefinitions(null).stream()
                .filter(definition -> null != definition.getImplClass()
                        && !AutoParser.class.isAssignableFrom(definition.getImplClass()))
                .map(definition -> (SpiderParser) definition.<SpiderParser>newInstance(provider.getServiceAutowire()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        log.info("AutoParser 加载了 {} 个 Parser 实现", parsers.size());
    }

    @Override
    public SpiderResult parse(SpiderResponse response) {
        if (parsers.isEmpty()) {
            log.warn("没有可用的 Parser SPI 实现");
            return null;
        }

        String url = response.getRequest() != null ? response.getRequest().getUrl() : "unknown";

        // 按优先级依次尝试，直到某个成功
        for (int i = 0; i < parsers.size(); i++) {
            SpiderParser parser = parsers.get(i);
            try {
                log.debug("AutoParser 尝试第 {} 个实现 ({}): {}",
                        i + 1, parser.getClass().getSimpleName(), url);
                SpiderResult result = parser.parse(response);

                if (result != null) {
                    log.info("AutoParser 第 {} 个实现 ({}) 成功: {}",
                            i + 1, parser.getClass().getSimpleName(), url);
                    return result;
                }
            } catch (Exception e) {
                log.warn("AutoParser 第 {} 个实现 ({}) 异常: {} - {}",
                        i + 1, parser.getClass().getSimpleName(), url, e.getMessage());
            }
        }

        log.warn("AutoParser 所有实现均无法解析: {}", url);
        return null;
    }

    @Override
    public String[] supportedContentTypes() {
        // // 支持所有类型，由内部 Parser 决定
        return new String[] {"*/*"};
    }
}
