package com.chua.skywalking.support.config;

import com.chua.skywalking.support.client.SkywalkingSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * SkyWalking 自动配置。
 * <p>
 * 这里<b>不</b>注册 {@code OtnlEngine} Bean：引擎由
 * {@code ServiceProvider.of(OtnlEngine.class)} 通过 SPI 惰性创建。若再声明一个 Bean
 * 就会出现「两个实例、两份 HTTP 连接池」，而且 SPI 查到的那个还拿不到配置。
 * 所以自动配置只做一件事：把 {@code skywalking.*} 写进进程级设置
 * {@link SkywalkingSettings}，让 SPI 引擎读到同一份连接参数。
 * </p>
 * <p>
 * 不加 {@code @ConditionalOnProperty}：{@code skywalking.enabled=false} 时也要进容器，
 * 才能把「用户主动关闭」这一事实写进设置，前端因此能区分「没部署」和「被关掉」。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(SkywalkingProperties.class)
public class SkywalkingAutoConfiguration implements InitializingBean {

    private final SkywalkingProperties properties;

    /**
     * 构造配置
     *
     * @param properties SkyWalking 配置
     */
    public SkywalkingAutoConfiguration(SkywalkingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        SkywalkingSettings.apply(properties.getUrl(), properties.getUsername(), properties.getPassword(),
                properties.getTimeout(), properties.isEnabled());
        if (properties.isEnabled()) {
            log.info("[SkyWalking] OAP 地址: {} (超时 {} ms)", SkywalkingSettings.url(), SkywalkingSettings.timeout());
        } else {
            log.info("[SkyWalking] 已通过 skywalking.enabled=false 关闭");
        }
    }
}
