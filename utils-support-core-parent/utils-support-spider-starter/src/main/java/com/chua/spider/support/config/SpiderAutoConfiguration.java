package com.chua.spider.support.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * 爬虫模块自动配置。
 *
 * <p>当类路径存在 Spring Web 时自动注册爬虫 CRUD 控制器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Configuration
@ConditionalOnClass(RestController.class)
public class SpiderAutoConfiguration {

    /**
     * 注册爬虫定义控制器。
     *
     * @return 爬虫控制器
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    public SpiderController spiderController() {
        return new SpiderController();
    }
}