package com.chua.spider.support.config;

import com.chua.spider.support.config.store.SpiderDefinitionStore;
import com.chua.spider.support.config.store.SpiderProxyPoolStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爬虫模块自动配置（含代理池 + 定时调度）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Configuration
@ConditionalOnClass(RestController.class)
public class SpiderAutoConfiguration {

    /**
     * 爬虫定义存储（单例 Bean，控制器与定时服务共享）。
     */
    @Bean
    public SpiderDefinitionStore spiderDefinitionStore() {
        return new SpiderDefinitionStore();
    }

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
    public SpiderRequestFactory spiderRequestFactory(SpiderProxyPoolStore proxyStore) {
        return new SpiderRequestFactory(proxyStore);
    }

    /**
     * 爬虫定时调度服务：根据 spiderScheduleCron 触发任务。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public SpiderTimerService spiderTimerService(SpiderDefinitionStore defStore) {
        return new SpiderTimerService(defStore);
    }

    @Bean
    public SpiderController spiderController(SpiderDefinitionStore store) {
        return new SpiderController(store);
    }

    @Bean
    public SpiderProxyPoolController spiderProxyPoolController() {
        return new SpiderProxyPoolController();
    }
}