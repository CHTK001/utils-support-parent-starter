package com.chua.spider.support.config;

import com.chua.spider.support.config.store.SpiderDefinitionStore;
import com.chua.spider.support.config.store.SpiderExecutionStore;
import com.chua.spider.support.config.store.SpiderProxyPoolStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爬虫模块自动配置（含代理池 + 定时调度 + 执行）。
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
     * 执行记录存储（单例 Bean）。
     */
    @Bean
    public SpiderExecutionStore spiderExecutionStore() {
        return new SpiderExecutionStore();
    }

    /**
     * 代理节点连通性测试器。
     */
    @Bean
    public SpiderProxyTester spiderProxyTester(SpiderProxyPoolStore poolStore) {
        return new SpiderProxyTester(poolStore);
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
     * 爬虫执行器：根据 SpiderDefinition 启动后台线程执行爬虫。
     */
    @Bean
    public SpiderRunner spiderRunner(SpiderDefinitionStore defStore,
                                    SpiderExecutionStore execStore,
                                    SpiderRequestFactory requestFactory) {
        return new SpiderRunner(defStore, execStore, requestFactory);
    }

    /**
     * 爬虫定时调度服务：根据 spiderScheduleCron 触发任务。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public SpiderTimerService spiderTimerService(SpiderDefinitionStore defStore,
                                                SpiderRunner runner) {
        SpiderTimerService service = new SpiderTimerService(defStore);
        service.setTaskHandler(runner::start);
        return service;
    }

    @Bean
    /** SpiderController */
    public SpiderController spiderController(SpiderDefinitionStore store) {
        return new SpiderController(store);
    }

    @Bean
    /**
     * SpiderProxyPoolController
     * @param poolStore poolStore
     * @param poolTester poolTester
     * @param poolTester poolTester
     * @param execStore execStore
     * @param defStore defStore
     * @param runner runner
     * @param defStore defStore
     * @param runner runner
     */
    public SpiderProxyPoolController spiderProxyPoolController(
            SpiderProxyPoolStore poolStore, SpiderProxyTester poolTester) {
        return new SpiderProxyPoolController(poolStore, poolTester);
    }

    @Bean
    /**
     * SpiderExecutionController
     * @param execStore execStore
     * @param defStore defStore
     * @param runner runner
     */
    public SpiderExecutionController spiderExecutionController(SpiderExecutionStore execStore,
                                                              SpiderDefinitionStore defStore,
                                                              SpiderRunner runner) {
        return new SpiderExecutionController(execStore, defStore, runner);
    }
}