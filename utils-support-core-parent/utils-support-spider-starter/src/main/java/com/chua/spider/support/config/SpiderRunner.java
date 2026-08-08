package com.chua.spider.support.config;

import com.chua.spider.support.Spider;
import com.chua.spider.support.config.model.SpiderDefinition;
import com.chua.spider.support.config.model.SpiderExecutionRecord;
import com.chua.spider.support.config.store.SpiderDefinitionStore;
import com.chua.spider.support.config.store.SpiderExecutionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        var record = newRecord(definition);
        executionStore.save(record);

        var worker = new Thread(() -> runJob(definition, record),
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
            var spider = Spider.create()
                    .fetcher("http")
                    .parser("html")
                    .addRequest(requestFactory.build(definition, definition.getSpiderEntryUrl()))
                    .threads(DEFAULT_THREADS)
                    .pipeline("console")
                    .build();
            var results = spider.runSync();
            log.info("[SpiderRunner] runSync 完成 spiderCode={} results.size={}",
                    definition.getSpiderCode(), results == null ? -1 : results.size());

            record.setStatus("SUCCESS");
            record.setEndTime(LocalDateTime.now());
            record.setResultCount(results == null ? 0 : results.size());
            String json = serializeResults(results);
            record.setResultsJson(json);
            log.info("[SpiderRunner] setResultsJson={}",
                    json == null ? "<null>" : json.substring(0, Math.min(100, json.length())));
        } catch (Exception e) {
            log.error("[SpiderRunner] 执行失败 spiderCode={}", definition.getSpiderCode(), e);
            record.setStatus("FAILED");
            record.setEndTime(LocalDateTime.now());
            record.setErrorMessage(e.getMessage());
        } finally {
            executionStore.save(record);
        }
    }

    /**
     * 将 SpiderResult 列表序列化为 JSON 字符串。
     *
     * <p>仅保留抓取结果的核心字段（标题/URL/正文/结构化/链接），避免序列化整个
     * DOM 树等大字段导致内存爆炸。</p>
     */
    private String serializeResults(List<com.chua.spider.support.model.SpiderResult> results) {
        if (results == null || results.isEmpty()) {
            return "[]";
        }
        try {
            var mapper = new ObjectMapper();
            var slim = new ArrayList<Map<String, Object>>();
            for (var r : results) {
                var item = new LinkedHashMap<String, Object>();
                item.put("title", r.getTitle());
                item.put("url", r.getUrl());
                item.put("depth", r.getDepth());
                item.put("text", r.getText());
                item.put("structured", r.getStructured());
                item.put("links", r.getLinks());
                item.put("aiSummary", r.getAiSummary());
                slim.add(item);
            }
            return mapper.writeValueAsString(slim);
        } catch (Exception e) {
            log.warn("[SpiderRunner] 结果序列化失败", e);
            return "[]";
        }
    }

    /**
     * 创建新的执行记录（初始 RUNNING 状态）。
     *
     * @param definition 爬虫定义
     * @return 新记录
     */
    private SpiderExecutionRecord newRecord(SpiderDefinition definition) {
        var record = new SpiderExecutionRecord();
        record.setExecutionNo(executionStore.nextExecutionNo());
        record.setSpiderCode(definition.getSpiderCode());
        record.setStatus("RUNNING");
        record.setStartTime(LocalDateTime.now());
        return record;
    }
}
