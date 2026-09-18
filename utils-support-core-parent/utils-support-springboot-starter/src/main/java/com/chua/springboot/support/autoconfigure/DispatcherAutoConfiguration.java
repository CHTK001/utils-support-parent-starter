package com.chua.springboot.support.autoconfigure;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import com.chua.common.support.concurrent.dispatcher.provider.MemoryDispatcherProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
* 分发器自动配置。
*
* @author CH
* @since 2026/07/24
 */
@AutoConfiguration
public class DispatcherAutoConfiguration {

    /**
    * 内存分发器提供者。
    *
    * @return DispatcherProvider 实例
    */
    @Bean
    @ConditionalOnMissingBean
    public DispatcherProvider memoryDispatcherProvider() {
        return new MemoryDispatcherProvider(
                DispatcherConfig.builder().build());
    }
}
