package com.chua.crypto.support.spring;

import com.chua.crypto.support.Crypto;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 系统加密 SpringBoot 自动装配
 *
 * <p>引入本模块并运行于 SpringBoot 环境时自动生效：
 * <ul>
 *   <li>注册全局单例 {@link Crypto}（应用内可直接注入）</li>
 *   <li>启动期由 {@link CryptoEnvironmentPostProcessor} 解密已加密配置文件与 ENC(...) 配置值</li>
 * </ul>
 *
 * <p>普通 Java / FatJar 非托管环境不经过此装配，直接使用链式 API 即可。
 *
 * @author CH
 * @since 2026-08-26
 */
@AutoConfiguration
@ConditionalOnClass(Crypto.class)
@ConditionalOnProperty(prefix = "chua.crypto", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoAutoConfiguration {

    /**
     * 注册系统加密门面 Bean
     *
     * @param properties chua.加密货币.* 配置
     * @return 已初始化的加密门面
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public Crypto crypto(CryptoProperties properties) {
        return Crypto.from(properties.toSetting()).initialize();
    }
}
