package com.chua.webview.jcef.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.network.support.network.protocol.filter.MappingParser;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.MappingDefinition;
import com.chua.common.support.objects.definition.SingletonBeanDefinition;
import com.chua.common.support.objects.definition.UrlMappingDefinition;
import com.chua.common.support.objects.describe.MethodDescribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * IPC                      
 * <p>
 *        {@link IpcMethod}                          {@link MappingDefinition}   
 *        {@link IpcProtocolServer#restful()}          
 * </p>
 *
 * @author CH
 * @since 2025
 */
@Spi("ipc")
public class IpcMappingParser implements MappingParser {

    private static final Logger log = LoggerFactory.getLogger(IpcMappingParser.class);

    @Override
    public List<MappingDefinition> parse(Object mappingObject) {
        List<MappingDefinition> mappings = new ArrayList<>();
        if (mappingObject == null) {
            return mappings;
        }

        Class<?> clazz = mappingObject.getClass();
        BeanDefinition beanDefinition = SingletonBeanDefinition.of(mappingObject, clazz.getSimpleName());

        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(IpcMethod.class)) {
                IpcMethod annotation = method.getAnnotation(IpcMethod.class);
                MethodDescribe methodDescribe = new MethodDescribe(method);

                UrlMappingDefinition definition = new UrlMappingDefinition(
                        "",
                        annotation.value(),
                        annotation.method(),
                        methodDescribe,
                        "application/json",
                        beanDefinition,
                        0
);
                mappings.add(definition);

                log.debug("       IPC       : {} -> {}", method.getName(), annotation.value());
            }
        }

        return mappings;
    }

    @Override
    public MappingDefinition parseMethod(Method method, Object mappingObject) {
        if (method == null || !method.isAnnotationPresent(IpcMethod.class)) {
            return null;
        }

        IpcMethod annotation = method.getAnnotation(IpcMethod.class);
        MethodDescribe methodDescribe = new MethodDescribe(method);
        BeanDefinition beanDefinition = SingletonBeanDefinition.of(mappingObject, mappingObject.getClass().getSimpleName());

        return new UrlMappingDefinition(
                "",
                annotation.value(),
                annotation.method(),
                methodDescribe,
                "application/json",
                beanDefinition,
                0
);
    }

    @Override
    public boolean supports(Method method) {
        return method != null && method.isAnnotationPresent(IpcMethod.class);
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public String getName() {
        return "IpcMappingParser";
    }
}
