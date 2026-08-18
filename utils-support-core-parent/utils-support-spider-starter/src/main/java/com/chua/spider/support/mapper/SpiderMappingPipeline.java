package com.chua.spider.support.mapper;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.spider.support.SpiderPipeline;
import com.chua.spider.support.model.SpiderResult;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * 爬虫映射管道。
 *
 * <p>将 {@link SpiderResult} 自动映射为类型化的 POJO，
 * 再交给用户回调处理。支持两种提取方式：
 * <ul>
 *   <li>{@code @SpiderField(selector = "...")} — CSS 选择器提取</li>
 *   <li>{@code @SpiderField(ai = "...")} — AI 提取（需提供 AI API Key）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * @SpiderField(selector = "h1.title")
 * private String title;
 *
 * Spider.create()
 *     .addUrl("https://example.com")
 *     .as(Article.class, article -> {
 *         System.out.println(article.getTitle());
 *     })
 *     .run();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@ConditionalOnClass("org.jsoup.Jsoup")
public class SpiderMappingPipeline<T> implements SpiderPipeline {

    /** 目标class */
    private final Class<T> targetClass;
    /** 消费者 */
    private final Consumer<T> consumer;
    /** Mapper */
    private final SpiderFieldMapper mapper;

    /**
     * 创建映射管道（不使用 AI）。
     *
     * @param targetClass 目标 POJO 类型
     * @param consumer    类型化回调
     * @param <T>         POJO 类型
     * @return 映射管道实例
     */
    public static <T> SpiderMappingPipeline<T> of(Class<T> targetClass, Consumer<T> consumer) {
        return new SpiderMappingPipeline<>(targetClass, consumer, null, null);
    }

    /**
     * 创建映射管道（使用 AI 提取）。
     *
     * @param targetClass 目标 POJO 类型
     * @param consumer    类型化回调
     * @param aiProvider  AI 服务商
     * @param aiApiKey    API Key
     * @param <T>         POJO 类型
     * @return 映射管道实例
     */
    public static <T> SpiderMappingPipeline<T> of(Class<T> targetClass, Consumer<T> consumer,
                                                  String aiProvider, String aiApiKey) {
        return new SpiderMappingPipeline<>(targetClass, consumer, aiProvider, aiApiKey);
    }

    /**
     * 构造器。
     */
    private SpiderMappingPipeline(Class<T> targetClass, Consumer<T> consumer,
                                  String aiProvider, String aiApiKey) {
        this.targetClass = targetClass;
        this.consumer = consumer;
        this.mapper = aiProvider != null
                ? new SpiderFieldMapper(aiProvider, aiApiKey)
                : new SpiderFieldMapper();
    }

    @Override
    public void process(SpiderResult result) {
        if (result == null || targetClass == null) {
            return;
        }
        T instance = mapper.map(result, targetClass);
        if (instance == null) {
            log.warn("[spider-mapper] POJO 映射失败: {}", targetClass.getName());
            return;
        }

        try {
            consumer.accept(instance);
        } catch (Exception e) {
            log.error("[spider-mapper] 映射回调异常: {}", targetClass.getName(), e);
        }
    }
}
