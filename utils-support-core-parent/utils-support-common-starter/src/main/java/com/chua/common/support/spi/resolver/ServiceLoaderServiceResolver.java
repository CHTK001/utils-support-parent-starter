package com.chua.common.support.spi.resolver;

import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.spi.definition.ServiceDefinitionUtils;
import com.chua.common.support.utils.ClassUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 基于 Java 服务加载 的 SPI 服务解析器实现。
 *
 * <p>读取 META-INF/services 文件获取实现类名，构建 ServiceDefinition。
 * 不通过 服务加载 实例化对象（避免无参构造器要求），由 服务提供者 统一用构造参数创建实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ServiceLoaderServiceResolver implements ServiceResolver {

    @Override
    /** 解析 */
    public List<ServiceDefinition> resolve(Class<?> type, ClassLoader classLoader) {
        List<ServiceDefinition> result = new ArrayList<>();
        String fileName = "META-INF/services/" + type.getName();

        try {
            Enumeration<URL> resources = classLoader.getResources(fileName);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        Class<?> implClass = ClassUtils.forName(line, classLoader);
                        if (implClass != null && type.isAssignableFrom(implClass)) {
                            result.addAll(ServiceDefinitionUtils.buildDefinition(
                                    type, implClass, ServiceLoaderServiceResolver.class));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略扫描异常
        }

        return result;
    }
}
