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

    public SpiderProxyPoolController() {
        this.store = new SpiderProxyPoolStore();
    }

    @GetMapping("/page")
    public SpiderProxyPoolStore.PageResult<SpiderProxyPool> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return store.page(pageNo, pageSize, keyword);
    }

    @GetMapping("/detail")
    public SpiderProxyPool detail(@RequestParam String poolCode) {
        return store.get(poolCode);
    }

    @PostMapping("/save")
    public SpiderProxyPool save(@RequestBody SpiderProxyPool pool) {
        return store.save(pool);
    }

    @DeleteMapping("/delete")
    public Map<String, Object> delete(@RequestParam String poolCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", store.remove(poolCode));
        return result;
    }

    @PostMapping("/status")
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
}