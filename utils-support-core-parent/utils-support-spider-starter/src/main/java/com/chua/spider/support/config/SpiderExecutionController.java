package com.chua.spider.support.config;

import com.chua.spider.support.config.model.SpiderDefinition;
import com.chua.spider.support.config.model.SpiderExecutionRecord;
import com.chua.spider.support.config.store.SpiderDefinitionStore;
import com.chua.spider.support.config.store.SpiderExecutionStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 爬虫执行记录 REST 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
@RequestMapping("/spider/executions")
public class SpiderExecutionController {

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

    public SpiderExecutionController(SpiderExecutionStore executionStore,
                                     SpiderDefinitionStore definitionStore,
                                     SpiderRunner runner) {
        this.executionStore = executionStore;
        this.definitionStore = definitionStore;
        this.runner = runner;
    }

    /**
     * 触发爬虫执行（异步）。
     *
     * @param spiderCode 爬虫编码
     * @return 执行记录（状态 RUNNING）
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam String spiderCode) {
        SpiderDefinition definition = definitionStore.get(spiderCode);
        if (definition == null) {
            return Map.of("error", "spiderCode 不存在: " + spiderCode);
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
}
