package com.chua.spider.support.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 爬虫中心独立服务启动入口。
 *
 * <p>供本地联调使用，监听 19091 端口暴露 SpiderController / SpiderProxyPoolController 接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpringBootApplication
public class SpiderServerApp {

    /**
     * Main
     *
     * @param args 参数
     */
    public static void main(String[] args) {
        System.setProperty("logging.config", "");
        SpringApplication app = new SpringApplication(SpiderServerApp.class);
        app.setDefaultProperties(java.util.Map.of(
                "server.port", 19091,
                "spring.main.banner-mode", "off",
                "logging.level.root", "INFO"
        ));
        app.run(args);
    }
}
