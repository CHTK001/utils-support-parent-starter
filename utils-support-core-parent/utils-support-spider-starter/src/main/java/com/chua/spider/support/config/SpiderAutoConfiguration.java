package com.chua.spider.support.config;

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

    @Bean
    public SpiderController spiderController() {
        return new SpiderController();
    }

    @Bean
    public SpiderProxyPoolController spiderProxyPoolController() {
        return new SpiderProxyPoolController();
    }
}