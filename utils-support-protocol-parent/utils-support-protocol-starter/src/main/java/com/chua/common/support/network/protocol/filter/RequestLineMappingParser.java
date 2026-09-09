package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.OnRouterEvent;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.invoke.annotation.*;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.MappingDefinition;
import com.chua.common.support.objects.definition.TypeBeanDefinition;
import com.chua.common.support.objects.definition.UrlMappingDefinition;
import com.chua.common.support.objects.describe.MethodDescribe;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * RequestLine注解映射解析器
 * <p>
 * 用于解析RequestLine相关注解，包括：
 * - @RequestLine
 * - @GetRequestLine
 * - @PostRequestLine
 * - @PutRequestLine
 * - @DeleteRequestLine
 * - @OnRouterEvent
 *
 * 将这些注解转换为统一的MappingDefinition。
 *
 * @author CH
 * @since 2024/7/9
 */
@Slf4j
public class RequestLineMappingParser implements MappingParser {

    @Override
    public List<MappingDefinition> parse(Object mappingObject) {
        if (mappingObject == null) {
            return new ArrayList<>();
        }

        List<MappingDefinition> mappings = new ArrayList<>();
        Class<?> clazz = mappingObject.getClass();
        
        // 获取类级别的路径前缀
        String parentPath = getClassLevelPath(clazz);
        
        // 创建Bean定义
        BeanDefinition beanDefinition = TypeBeanDefinition.of(mappingObject.getClass());
        
        // 扫描所有方法
        Method[] methods = clazz.getDeclaredMethods();
        for (Method method : methods) {
            if (supports(method)) {
                MappingDefinition mapping = parseMethod(method, mappingObject);
                if (mapping != null) {
                    mappings.add(mapping);
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("解析映射对象 {} 完成，找到 {} 个映射", clazz.getSimpleName(), mappings.size());
        }
        return mappings;
    }

    @Override
    public MappingDefinition parseMethod(Method method, Object mappingObject) {
        if (method == null || mappingObject == null) {
            return null;
        }

        Class<?> clazz = mappingObject.getClass();
        String parentPath = getClassLevelPath(clazz);
        MethodDescribe methodDescribe = new MethodDescribe(method);
        BeanDefinition beanDefinition = TypeBeanDefinition.of(clazz);

        // 解析 @OnRouterEvent
        if (method.isAnnotationPresent(OnRouterEvent.class)) {
            OnRouterEvent annotation = method.getAnnotation(OnRouterEvent.class);
            String[] values = annotation.value();
            String value = (values != null && values.length > 0) ? values[0] : "";
            return new UrlMappingDefinition(
                    parentPath,
                    value,
                    new HttpMethod[]{HttpMethod.GET},
                    methodDescribe,
                    null,
                    beanDefinition,
                    annotation.order()
            );
        }

        // 解析 @RequestLine
        if (method.isAnnotationPresent(RequestLine.class)) {
            RequestLine annotation = method.getAnnotation(RequestLine.class);
            String[] values = annotation.value();
            String value = (values != null && values.length > 0) ? values[0] : "";
            String[] produces = annotation.produces();
            String produce = (produces != null && produces.length > 0) ? produces[0] : null;
            return new UrlMappingDefinition(
                    parentPath,
                    value,
                    annotation.methods(),
                    methodDescribe,
                    produce,
                    beanDefinition,
                    annotation.order()
            );
        }

        // 解析 @GetRequestLine
        if (method.isAnnotationPresent(GetRequestLine.class)) {
            GetRequestLine annotation = method.getAnnotation(GetRequestLine.class);
            String[] values = annotation.value();
            String value = (values != null && values.length > 0) ? values[0] : "";
            String[] produces = annotation.produces();
            String produce = (produces != null && produces.length > 0) ? produces[0] : null;
            return new UrlMappingDefinition(
                    parentPath,
                    value,
                    new HttpMethod[]{HttpMethod.GET},
                    methodDescribe,
                    produce,
                    beanDefinition,
                    0
            );
        }

        // 解析 @PostRequestLine
        if (method.isAnnotationPresent(PostRequestLine.class)) {
            PostRequestLine annotation = method.getAnnotation(PostRequestLine.class);
            String[] values = annotation.value();
            String value = (values != null && values.length > 0) ? values[0] : "";
            String[] produces = annotation.produces();
            String produce = (produces != null && produces.length > 0) ? produces[0] : null;
            return new UrlMappingDefinition(
                    parentPath,
                    value,
                    new HttpMethod[]{HttpMethod.POST},
                    methodDescribe,
                    produce,
                    beanDefinition,
                    0
            );
        }

        // 解析 @PutRequestLine
        if (method.isAnnotationPresent(PutRequestLine.class)) {
            PutRequestLine annotation = method.getAnnotation(PutRequestLine.class);
            String[] values = annotation.value();
            String value = (values != null && values.length > 0) ? values[0] : "";
            String[] produces = annotation.produces();
            String produce = (produces != null && produces.length > 0) ? produces[0] : null;
            return new UrlMappingDefinition(
                    parentPath,
                    value,
                    new HttpMethod[]{HttpMethod.PUT},
                    methodDescribe,
                    produce,
                    beanDefinition,
                    0
            );
        }

        // 解析 @DeleteRequestLine
        if (method.isAnnotationPresent(DeleteRequestLine.class)) {
            DeleteRequestLine annotation = method.getAnnotation(DeleteRequestLine.class);
            String[] values = annotation.value();
            String value = (values != null && values.length > 0) ? values[0] : "";
            String[] produces = annotation.produces();
            String produce = (produces != null && produces.length > 0) ? produces[0] : null;
            return new UrlMappingDefinition(
                    parentPath,
                    value,
                    new HttpMethod[]{HttpMethod.DELETE},
                    methodDescribe,
                    produce,
                    beanDefinition,
                    0
            );
        }

        return null;
    }

    @Override
    public boolean supports(Method method) {
        if (method == null) {
            return false;
        }

        return method.isAnnotationPresent(OnRouterEvent.class) ||
               method.isAnnotationPresent(RequestLine.class) ||
               method.isAnnotationPresent(GetRequestLine.class) ||
               method.isAnnotationPresent(PostRequestLine.class) ||
               method.isAnnotationPresent(PutRequestLine.class) ||
               method.isAnnotationPresent(DeleteRequestLine.class);
    }

    @Override
    public int getPriority() {
        return 50; // 较高优先级，作为默认解析器
    }

    @Override
    public String getName() {
        return "RequestLineMappingParser";
    }

    /**
     * 获取类级别的路径前缀
     */
    private String getClassLevelPath(Class<?> clazz) {
        // 检查是否有类级别的路径注解
        if (clazz.isAnnotationPresent(RequestLine.class)) {
            RequestLine annotation = clazz.getAnnotation(RequestLine.class);
            String[] values = annotation.value();
            if (values.length > 0 && !StringUtils.isEmpty(values[0])) {
                return values[0];
            }
        }
        
        // 可以扩展支持其他类级别的路径注解
        return "";
    }
}