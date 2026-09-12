package com.chua.common.support.spi.autowire;

import com.chua.common.support.utils.ClassUtils;

import java.util.LinkedList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 自动装配器实现类，负责将对象进行自动装配（如依赖注入等）。
* 支持 Spring 环境下的自动装配以及自定义的装配器链。
*
* @author CH
* @since 4.0.0.42
 */
public class AutoServiceAutowire implements ServiceAutowire {

    /**
    * 默认的单例实例，用于全局获取自动装配器
     */
    public static ServiceAutowire INSTANCE = new AutoServiceAutowire();
    
    /**
    * Spring application上下文 的全限定类名，用于检测 Spring 环境
     */
    static final String APPLICATION_CONTEXT = "org.springframework.context.ApplicationContext";
    
    /**
    * Spring Bean 工具类的全限定类名
     */
    public static final String UTILS = "com.chua.starter.common.support.configuration.SpringBeanUtils";

    /**
    * Spring 环境下的自动装配器实现类的全限定类名
     */
    private static final String SPRING_AUTO = "com.chua.spring.support.configuration.spi.SpringServiceAutowire";

    /**
    * 自定义/扩展的自动装配器列表，按顺序执行装配逻辑
     */
    public static final List<ServiceAutowire> AUTOWIRES = new LinkedList<>();

    /**
    * Spring 环境下的自动装配器实例，若存在 Spring 环境则初始化
     */
    private static ServiceAutowire springServiceAutowire;

    /**
    * 静态初始化块：
    * 1. 检测并加载 Spring 自动装配器（如果 类路径 中存在）。
    * 2. 注册默认的初始化感知自动装配器。
     */
    static {
        if (ClassUtils.isPresent(SPRING_AUTO)) {
            springServiceAutowire = ClassUtils.forObject(SPRING_AUTO);
        }
        AUTOWIRES.add(new InitializingAwareAutoServiceAutowire());
    }

    /**
    * 对给定的对象执行自动装配逻辑。
    * 优先执行 Spring 环境的装配，然后依次执行注册的自定义装配器链。
    *
    * @param object 需要被装配的目标对象
    * @return 装配完成后的对象，如果传入对象为 空 则返回 空
     */
    @Override
    public Object autowire(Object object) {
        if (null == object) {
            return null;
        }

        if (null != springServiceAutowire) {
            springServiceAutowire.autowire(object);
        }

        for (ServiceAutowire serviceAutowire : AUTOWIRES) {
            serviceAutowire.autowire(object);
        }
        return object;
    }

    /**
    * 根据指定的实现类创建并装配 Bean 实例。
    * 当前实现暂不支持直接创建，返回 空。
    *
    * @param implClass Bean 的实现类
    * @return 创建并装配后的 Bean 实例，当前默认返回 空
     */
    @Override
    public Object createBean(Class<?> implClass) {
        return null;
    }
}
