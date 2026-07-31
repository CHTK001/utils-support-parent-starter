package com.chua.spring.support.objects.environment;

import com.chua.common.support.objects.environment.ConfigSourceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.spring.support.configuration.SpringBeanUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Spring 配置源提供者，将 Spring {@link org.springframework.core.env.Environment}
 * 中的 {@link org.springframework.core.env.PropertySource} 包装为框架配置源。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("spring")
public class SpringConfigSourceProvider implements ConfigSourceProvider {

    @Override
    public List<com.chua.common.support.config.source.PropertySource> getPropertySources() {
        List<com.chua.common.support.config.source.PropertySource> result = new ArrayList<>();
        try {
            org.springframework.core.env.Environment env = SpringBeanUtils.getEnvironment();
            if (env == null) {
                return result;
            }
            org.springframework.core.env.MutablePropertySources sources;
            if (env instanceof org.springframework.core.env.ConfigurableEnvironment ce) {
                sources = ce.getPropertySources();
            } else {
                return result;
            }
            Iterator<org.springframework.core.env.PropertySource<?>> it = sources.iterator();
            int priority = sources.size();
            while (it.hasNext()) {
                org.springframework.core.env.PropertySource<?> source = it.next();
                result.add(new SpringPropertySourceAdapter(source, priority--));
            }
        } catch (Exception e) {
            log.debug("Spring 环境不可用，跳过 Spring PropertySource 加载", e);
        }
        return result;
    }

    /**
     * Spring {@link org.springframework.core.env.PropertySource} 到框架
     * {@link com.chua.common.support.config.source.PropertySource} 的适配器。
     */
    private static class SpringPropertySourceAdapter implements com.chua.common.support.config.source.PropertySource {

        private final String name;
        private final org.springframework.core.env.PropertySource<?> delegate;
        private final int priority;

        SpringPropertySourceAdapter(org.springframework.core.env.PropertySource<?> delegate, int priority) {
            this.delegate = delegate;
            this.name = delegate.getName();
            this.priority = priority;
        }

        @Override
        public Object getProperty(String key) {
            try {
                return delegate.getProperty(key);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }
}
