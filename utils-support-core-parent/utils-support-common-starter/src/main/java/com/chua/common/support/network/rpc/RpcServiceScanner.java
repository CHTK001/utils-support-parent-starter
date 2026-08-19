package com.chua.common.support.network.rpc;

import com.chua.common.support.utils.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * RPC 服务注册工具，通过 {@link RpcService} 注解信息向 {@link RpcServer} 注册服务实例。
 *
 * @author CH
 * @since 1.0.0
 */
public class RpcServiceScanner {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(RpcServiceScanner.class);
    /** 服务器 */
    private final RpcServer server;

    /**
     * 创建 RpcServiceScanner 实例
     * @param server server
     */
    private RpcServiceScanner(RpcServer server) {
        this.server = server;
    }

    /** Of */
    public static RpcServiceScanner of(RpcServer server) {
        return new RpcServiceScanner(server);
    }

    /** 注册 */
    public void register(Object... beans) {
        for (Object bean : beans) {
            RpcService annotation = bean.getClass().getAnnotation(RpcService.class);
            String name = resolveName(bean.getClass(), annotation);
            server.register(name, bean);
            log.info("Registered @RpcService: {} -> {}", name, bean.getClass().getName());
        }
    }

    /** 解析Name */
    private String resolveName(Class<?> implClass, RpcService annotation) {
        if (annotation != null) {
            if (annotation.interfaceClass() != void.class) {
                return annotation.interfaceClass().getTypeName();
            }
            if (!annotation.interfaceName().isEmpty()) {
                return annotation.interfaceName();
            }
        }
        Set<Class<?>> interfaces = ClassUtils.getAllInterfaces(implClass);
        if (!interfaces.isEmpty()) {
            return interfaces.iterator().next().getTypeName();
        }
        return implClass.getName();
    }
}