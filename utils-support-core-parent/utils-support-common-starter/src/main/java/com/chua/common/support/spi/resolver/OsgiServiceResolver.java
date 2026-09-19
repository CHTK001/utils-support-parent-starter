package com.chua.common.support.spi.resolver;

import com.chua.common.support.osgi.OsgiLauncher;
import com.chua.common.support.osgi.OsgiLauncherHolder;
import com.chua.common.support.spi.definition.ServiceDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OSGI 服务解析器，从全局唯一的 OSGI 框架实例中获取 SPI 服务实现。
 * <p>
 * 通过 {@link OsgiLauncherHolder} 获取已激活的 OSGI 启动器，
 * 再调用 {@link OsgiLauncher#getServices(Class)} 查找与指定类型匹配的服务。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OsgiServiceResolver implements ServiceResolver {

    @Override
    /**
     * 解析
    */
    public List<ServiceDefinition> resolve(Class<?> type, ClassLoader classLoader) {
        OsgiLauncher launcher = OsgiLauncherHolder.getInstance();
        if (launcher == null || !launcher.isActive()) {
            return Collections.emptyList();
        }

        List<?> services = launcher.getServices(type);
        if (services.isEmpty()) {
            return Collections.emptyList();
        }

        List<ServiceDefinition> result = new ArrayList<>();
        for (Object service : services) {
            try {
                ServiceDefinition definition = new ServiceDefinition();
                String name = service.getClass().getSimpleName().toUpperCase();
                definition.setName(name);
                definition.setImplClass(service.getClass());
                definition.setType(type);
                definition.setClassLoader(classLoader);
                definition.setObj(service);
                definition.setLoaded(true);
                definition.setLoadTime(System.currentTimeMillis());
                definition.setFinderType(OsgiServiceResolver.class);
                result.add(definition);
            } catch (Exception e) {
                log.debug("Failed to create ServiceDefinition from OSGI service: {}", e.getMessage());
            }
        }
        return result;
    }

    @Override
    /**
     * 是否Dynamic
    */
    public boolean isDynamic() {
        return true;
    }
}
