package com.chua.spider.support.config;

import com.chua.spider.support.Spider;
import com.chua.spider.support.config.model.SpiderDefinition;
import com.chua.spider.support.config.model.SpiderExecutionRecord;
import com.chua.spider.support.config.store.SpiderDefinitionStore;
import com.chua.spider.support.config.store.SpiderExecutionStore;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 爬虫执行服务。
 *
 * <p>根据 {@link SpiderDefinition} 启动后台线程执行爬虫，
 * 写入执行记录到 {@link SpiderExecutionStore}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SpiderRunner {

    /**
     * 默认线程数
     */
    private static final int DEFAULT_THREADS = 2;

    /**
     * 爬虫定义存储
     */
    private final SpiderDefinitionStore definitionStore;

    /**
     * 执行记录存储
     */
    private final SpiderExecutionStore executionStore;

    /**
     * 请求工厂
     */
    private final SpiderRequestFactory requestFactory;

    public SpiderRunner(SpiderDefinitionStore definitionStore,
                         SpiderExecutionStore executionStore,
                         SpiderRequestFactory requestFactory) {
        this.definitionStore = definitionStore;
        this.executionStore = executionStore;
        this.requestFactory = requestFactory;
    }

    /**
     * 启动爬虫执行（异步）。
     *
     * @param definition 爬虫定义
     * @return 执行记录（状态为 RUNNING）
     */
    public SpiderExecutionRecord start(SpiderDefinition definition) {
        SpiderExecutionRecord record = newRecord(definition);
        executionStore.save(record);

        Thread worker = new Thread(() -> runJob(definition, record),
                "spider-run-" + record.getExecutionNo());
        worker.setDaemon(true);
        worker.start();
        return record;
    }

    /**
     * 异步执行任务主体。
     *
     * @param definition 爬虫定义
     * @param record 执行记录
     */
    private void runJob(SpiderDefinition definition, SpiderExecutionRecord record) {
        try {
            Spider spider = Spider.create()
                    .fetcher("http")
                    .parser("html")
                    .addRequest(requestFactory.build(definition, definition.getSpiderEntryUrl()))
                    .threads(DEFAULT_THREADS)
                    .pipeline("console")
                    .build();
            spider.run();
            List<com.chua.spider.support.model.SpiderResult> results = spider.getResults();

            record.setStatus("SUCCESS");
            record.setEndTime(LocalDateTime.now());
            record.setResultCount(results == null ? 0 : results.size());
        } catch (Exception e) {
            log.error("[spider-config] 执行失败 spiderCode={}", definition.getSpiderCode(), e);
            record.setStatus("FAILED");
            record.setEndTime(LocalDateTime.now());
            record.setErrorMessage(e.getMessage());
        } finally {
            executionStore.save(record);
        }
    }

    /**
     * 创建新的执行记录（初始 RUNNING 状态）。
     *
     * @param definition 爬虫定义
     * @return 新记录
     */
    private SpiderExecutionRecord newRecord(SpiderDefinition definition) {
        SpiderExecutionRecord record = new SpiderExecutionRecord();
        record.setExecutionNo(executionStore.nextExecutionNo());
        record.setSpiderCode(definition.getSpiderCode());
        record.setStatus("RUNNING");
        record.setStartTime(LocalDateTime.now());
        return record;
    }
}
