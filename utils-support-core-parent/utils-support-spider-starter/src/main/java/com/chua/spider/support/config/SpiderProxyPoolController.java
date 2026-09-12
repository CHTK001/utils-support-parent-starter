package com.chua.spider.support.config;

import com.chua.spider.support.config.model.SpiderProxyPool;
import com.chua.spider.support.config.store.SpiderProxyPoolStore;
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
 * 代理池 REST 接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
@RequestMapping("/spider/proxy-pools")
public class SpiderProxyPoolController {

    /**
     * 代理池存储
     */
    private final SpiderProxyPoolStore store;

    /**
     * 代理节点连通性测试器
     */
    private final SpiderProxyProbe tester;

    /**
      * 创建 蜘蛛代理游泳池控制器 实例
     * @param store 存储
     * @param tester 蜘蛛代理探针
     * @param tester 测试
     */
    public SpiderProxyPoolController(SpiderProxyPoolStore store, SpiderProxyProbe tester) {
        this.store = store;
        this.tester = tester;
    }

    @GetMapping("/page")
    /**
     * Page
     * @param pageNo pageno
     * @param pageSize page大小
     * @param keyword keyword
     * @param pageSize page大小
     * @param keyword keyword
     * @param poolCode 游泳池编码
     * @param pool 游泳池
     * @param poolCode 游泳池编码
     * @param poolCode 游泳池编码
     * @param poolStatus 游泳池状态
     * @param true true
     * @param false false
     * @param poolCode 游泳池编码
     */
    public SpiderProxyPoolStore.PageResult<SpiderProxyPool> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return store.page(pageNo, pageSize, keyword);
    }

    @GetMapping("/detail")
    /**
     * Detail
     *
     * @param poolCode 游泳池编码
     * @return detail的结果
     */
    public SpiderProxyPool detail(@RequestParam String poolCode) {
        return store.get(poolCode);
    }

    @PostMapping("/save")
    /**
     * 保存
     *
     * @param pool 游泳池
     * @return 保存的结果
     */
    public SpiderProxyPool save(@RequestBody SpiderProxyPool pool) {
        return store.save(pool);
    }

    @DeleteMapping("/delete")
    /**
     * 删除
     *
     * @param poolCode 游泳池编码
     * @return 删除的结果
     */
    public Map<String, Object> delete(@RequestParam String poolCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", store.remove(poolCode));
        return result;
    }

    @PostMapping("/status")
    /**
      * 状态
     * @param poolCode 游泳池编码
     * @param poolStatus 游泳池状态
     */
    public Map<String, Object> status(@RequestParam String poolCode,
                                       @RequestParam int poolStatus) {
        SpiderProxyPool pool = store.get(poolCode);
        Map<String, Object> result = new LinkedHashMap<>();
        if (pool != null) {
            pool.setPoolStatus(poolStatus);
            result.put("updated", true);
        } else {
            result.put("updated", false);
        }
        return result;
    }

    /**
     * 测试代理池所有节点连通性。
     *
     * @param poolCode 代理池编码
     * @return 每个节点的测试结果 + 汇总
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestParam String poolCode) {
        return tester.testPool(poolCode);
    }
}
