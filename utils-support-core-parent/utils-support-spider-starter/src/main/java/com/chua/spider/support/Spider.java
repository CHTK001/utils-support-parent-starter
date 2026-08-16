package com.chua.spider.support;

import com.chua.common.support.task.scheduler.ScheduledTask;
import com.chua.common.support.task.scheduler.Trigger;
import com.chua.common.support.task.deduplicate.Deduplicator;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResult;
import com.chua.spider.support.model.SpiderSite;

import java.util.List;
import java.util.function.Consumer;

/**
 * 爬虫主入口 SPI 接口。
 *
 * <p>提供统一的爬虫编程入口，使用链式调用的 Builder 模式进行配置。
 *
 * <h3>核心组件职责</h3>
 * <ul>
 *   <li><b>Fetcher（抓取器）</b> — 去目标 URL 拿原始内容回来，不关心内容是什么</li>
 *   <li><b>Parser（解析器）</b> — 把拿回来的内容拆成结构化数据（标题、正文、meta）</li>
 *   <li><b>Pipeline（回调）</b> — 采集到数据后回调处理（存文件、存DB、打印）</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>核心组件（Fetcher、Parser、Pipeline）支持 SPI 动态加载和替换</li>
 *   <li>简单组件（Scheduler、Deduplicator、Filter）直接在设置中创建，无需 SPI</li>
 *   <li>支持 {@code fetcher("auto")} 自动降级，逐个尝试 SPI 实现直到成功</li>
 * </ul>
 *
 * <p>快速使用示例：
 * <pre>{@code
 * Spider.create()
 *     .addUrl("https://example.com")
 *     .run();
 * }</pre>
 *
 * <p>并发采集示例：
 * <pre>{@code
 * Spider.create()
 *     .addUrl("https://example.com")
 *     .threads(5)                     // 5 个线程并发
 *     .pipeline(result -> System.out.println(result.getTitle()))
 *     .run();
 * }</pre>
 *
 * <p>自动降级示例：
 * <pre>{@code
 * Spider.create()
 *     .addUrl("https://example.com")
 *     .fetcher("auto")               // 自动选择可用的抓取器
 *     .parser("auto")                // 自动选择可用的解析器
 *     .run();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Spider {

    /**
     * 创建爬虫构建器。
     *
     * <p>返回 {@link DefaultSpider.DefaultSpiderBuilder 默认构建器}，
     * 内置了所有组件的默认实现，可以直接调用 {@code .addUrl(url).run()} 开始爬取。
     *
     * @return 爬虫构建器
     */
    static Builder create() {
        return new DefaultSpider.DefaultSpiderBuilder();
    }

    /**
     * 启动爬虫。
     *
     * <p>执行完整的爬取生命周期：
     * <ol>
     *   <li><b>Scheduler</b> 出队 URL</li>
     *   <li><b>Fetcher</b> 去目标 URL 拿原始内容（HTTP / 浏览器渲染）</li>
     *   <li><b>Parser</b> 把原始内容解析成结构化数据（标题、正文、meta）</li>
     *   <li><b>AiParser</b> AI 增强（智能总结、分类、翻译）</li>
     *   <li><b>Pipeline</b> 回调处理采集到的数据（保存、打印、转发）</li>
     *   <li><b>LinkExtractor</b> 提取新链接继续爬取</li>
     * </ol>
     * 阻塞直至所有爬取任务完成。
     */
    void run();

    /**
     * 启动爬虫并同步返回所有结果。
     *
     * <p>等同于 {@code run()}，但额外返回已处理的结果列表。
     * 阻塞直至所有爬取任务完成后，将 {@link #getResults()} 返回。
     *
     * <p>使用示例：
     * <pre>{@code
     * List<SpiderResult> results = Spider.create()
     *     .addUrl("https://example.com")
     *     .runSync();
     * }</pre>
     *
     * @return 本次爬虫执行的全部结果
     */
    List<SpiderResult> runSync();

    /**
     * 使用 task/scheduler 模块驱动爬虫定时执行。
     *
     * <p>将 {@code spider.run()} 包装为 {@link Runnable}，
     * 委托给 {@link com.chua.common.support.task.scheduler.SchedulerProvider} 按 {@link Trigger} 策略调度。
     * 首次触发立即执行一次，后续按 Trigger 计算的下一个时间点继续执行。
     *
     * <p>使用示例：
     * <pre>{@code
     * // 每天凌晨 3 点执行
     * ScheduledTask task = Spider.create()
     *     .addUrl("https://news.example.com")
     *     .scheduler(new CronTrigger("0 0 3 * * ?"));
     *
     * // 取消调度
     * task.cancel();
     * }</pre>
     *
     * <p>注意：每次调度触发都会新建一个 Spider 实例执行单次爬取，
     * 不会复用上一次的调度状态（队列、去重等均隔离）。
     *
     * @param trigger 触发策略
     * @return 已调度的任务实例，可用于取消
     * @see com.chua.common.support.task.scheduler.JdkSchedulerProvider
     */
    ScheduledTask scheduler(Trigger trigger);

    /**
     * 停止爬虫。
     *
     * <p>优雅停止，等待当前正在处理的请求完成后退出。
     */
    void stop();

    /**
     * 添加待爬取 URL。
     *
     * @param url 待爬取的 URL
     * @return 当前爬虫实例
     */
    Spider addUrl(String url);

    /**
     * 获取爬取结果列表。
     *
     * @return 所有已处理的爬取结果
     */
    List<SpiderResult> getResults();

    /**
     * 爬虫构建器。
     *
     * <p>通过链式调用配置爬虫的各个组件，最后调用 {@link #build()} 或
     * {@link #run()} 完成构建并执行。所有组件都有内置默认实现，
     * 用户只需关注需要定制的部分。
     */
    interface Builder {

        /**
         * 设置站点配置。
         *
         * @param site 目标站点配置（域名、请求间隔、User-Agent、最大深度等）
         * @return 当前构建器
         */
        Builder site(SpiderSite site);

        /**
         * 设置 Fetcher 抓取器（SPI 方式）。
         *
         * <p>通过 SPI 名称选择，如：
         * <ul>
         *   <li>{@code "http"} — JDK HttpClient（默认，零依赖）</li>
         *   <li>{@code "playwright"} — 浏览器渲染（需要 playwright）</li>
         *   <li>{@code "auto"} — 自动降级，逐个尝试可用实现直到成功</li>
         * </ul>
         *
         * @param name SPI 名称
         * @return 当前构建器
         */
        Builder fetcher(String name);

        /**
         * 直接设置 Fetcher 抓取器实例。
         *
         * @param fetcher 抓取器实例
         * @return 当前构建器
         */
        Builder fetcher(SpiderFetcher fetcher);

        /**
         * 设置 Parser 解析器（SPI 方式）。
         *
         * <p>通过 SPI 名称选择，如：
         * <ul>
         *   <li>{@code "html"} — JSoup HTML 解析（需要 jsoup）</li>
         *   <li>{@code "playwright"} — 浏览器渲染解析（需要 playwright）</li>
         *   <li>{@code "auto"} — 自动降级，逐个尝试可用实现直到成功</li>
         * </ul>
         *
         * @param name SPI 名称
         * @return 当前构建器
         */
        Builder parser(String name);

        /**
         * 直接设置 Parser 解析器实例。
         *
         * @param parser 解析器实例
         * @return 当前构建器
         */
        Builder parser(SpiderParser parser);

        /**
         * 设置链接提取器。
         *
         * @param linkExtractor 链接提取器实例
         * @return 当前构建器
         */
        Builder linkExtractor(SpiderLinkExtractor linkExtractor);

        /**
         * 添加 URL 过滤器。
         *
         * <p>可多次调用添加多个过滤器，按添加顺序依次执行。
         *
         * @param filter URL 过滤器实例
         * @return 当前构建器
         */
        Builder urlFilter(SpiderUrlFilter filter);

        /**
         * 设置调度器。
         *
         * @param scheduler 调度器实例
         * @return 当前构建器
         */
        Builder scheduler(SpiderScheduler scheduler);

        /**
         * 设置去重器。
         *
         * @param deduplicator 去重器实例
         * @return 当前构建器
         */
        Builder deduplicator(Deduplicator deduplicator);

        /**
         * 设置 AI 解析器。
         *
         * <p>通过 SPI 名称选择，如 {@code "ai"}。需提供 API Key 用于 ChatClient。
         *
         * @param name   SPI 名称
         * @param apiKey AI 服务商 API Key
         * @return 当前构建器
         */
        Builder aiParser(String name, String apiKey);

        /**
         * 添加 Pipeline 处理器。
         *
         * <p>可多次调用添加多个 Pipeline，按添加顺序依次执行。
         *
         * @param pipeline Pipeline 实例
         * @return 当前构建器
         */
        Builder pipeline(SpiderPipeline pipeline);

        /**
         * 添加 Pipeline 处理器（SPI 方式）。
         *
         * <p>通过 SPI 名称选择，如 {@code "console"}（控制台输出，默认）。
         *
         * @param name SPI 名称
         * @return 当前构建器
         */
        Builder pipeline(String name);

        /**
         * 添加简单 Pipeline 处理器（Consumer 方式）。
         *
         * <p>使用 Lambda 表达式快速处理结果，方便调试和简单场景。
         *
         * @param consumer 结果处理器
         * @return 当前构建器
         */
        Builder pipeline(Consumer<SpiderResult> consumer);

        /**
         * 设置 POJO 映射（类型化输出）。
         *
         * <p>将采集到的数据自动映射为带 {@code @SpiderField} 注解的 POJO 类型，
         * 再通过回调返回给用户。支持 CSS 选择器和 AI 两种提取方式。
         *
         * <p>使用示例：
         * <pre>{@code
         * @SpiderField(selector = "h1.title")
         * private String title;
         *
         * @SpiderField(ai = "作者")
         * private String author;
         *
         * Spider.create()
         *     .addUrl("https://example.com")
         *     .as(Article.class, article -> {
         *         System.out.println(article.getTitle());
         *     })
         *     .run();
         * }</pre>
         *
         * @param targetClass 目标 POJO 类型
         * @param consumer    类型化回调
         * @param <T>         POJO 类型
         * @return 当前构建器
         */
        <T> Builder as(Class<T> targetClass, Consumer<T> consumer);

        /**
         * 设置 POJO 映射（AI 增强）。
         *
         * <p>使用 AI 从页面内容中提取结构化数据并映射到 POJO。
         * 需要 {@code @SpiderField(ai = "...")} 配合使用。
         *
         * @param targetClass 目标 POJO 类型
         * @param aiProvider  AI 服务商，如 "openai"、"deepseek"
         * @param aiApiKey    API Key
         * @param consumer    类型化回调
         * @param <T>         POJO 类型
         * @return 当前构建器
         */
        <T> Builder as(Class<T> targetClass, String aiProvider, String aiApiKey, Consumer<T> consumer);

        /**
         * 添加待爬取请求（精细控制）。
         *
         * <p>相比 {@link #addUrl(String)}，此方法允许对单个请求设置
         * 优先级、请求方法、请求体、自定义头等精细参数。
         *
         * @param request 完整的爬取请求配置
         * @return 当前构建器
         */
        Builder addRequest(SpiderRequest request);

        /**
         * 添加待爬取 URL。
         *
         * <p>使用默认参数创建一个 GET 请求并加入待爬取队列。
         * 如需精细控制请使用 {@link #addRequest(SpiderRequest)}。
         *
         * @param url 种子 URL
         * @return 当前构建器
         */
        Builder addUrl(String url);

        /**
         * 设置线程数。
         *
         * <p>并发爬取时的线程数量，默认 1。
         *
         * @param threads 线程数
         * @return 当前构建器
         */
        Builder threads(int threads);

        /**
         * 构建爬虫实例。
         *
         * @return Spider 实例
         */
        Spider build();

        /**
         * 构建并直接运行爬虫。
         *
         * <p>等同于 {@code build().run()}，方便一步到位的调用。
         */
        default void run() {
            build().run();
        }
    }
}
