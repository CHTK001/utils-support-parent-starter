package com.chua.ssh.support.parser;

import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ShellUrlServerFilter;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.UrlUtils;
import com.chua.ssh.support.annotations.ShellMethod;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 解析 {@link ShellMethod} 注解的 {@link ServerHandlerAnnotationParser} 实现。
 *
 * <p>从 {@link ObjectContext} 中查找标注了 {@link ShellMethod} 的 Bean，
 * 生成 Shell 命令路由处理器。类级注解作为命令前缀，方法级注解定义具体命令名。</p>
 *
 * <p>命令名拼接规则：类级前缀与方法级名用 {@code .} 连接，如 {@code @ShellMethod("/api")} + {@code @ShellMethod("user")} → {@code api.user}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("shell-method")
public class ShellMethodServerHandlerParser implements ServerHandlerAnnotationParser {

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 0;
    }

    @Override
    /** 解析 */
    public List<ServerHandler> parse(ObjectContext objectContext, ServerFilter serverFilter) {
        if (!(serverFilter instanceof ShellUrlServerFilter)) {
            return List.of();
        }
        List<ServerHandler> result = new ArrayList<>();
        if (objectContext == null) {
            return result;
        }

        Set<String> processedClasses = new HashSet<>();

 // 处理类级 @Shell方法 注解的 Bean
        Map<String, Object> classBeans = objectContext.getBeansWithAnnotation(ShellMethod.class);
        if (classBeans != null) {
            for (Map.Entry<String, Object> entry : classBeans.entrySet()) {
                Object bean = entry.getValue();
                Class<?> clazz = bean.getClass();
                processedClasses.add(clazz.getName());
                parseClassMethods(objectContext, clazz, extractClassPrefix(clazz), result);
            }
        }

 // 处理方法级 @Shell方法 注解（类上无注解时）
        List<com.chua.common.support.objects.definition.MethodDefinition> methodDefs =
                objectContext.getMethodWithAnnotation(ShellMethod.class);
        if (methodDefs != null) {
            for (com.chua.common.support.objects.definition.MethodDefinition md : methodDefs) {
                ShellMethod ann = md.getMethod().getAnnotation(ShellMethod.class);
                if (ann == null) {
                    continue;
                }
 // 跳过已被类级别处理过的 Bean，避免重复注册与 invoke 工厂方法异常
                Class<?> clazz = md.getParentBeanDefinition().getBean().getClass();
                if (!processedClasses.add(clazz.getName())) {
                    continue;
                }
                String commandName = ann.value().trim();
                if (commandName.isEmpty()) {
                    continue;
                }
                String path = UrlUtils.normalizePath(commandName);
                result.add(new ShellMethodServerHandler(
                        objectContext,
                        clazz,
                        md.getMethod(),
                        path,
                        ann.produce()
                ));
            }
        }

        return result;
    }

    /**
     * 解析类中所有方法级 {@link ShellMethod} 注解。
     *
     * @param objectContext 对象上下文
     * @param clazz         类类型
     * @param classPrefix   类级前缀（点号分隔）
     * @param result        处理器列表
     */
    private static void parseClassMethods(ObjectContext objectContext, Class<?> clazz,
                                          String classPrefix, List<ServerHandler> result) {
        for (Method method : clazz.getDeclaredMethods()) {
            ShellMethod ann = method.getAnnotation(ShellMethod.class);
            if (ann == null) {
                continue;
            }
            String commandName = ann.value().trim();
            if (commandName.isEmpty()) {
                continue;
            }
            String fullName = classPrefix.isEmpty() ? commandName : classPrefix + "." + commandName;
            String path = UrlUtils.normalizePath(fullName);
            result.add(new ShellMethodServerHandler(
                    objectContext,
                    clazz,
                    method,
                    path,
                    ann.produce()
            ));
        }
    }

    /**
     * 从类级别 {@link ShellMethod} 注解提取命令前缀。
     *
     * @param clazz 类
     * @return 点号分隔的前缀，无注解返回空字符串
     */
    private static String extractClassPrefix(Class<?> clazz) {
        ShellMethod classAnn = clazz.getAnnotation(ShellMethod.class);
        if (classAnn == null) {
            return "";
        }
        String value = classAnn.value().trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.replaceAll("/", ".").replaceAll("^\\.", "").replaceAll("\\.$", "");
    }

}
