package com.chua.spider.support;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.common.support.task.deduplicate.Deduplicator;
import com.chua.common.support.task.deduplicate.MemoryDeduplicator;
import com.chua.common.support.task.scheduler.JdkSchedulerProvider;
import com.chua.common.support.task.scheduler.ScheduledTask;
import com.chua.common.support.task.scheduler.Trigger;
import com.chua.spider.support.extractor.HtmlLinkExtractor;
import com.chua.spider.support.fetcher.HttpFetcher;
import com.chua.spider.support.mapper.SpiderMappingPipeline;
import com.chua.spider.support.model.SpiderRequest;
import com.chua.spider.support.model.SpiderResponse;
import com.chua.spider.support.model.SpiderResult;
import com.chua.spider.support.model.SpiderSite;
import com.chua.spider.support.parser.AutoParser;
import com.chua.spider.support.pipeline.ConsolePipeline;
import com.chua.spider.support.scheduler.FifoScheduler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 默认爬虫实现。
 * <p>
 * 内置全部默认实现，开箱即用。支持：
 * <ul>
 *     <li><b>多线程并发</b> — 通过 {@link Spider.Builder#threads(int)} 设置并发数</li>
 *     <li><b>自动重试</b> — 通过 {@link SpiderSite#getRetryTimes()} 配置重试次数</li>
 *     <li><b>自动降级</b> — fetcher("auto") / parser("auto") 逐个尝试 SPI 实现</li>
 *     <li><b>Pipeline 回调</b> — 结果通过 Pipeline 回调输出</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/17
 */
@Slf4j
@SpiDefault
@Spi(value = "default", order = 0)
public class DefaultSpider implements Spider {

    /**
     * 站点配置，包含超时、重试、深度限制等参数。
     */
    private final SpiderSite site;

    /**
     * 页面抓取器。
     */
    private final SpiderFetcher fetcher;

    /**
     * 页面解析器。
     */
    private final SpiderParser parser;

    /**
     * 链接提取器，用于从页面中发现新链接。
     */
    private final SpiderLinkExtractor linkExtractor;

    /**
     * URL 过滤链，用于拦截不允许抓取的链接。
     */
    private final List<SpiderUrlFilter> urlFilters;

    /**
     * 请求调度器。
     */
    private final SpiderScheduler scheduler;

    /**
     * URL 去重器。
     */
    private final Deduplicator deduplicator;

    /**
     * AI 解析器，用于对结果进行二次增强。
     */
    private final SpiderAiParser aiParser;

    /**
     * 结果处理管道列表。
     */
    private final List<SpiderPipeline> pipelines;

    /**
     * 种子 URL 列表。
     */
    private final List<String> seedUrls;

    /**
     * 工作线程数。
     */
    private final int threads;

    /**
     * 单请求最大重试次数。
     */
    private final int retryTimes;

    /**
     * 已成功抓取的结果列表。
     */
    private final List<SpiderResult> results;

    /**
     * 爬虫运行状态标志，保证只启动一次。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 工作线程池。
     */
    private ExecutorService executor;

    /**
     * 构造默认爬虫实例。
     *
     * @param builder 构建器
     */
    public DefaultSpider(DefaultSpiderBuilder builder) {
        this.site = builder.site;
        this.fetcher = builder.fetcher;
        this.parser = builder.parser;
        this.scheduler = builder.scheduler;
        this.deduplicator = builder.deduplicator;
        this.linkExtractor = builder.linkExtractor;
        this.urlFilters = builder.urlFilters != null ? builder.urlFilters : new ArrayList<>();
        this.aiParser = builder.aiParser;
        this.pipelines = builder.pipelines != null ? builder.pipelines : new ArrayList<>();
        this.seedUrls = builder.seedUrls != null ? builder.seedUrls : new ArrayList<>();
        this.threads = builder.threads > 0 ? builder.threads : 1;
        this.retryTimes = builder.site != null ? builder.site.getRetryTimes() : 3;
        this.results = new CopyOnWriteArrayList<>();
    }

    @Override
    public List<SpiderResult> runSync() {
        if (!running.compareAndSet(false, true)) {
            return Collections.emptyList();
        }
        try {
            initPipelines();
            enqueueSeedUrls();
            int maxPages = (site != null ? site.getMaxPages() : 0);
            processRequests(maxPages);
        } finally {
            shutdownPipelines();
            running.set(false);
        }
        var resultList = getResults();
        log.info("[runSync] 完成后 resultCount={}", resultList.size());
        return resultList;
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[spider] 爬虫已在运行中");
            return;
        }

        executor = Executors.newFixedThreadPool(threads);
        try {
            initPipelines();
            enqueueSeedUrls();
            int maxPages = (site != null ? site.getMaxPages() : 0);
            final CountDownLatch latch = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        processRequests(maxPages);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            shutdown();
        }
    }

    /**
     * 核心处理循环：从调度器取出请求并处理，直到队列为空或达到页数限制。
     *
     * @param maxPages 最大页面数，0 表示不限制
     */
    private void processRequests(int maxPages) {
        while (running.get()) {
            SpiderRequest request = scheduler.dequeue();
            if (request == null) {
                break;
            }

            if (!shouldProcess(request, maxPages)) {
                continue;
            }

            processRequestWithRetry(request);
            handleRequestInterval();
        }
    }

    /**
     * 初始化所有 Pipeline。
     */
    private void initPipelines() {
        for (SpiderPipeline pipeline : pipelines) {
            try {
                pipeline.init();
            } catch (Exception e) {
                log.warn("[spider] Pipeline 初始化失败", e);
            }
        }
    }

    /**
     * 将所有种子 URL 入队。
     */
    private void enqueueSeedUrls() {
        for (String url : seedUrls) {
            enqueueUrl(url, 0, null);
        }
    }

/**
 * 判断当前请求是否应继续处理。
     *
     * @param request  当前请求
     * @param maxPages 最大页面数
     * @return true 表示应继续处理
     */
    private boolean shouldProcess(SpiderRequest request, int maxPages) {
        if (site != null && site.getMaxDepth() >= 0
                && request.getDepth() > site.getMaxDepth()) {
            return false;
        }

        if (maxPages > 0 && results.size() >= maxPages) {
            running.set(false);
            return false;
        }

        return true;
    }

    /**
     * 处理请求后的间隔休眠。
     */
    private void handleRequestInterval() {
        if (site != null && scheduler.hasNext()) {
            try {
                Thread.sleep(site.getInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 销毁资源：执行 Pipeline 销毁 + 线程池关闭。
     */
    private void shutdown() {
        for (SpiderPipeline pipeline : pipelines) {
            try {
                pipeline.destroy();
            } catch (Exception e) {
                log.warn("[spider] Pipeline 销毁失败", e);
            }
        }

        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        running.set(false);
        log.info("[spider] 爬虫结束，共处理 {} 个页面", results.size());
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public ScheduledTask scheduler(Trigger trigger) {
        return new JdkSchedulerProvider().schedule(this::runSync, trigger);
    }

    private void shutdownPipelines() {
        for (SpiderPipeline pipeline : pipelines) {
            try {
                pipeline.destroy();
            } catch (Exception e) {
                log.warn("[spider] Pipeline 销毁失败", e);
            }
        }
    }

    @Override
    public Spider addUrl(String url) {
        enqueueUrl(url, 0, null);
        return this;
    }

    @Override
    public List<SpiderResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    /**
     * 带重试的请求处理。
     *
     * @param request 待处理的爬虫请求
     */
    private void processRequestWithRetry(SpiderRequest request) {
        for (int i = 0; i <= retryTimes; i++) {
            if (i > 0) {
                log.info("[spider] 重试 ({}/{}) {}", i, retryTimes, request.getUrl());
                try {
                    Thread.sleep(1000L * i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (processRequest(request)) {
                return;
            }
        }

        log.warn("[spider] 请求失败（已重试 {} 次）: {}", retryTimes, request.getUrl());
    }

    /**
     * 处理单个请求，成功返回 true。
     *
     * @param request 待处理的爬虫请求
     * @return 处理成功返回 true，否则返回 false
     */
    private boolean processRequest(SpiderRequest request) {
        try {
            SpiderResponse response = fetcher.fetch(request);
            if (!response.isSuccess()) {
                log.warn("[spider] 抓取失败: {} - {}", request.getUrl(), response.getError());
                return false;
            }

            SpiderResult result = parser.parse(response);
            if (result == null) {
                log.debug("[spider] 解析器无法处理: {} - {}", request.getUrl(), response.getContentType());
                return false;
            }

            // 回填爬取深度，供 Pipeline 回调区分列表页和详情页
            result.setDepth(request.getDepth());
            enrichWithAi(result, request);
            processWithPipelines(result, request);
            results.add(result);
            extractAndEnqueueLinks(response, request);
            return true;
        } catch (Exception e) {
            log.warn("[spider] 处理请求异常: {} - {}", request.getUrl(), e.getMessage());
            return false;
        }
    }

    /**
     * 使用 AI 解析器增强结果。
     *
     * @param result  解析结果
     * @param request 原始请求
     */
    private void enrichWithAi(SpiderResult result, SpiderRequest request) {
        if (aiParser == null) {
            return;
        }

        try {
            String summary = aiParser.summarize(result);
            if (summary != null && !summary.isEmpty()) {
                result.getMetadata().put("aiSummary", summary);
            }
        } catch (Exception e) {
            log.warn("[spider] AI 解析失败: {}", request.getUrl(), e);
        }
    }

    /**
     * 将结果传递给 Pipeline 处理。
     *
     * @param result  解析结果
     * @param request 原始请求
     */
    private void processWithPipelines(SpiderResult result, SpiderRequest request) {
        for (SpiderPipeline pipeline : pipelines) {
            try {
                pipeline.process(result);
            } catch (Exception e) {
                log.warn("[spider] Pipeline 异常: {}", request.getUrl(), e);
            }
        }
    }

    /**
     * 提取页面中的新链接并入队。
     *
     * @param response 抓取响应
     * @param request  当前请求
     */
    private void extractAndEnqueueLinks(SpiderResponse response, SpiderRequest request) {
        if (linkExtractor == null) {
            return;
        }

        List<String> links = linkExtractor.extract(response);
        for (String link : links) {
            enqueueUrl(link, request.getDepth() + 1, request.getUrl());
        }
    }

    /**
     * 将 URL 入队，经过过滤和去重后提交到调度器。
     *
     * @param url     待入队的 URL
     * @param depth   链接深度
     * @param referUrl 来源 URL
     */
    private void enqueueUrl(String url, int depth, String referUrl) {
        SpiderRequest request = SpiderRequest.builder()
                .url(url)
                .depth(depth)
                .referUrl(referUrl)
                .build();

        for (SpiderUrlFilter filter : urlFilters) {
            if (!filter.accept(request)) {
                return;
            }
        }

        if (deduplicator.isDuplicate(url)) {
            return;
        }

        deduplicator.markProcessed(url);
        scheduler.enqueue(request);
    }

    /**
     * 默认爬虫构建器。
     * <p>
     * 内置所有组件的默认实现，开箱即用。
     * 所有组件均可通过链式方法替换为自定义实现。
     *
 * @author CH
     */
    public static class DefaultSpiderBuilder implements Spider.Builder {

        /**
         * 站点配置。
         */
        private SpiderSite site;

        /**
         * 页面抓取器。
         */
        private SpiderFetcher fetcher;

        /**
         * 页面解析器。
         */
        private SpiderParser parser;

        /**
         * 链接提取器。
         */
        private SpiderLinkExtractor linkExtractor;

        /**
         * URL 过滤链。
         */
        private final List<SpiderUrlFilter> urlFilters = new ArrayList<>();

        /**
         * 请求调度器。
         */
        private SpiderScheduler scheduler;

        /**
         * URL 去重器。
         */
        private Deduplicator deduplicator;

        /**
         * AI 解析器。
         */
        private SpiderAiParser aiParser;

        /**
         * 结果处理管道列表。
         */
        private final List<SpiderPipeline> pipelines = new ArrayList<>();

        /**
         * 种子 URL 列表。
         */
        private final List<String> seedUrls = new ArrayList<>();

        /**
         * 工作线程数。
         */
        private int threads = 1;

        @Override
        public Builder site(SpiderSite site) {
            this.site = site;
            return this;
        }

        @Override
        public Builder fetcher(String name) {
            this.fetcher = ServiceProvider.of(SpiderFetcher.class).getNewExtension(name);
            if (this.fetcher == null) {
                log.warn("[spider] 未找到 Fetcher SPI: {}", name);
            }
            return this;
        }

        @Override
        public Builder fetcher(SpiderFetcher fetcher) {
            this.fetcher = fetcher;
            return this;
        }

        @Override
        public Builder parser(String name) {
            this.parser = ServiceProvider.of(SpiderParser.class).getNewExtension(name);
            if (this.parser == null) {
                log.warn("[spider] 未找到 Parser SPI: {}", name);
            }
            return this;
        }

        @Override
        public Builder parser(SpiderParser parser) {
            this.parser = parser;
            return this;
        }

        @Override
        public Builder linkExtractor(SpiderLinkExtractor linkExtractor) {
            this.linkExtractor = linkExtractor;
            return this;
        }

        @Override
        public Builder urlFilter(SpiderUrlFilter filter) {
            if (filter != null) {
                this.urlFilters.add(filter);
            }
            return this;
        }

        @Override
        public Builder scheduler(SpiderScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        @Override
        public Builder deduplicator(Deduplicator deduplicator) {
            this.deduplicator = deduplicator;
            return this;
        }

        @Override
        public Builder aiParser(String name, String apiKey) {
            this.aiParser = ServiceProvider.of(SpiderAiParser.class).getNewExtension(name, apiKey);
            if (this.aiParser == null) {
                log.warn("[spider] 未找到 AiParser SPI: {}", name);
            }
            return this;
        }

        @Override
        public Builder pipeline(SpiderPipeline pipeline) {
            if (pipeline != null) {
                this.pipelines.add(pipeline);
            }
            return this;
        }

        @Override
        public Builder pipeline(String name) {
            SpiderPipeline p = ServiceProvider.of(SpiderPipeline.class).getNewExtension(name);
            if (p != null) {
                this.pipelines.add(p);
            } else {
                log.warn("[spider] 未找到 Pipeline SPI: {}", name);
            }
            return this;
        }

        @Override
        public Builder pipeline(Consumer<SpiderResult> consumer) {
            if (consumer != null) {
                this.pipelines.add(new ConsumerPipeline(consumer));
            }
            return this;
        }

        @Override
        public <T> Builder as(Class<T> targetClass, Consumer<T> consumer) {
            if (targetClass != null && consumer != null) {
                try {
                    Class.forName("org.jsoup.Jsoup");
                    this.pipelines.add(SpiderMappingPipeline.of(targetClass, consumer));
                } catch (ClassNotFoundException e) {
                    log.warn("[spider] jsoup 不在类路径，无法使用 POJO 映射");
                }
            }
            return this;
        }

        @Override
        public <T> Builder as(Class<T> targetClass, String aiProvider,
                               String aiApiKey, Consumer<T> consumer) {
            if (targetClass != null && consumer != null) {
                try {
                    Class.forName("org.jsoup.Jsoup");
                    this.pipelines.add(SpiderMappingPipeline.of(
                            targetClass, consumer, aiProvider, aiApiKey));
                } catch (ClassNotFoundException e) {
                    log.warn("[spider] jsoup 不在类路径，无法使用 POJO 映射");
                }
            }
            return this;
        }

        @Override
        public Builder addRequest(SpiderRequest request) {
            if (request != null && request.getUrl() != null) {
                this.seedUrls.add(request.getUrl());
            }
            return this;
        }

        @Override
        public Builder addUrl(String url) {
            if (url != null && !url.isEmpty()) {
                this.seedUrls.add(url);
            }
            return this;
        }

        @Override
        public Builder threads(int threads) {
            this.threads = Math.max(1, threads);
            return this;
        }

        @Override
        public Spider build() {
            if (fetcher == null) {
                int timeout = site != null ? site.getTimeout() : 30000;
                fetcher = new HttpFetcher(timeout);
            }
            if (parser == null) {
                parser = ServiceProvider.of(SpiderParser.class).getNewDefaultExtension();
                // SPI 加载失败时回退到 AutoParser（自动降级）
                if (parser == null) {
                    parser = new AutoParser();
                }
            }
            if (scheduler == null) {
                scheduler = new FifoScheduler();
            }
            if (deduplicator == null) {
                deduplicator = new MemoryDeduplicator();
            }
            if (linkExtractor == null) {
                linkExtractor = ServiceProvider.of(SpiderLinkExtractor.class)
                        .getNewDefaultExtension();
                // SPI 加载失败时回退到 HtmlLinkExtractor
                if (linkExtractor == null) {
                    linkExtractor = new HtmlLinkExtractor();
                }
            }
            if (pipelines.isEmpty()) {
                pipelines.add(new ConsolePipeline());
            }
            return new DefaultSpider(this);
        }

        private static class ConsumerPipeline implements SpiderPipeline {
            private final Consumer<SpiderResult> consumer;

            ConsumerPipeline(Consumer<SpiderResult> consumer) {
                this.consumer = consumer;
            }

            @Override
            public void process(SpiderResult result) {
                consumer.accept(result);
            }
        }
    }
}
