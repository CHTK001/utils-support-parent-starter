package com.chua.spider.support.config;

import com.chua.spider.support.config.model.SpiderDefinition;
import com.chua.spider.support.config.store.SpiderDefinitionStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 爬虫定义 REST 接口。
 *
 * <p>提供爬虫定义的增删改查、启停切换能力，适配前端爬虫中心页面。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
@RequestMapping("/spider/definitions")
public class SpiderController {

    /**
     * 爬虫定义存储
     */
    private final SpiderDefinitionStore store;

    /**
     * 构造爬虫控制器。
     *
     * @param store 爬虫定义存储
     */
    public SpiderController(SpiderDefinitionStore store) {
        this.store = store;
    }

    /**
     * 分页查询爬虫定义。
     *
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @param keyword  关键字（可选）
     * @return 分页结果
     */
    @GetMapping("/page")
    public Result<SpiderDefinitionStore.PageResult<SpiderDefinition>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return ok(store.page(pageNo, pageSize, keyword));
    }

    /**
     * 查询爬虫定义详情。
     *
     * @param spiderCode 爬虫编码
     * @return 爬虫定义，不存在时返回 空
     */
    @GetMapping("/detail")
    public Result<SpiderDefinition> detail(@RequestParam String spiderCode) {
        return ok(store.get(spiderCode));
    }

    /**
     * 保存或更新爬虫定义。
     *
     * @param definition 爬虫定义
     * @return 保存后的爬虫定义
     */
    @PostMapping("/save")
    public Result<SpiderDefinition> save(@RequestBody SpiderDefinition definition) {
        return ok(store.save(definition));
    }

    /**
     * 删除爬虫定义。
     *
     * @param spiderCode 爬虫编码
     * @return 操作结果
     */
    @DeleteMapping("/delete")
    public Result<Map<String, Object>> delete(@RequestParam String spiderCode) {
        boolean removed = store.remove(spiderCode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", removed);
        return ok(result);
    }

    /**
     * 启停切换。
     *
     * @param spiderCode   爬虫编码
     * @param spiderStatus 状态（0 禁用 / 1 启用）
     * @return 操作结果
     */
    @PostMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam String spiderCode,
                                               @RequestParam int spiderStatus) {
        boolean updated = store.updateStatus(spiderCode, spiderStatus);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        return ok(result);
    }

    /**
     * 构建成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 统一响应
     */
    private static <T> Result<T> ok(T data) {
        return new Result<>("00000", data, "success", true);
    }

    /**
      * 统一响应结构，与前端 返回结果 类型一致。
     */
    public record Result<T>(
            String code,
            T data,
            String msg,
            boolean success
    ) {
    }
}