package com.chua.spider.support.config;

import com.chua.spider.support.config.model.SpiderDefinition;
import com.chua.spider.support.config.model.SpiderExecutionRecord;
import com.chua.spider.support.config.store.SpiderDefinitionStore;
import com.chua.spider.support.config.store.SpiderExecutionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 爬虫执行记录 REST 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@RestController
@RequestMapping("/spider/executions")
public class SpiderExecutionController {

    /**
      * 测试态：蜘蛛runner 在保存前回调通知，记录 哈希 与 json 长度。
     */
    private volatile int lastSavedHash = 0;
    /** 最后一个保存结果json */
    private volatile String lastSavedResultsJson = null;

    /**
     * 执行记录存储
     */
    private final SpiderExecutionStore executionStore;

    /**
     * 爬虫定义存储
     */
    private final SpiderDefinitionStore definitionStore;

    /**
     * 爬虫执行器
     */
    private final SpiderRunner runner;

    /**
      * 创建 蜘蛛执行控制器 实例
     * @param executionStore 执行存储
     * @param definitionStore definition存储
     * @param runner runner
     */
    public SpiderExecutionController(SpiderExecutionStore executionStore,
                                     SpiderDefinitionStore definitionStore,
                                     SpiderRunner runner) {
        this.executionStore = executionStore;
        this.definitionStore = definitionStore;
        this.runner = runner;
 // 把自己引用回填给 蜘蛛runner 用于 笔记保存状态 回调
        runner.setController(this);
    }

    /**
      * 蜘蛛runner 在 最终 调用，记录保存前 record 的 哈希 与 结果json。
     * @param r r
     */
    public void noteSaveState(SpiderExecutionRecord r) {
        this.lastSavedHash = System.identityHashCode(r);
        this.lastSavedResultsJson = r.getResultsJson();
        log.info("[debug-save] hash={} resultsJsonLen={}",
                lastSavedHash, lastSavedResultsJson == null ? -1 : lastSavedResultsJson.length());
    }

    /**
      * 调试接口：返回最近一次保存前的 哈希 与 json 长度。
     * @return 调试最后一个保存的结果
     */
    @GetMapping("/debug/last-save")
    public Map<String, Object> debugLastSave() {
        var ctrlRef = runner.getControllerRefForDebug();
        return Map.of(
                "hash", lastSavedHash,
                "resultsJson", lastSavedResultsJson == null ? "<null>" : lastSavedResultsJson.substring(0, Math.min(100, lastSavedResultsJson.length())),
                "resultsJsonLen", lastSavedResultsJson == null ? 0 : lastSavedResultsJson.length(),
                "ctrlRefNull", ctrlRef == null
        );
    }

    /**
     * 触发爬虫执行（异步）。
     *
     * <p>仅当爬虫状态为 1（启用）时才会真正启动；禁用状态返回 4xx 友好的错误对象。</p>
     *
     * @param spiderCode 爬虫编码
     * @return 执行记录（状态 RUNNING），或包含 {@code error} 字段的错误对象
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam String spiderCode) {
        SpiderDefinition definition = definitionStore.get(spiderCode);
        if (definition == null) {
            return Map.of("error", "spiderCode 不存在: " + spiderCode);
        }
        if (!Integer.valueOf(1).equals(definition.getSpiderStatus())) {
            return Map.of(
                    "error", "爬虫已禁用，无法执行",
                    "spiderCode", spiderCode,
                    "spiderStatus", definition.getSpiderStatus()
            );
        }
        SpiderExecutionRecord record = runner.start(definition);
        return Map.of(
                "executionNo", record.getExecutionNo(),
                "spiderCode", record.getSpiderCode(),
                "status", record.getStatus(),
                "startTime", String.valueOf(record.getStartTime())
        );
    }

    /**
     * 查询单条执行记录。
     *
     * @param executionNo 批次号
     * @return 执行记录
     */
    @GetMapping("/detail")
    public SpiderExecutionRecord detail(@RequestParam String executionNo) {
        return executionStore.get(executionNo);
    }

    /**
     * 分页查询执行记录。
     *
     * @param pageNo      页码
     * @param pageSize    每页条数
     * @param spiderCode  可选爬虫编码过滤
     * @return 分页结果
     */
    @GetMapping("/page")
    public SpiderExecutionStore.PageResult<SpiderExecutionRecord> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String spiderCode) {
        return executionStore.page(pageNo, pageSize, spiderCode);
    }

    /**
      * 调试接口：返回所有执行记录的内存快照（含 结果json 实际值）。
     * @return 调试列表全部的结果
     */
    @GetMapping("/debug/list-all")
    public List<Map<String, Object>> debugListAll() {
        return executionStore.listByCode(null).stream()
                .map(r -> {
                    var map = new java.util.LinkedHashMap<String, Object>();
                    map.put("executionNo", r.getExecutionNo());
                    map.put("hashCode", System.identityHashCode(r));
                    map.put("spiderCode", r.getSpiderCode());
                    map.put("status", r.getStatus());
                    map.put("resultCount", r.getResultCount());
                    map.put("resultsJsonLen", r.getResultsJson() == null ? 0 : r.getResultsJson().length());
                    map.put("resultsJson", r.getResultsJson());
                    // 反射读取字段值（绕过 getter 缓存）
                    Object rawResults = readField(r, "resultsJson");
                    map.put("rawResultsJsonLen", rawResults == null ? "NULL" : ((String) rawResults).length());
                    map.put("rawResultsJson", rawResults);
                    return (Map<String, Object>) map;
                })
                .toList();
    }

    /**
     * 读取字段
     *
     * @param target Target
     * @param name 名称
     * @return 读取字段的结果
     */
    private Object readField(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            return "<err:" + e.getMessage() + ">";
        }
    }
}
