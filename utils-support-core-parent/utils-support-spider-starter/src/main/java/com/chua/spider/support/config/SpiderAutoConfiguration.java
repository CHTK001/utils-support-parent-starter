package com.chua.spider.support.config;

import com.chua.spider.support.config.store.SpiderProxyPoolStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爬虫模块自动配置（含代理池）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Configuration
@ConditionalOnClass(RestController.class)
public class SpiderAutoConfiguration {

    /**
     * 代理池内存存储（单例 Bean，可注入到 SpiderRequestFactory）。
     */
    @Bean
    public SpiderProxyPoolStore spiderProxyPoolStore() {
        return new SpiderProxyPoolStore();
    }

    /**
     * 爬虫请求工厂：基于 SpiderDefinition + 代理池生成 SpiderRequest。
     */
    @Bean
    public SpiderRequestFactory spiderRequestFactory(SpiderProxyPoolStore store) {
        return new SpiderRequestFactory(store);
    }

    @Bean
    public SpiderController spiderController() {
        return new SpiderController();
    }

    @Bean
    public SpiderProxyPoolController spiderProxyPoolController() {
        return new SpiderProxyPoolController();
    }
}