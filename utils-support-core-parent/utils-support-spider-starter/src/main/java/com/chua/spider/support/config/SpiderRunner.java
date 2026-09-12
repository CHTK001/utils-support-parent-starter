package com.chua.spider.support.config;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    * 默认整体超时时间（10 分钟）
     */
    private static final long DEFAULT_JOB_TIMEOUT_MS = 600_000;

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

    /**
    * 控制器 弱引用（不参与 Spring 注入循环）。
     */
    private volatile SpiderExecutionController controllerRef;

    /**
    * 由 控制器 调用，注入自己引用。
    * @param controller 控制器
     */
    public void setController(SpiderExecutionController controller) {
        this.controllerRef = controller;
    }

    /**
    * 仅供 控制器 调试接口使用：返回当前 控制器ref 是否为 空。
    * @return 获取控制器reffor调试的结果
     */
    public SpiderExecutionController getControllerRefForDebug() {
        return controllerRef;
    }

    /**
    * 创建 蜘蛛runner 实例
    * @param definitionStore definition存储
    * @param executionStore 执行存储
    * @param requestFactory 请求工厂
     */
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
    * <p>仅当 {@link SpiderDefinition#getSpiderStatus()} 为 1（启用）时才会真正执行，
    * 否则立即返回一条状态为 {@code REJECTED} 的占位记录并写库，便于前端区分"被拒绝"与"运行中"。</p>
    *
    * @param definition 爬虫定义
    * @return 执行记录（状态 RUNNING 或 REJECTED）
     */
    public SpiderExecutionRecord start(SpiderDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (!Integer.valueOf(1).equals(definition.getSpiderStatus())) {
            log.warn("[SpiderRunner] 拒绝执行已禁用爬虫 spiderCode={} status={}",
                    definition.getSpiderCode(), definition.getSpiderStatus());
            var rejected = newRecord(definition);
            rejected.setStatus("REJECTED");
            rejected.setEndTime(LocalDateTime.now());
            rejected.setErrorMessage("爬虫已禁用（spiderStatus=" + definition.getSpiderStatus() + "）");
            rejected.setResultCount(0);
            executionStore.save(rejected);
            return rejected;
        }

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
            // 构建站点配置，从定义中读取深度和页数限制
            var site = new com.chua.spider.support.model.SpiderSite();
            site.setMaxDepth(definition.getSpiderMaxDepth() != null ? definition.getSpiderMaxDepth() : -1);
            site.setMaxPages(definition.getSpiderMaxPages() != null ? definition.getSpiderMaxPages() : 0);
            site.setTimeout(30000);
            site.setRetryTimes(3);
            site.setInterval(1000);

            var spider = Spider.create()
                    .site(site)
                    .fetcher("http")
                    .parser("html")
                    .addRequest(requestFactory.build(definition, definition.getSpiderEntryUrl()))
                    .threads(DEFAULT_THREADS)
                    .pipeline("console")
                    .build();

 // 使用 completable期货 实现整体超时控制
            var future = CompletableFuture.supplyAsync(spider::runSync,
                    java.util.concurrent.CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS));
            var results = future.get(DEFAULT_JOB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.info("[SpiderRunner] runSync 完成 spiderCode={} results.size={}",
                    definition.getSpiderCode(), results == null ? -1 : results.size());

            record.setStatus("SUCCESS");
            record.setEndTime(LocalDateTime.now());
            int count = results == null ? 0 : results.size();
            record.setResultCount(count);
            String json = serializeResults(results);
            // 防御：保证 resultsJson 与 resultCount 一致。如果 resultsJson 解析失败或为空但 count>0，
 // 至少把空数组写进去，避免前端看到 空。
            if (StringUtils.isEmpty(json)) {
                json = "[]";
                log.warn("[SpiderRunner] resultsJson 为空，spiderCode={} count={}",
                        definition.getSpiderCode(), count);
            }
            record.setResultsJson(json);
            log.info("[SpiderRunner] setResultsJson len={} for spiderCode={}",
                    json.length(), definition.getSpiderCode());
 // 调试钩子（仅在 控制器 已注册时可用）
            if (controllerRef != null) {
                controllerRef.noteSaveState(record);
            }
        } catch (java.util.concurrent.TimeoutException e) {
            log.error("[SpiderRunner] 执行超时 spiderCode={} timeoutMs={}",
                    definition.getSpiderCode(), DEFAULT_JOB_TIMEOUT_MS);
            record.setStatus("FAILED");
            record.setEndTime(LocalDateTime.now());
            record.setErrorMessage("爬虫执行超时（" + DEFAULT_JOB_TIMEOUT_MS + "ms）");
            record.setResultCount(0);
            record.setResultsJson("[]");
        } catch (Exception e) {
            log.error("[SpiderRunner] 执行失败 spiderCode={}", definition.getSpiderCode(), e);
            record.setStatus("FAILED");
            record.setEndTime(LocalDateTime.now());
            record.setErrorMessage(e.getMessage());
            record.setResultCount(0);
            record.setResultsJson("[]");
        } finally {
            executionStore.save(record);
        }
    }

    /**
    * 将 蜘蛛结果 列表序列化为 JSON 字符串。
    *
    * <p>仅保留抓取结果的核心字段（标题/URL/正文/结构化/链接），避免序列化整个
    * DOM 树等大字段导致内存爆炸。</p>
    * @param results 结果
    * @return serialize结果的结果
     */
    private String serializeResults(List<com.chua.spider.support.model.SpiderResult> results) {
        if (CollectionUtils.isEmpty(results)) {
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
