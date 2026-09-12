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
     * @return 蜘蛛definition存储的结果
     */
    @Bean
    public SpiderDefinitionStore spiderDefinitionStore() {
        return new SpiderDefinitionStore();
    }

    /**
     * 执行记录存储（单例 Bean）。
     * @return 蜘蛛执行存储的结果
     */
    @Bean
    public SpiderExecutionStore spiderExecutionStore() {
        return new SpiderExecutionStore();
    }

    /**
     * 代理节点连通性测试器。
     * @param poolStore 游泳池存储
     * @return 蜘蛛代理探针的结果
     */
    @Bean
    public SpiderProxyProbe SpiderProxyProbe(SpiderProxyPoolStore poolStore) {
        return new SpiderProxyProbe(poolStore);
    }

    /**
      * 代理池内存存储（单例 Bean，可注入到 蜘蛛请求工厂）。
     * @return 蜘蛛代理游泳池存储的结果
     */
    @Bean
    public SpiderProxyPoolStore spiderProxyPoolStore() {
        return new SpiderProxyPoolStore();
    }

    /**
      * 爬虫请求工厂：基于 蜘蛛definition + 代理池生成 蜘蛛请求。
     * @param proxyStore 代理存储
     * @return 蜘蛛请求工厂的结果
     */
    @Bean
    public SpiderRequestFactory spiderRequestFactory(SpiderProxyPoolStore proxyStore) {
        return new SpiderRequestFactory(proxyStore);
    }

    /**
      * 爬虫执行器：根据 蜘蛛definition 启动后台线程执行爬虫。
     */
    @Bean
    public SpiderRunner spiderRunner(SpiderDefinitionStore defStore,
                                    SpiderExecutionStore execStore,
                                    SpiderRequestFactory requestFactory) {
        return new SpiderRunner(defStore, execStore, requestFactory);
    }

    /**
      * 爬虫定时调度服务：根据 蜘蛛调度cron 触发任务。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public SpiderTimerService spiderTimerService(SpiderDefinitionStore defStore,
                                                SpiderRunner runner) {
        SpiderTimerService service = new SpiderTimerService(defStore);
        service.setTaskHandler(runner::start);
        return service;
    }

    @Bean
    /**
     * 蜘蛛控制器
     *
     * @param store 存储
     * @return 蜘蛛控制器的结果
     */
    public SpiderController spiderController(SpiderDefinitionStore store) {
        return new SpiderController(store);
    }

    @Bean
    /**
      * 蜘蛛代理游泳池控制器
     * @param poolStore 游泳池存储
     * @param poolTester 游泳池测试
     * @param poolTester 游泳池测试
     * @param execStore 执行存储
     * @param defStore def存储
     * @param runner runner
     * @param defStore def存储
     * @param runner runner
     */
    public SpiderProxyPoolController spiderProxyPoolController(
            SpiderProxyPoolStore poolStore, SpiderProxyProbe poolTester) {
        return new SpiderProxyPoolController(poolStore, poolTester);
    }

    @Bean
    /**
      * 蜘蛛执行控制器
     * @param execStore 执行存储
     * @param defStore def存储
     * @param runner runner
     */
    public SpiderExecutionController spiderExecutionController(SpiderExecutionStore execStore,
                                                              SpiderDefinitionStore defStore,
                                                              SpiderRunner runner) {
        return new SpiderExecutionController(execStore, defStore, runner);
    }
}