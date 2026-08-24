package com.chua.osgi.support.register.impl;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.FrameworkBeanDefinition;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanSingletonRegistry;
import com.chua.common.support.osgi.OsgiLauncher;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiIgnore;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.util.*;

/**
 * OSGi Bean 定义注册器（只读）。
 *
 * <p>委托注入的 {@link OsgiLauncher} 获取 OSGi 框架，所有查询直接委派
 * OSGi 服务注册表。Bean 实例由 Felix OSGi 容器管理，本注册器仅做桥接。</p>
 *
 * <p>标记为 {@link SpiIgnore}，不参与 SPI 自动注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SpiIgnore
@SpiDescribe("OSGi Bean 定义注册器（只读，委托 Felix OSGi 框架）")
public class OsgiBeanDefinitionRegister extends BeanSingletonRegistry implements BeanDefinitionRegister {

    /** closed */
    private volatile boolean closed;
    /** osgiLauncher */
    private volatile OsgiLauncher osgiLauncher;

    /**
     * 设置 OSGi 启动器（由 Spring 注入，替代静态持有）。
     *
     * @param osgiLauncher OSGi 启动器
     */
    public void setOsgiLauncher(OsgiLauncher osgiLauncher) {
        this.osgiLauncher = osgiLauncher;
    }

    @Override
    /** 获取Name */
    public String getName() {
        return "osgi";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 100;
    }

    @Override
    /** 是否Support */
    public boolean isSupport(BeanDefinition beanDefinition) {
        return false;
    }

    @Override
    /** 是否Writable */
    public boolean isWritable() {
        return false;
    }

    @Override
    /** 注册 */
    public boolean register(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("OSGi Bean 定义注册器不支持手动注册");
    }

    @Override
    /** 注销 */
    public boolean unregister(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("OSGi Bean 定义注册器不支持手动注销");
    }

    @Override
    /** 注销 */
    public boolean unregister(String beanName) {
        throw new UnsupportedOperationException("OSGi Bean 定义注册器不支持手动注销");
    }

    @Override
    /** 初始化 */
    public void initialize() {
        closed = false;
    }

    @Override
    /** 获取BeanDefinition */
    public BeanDefinition getBeanDefinition(String beanName) {
        if (beanName == null || closed) {
            return null;
        }
        OsgiLauncher launcher = this.osgiLauncher;
        if (launcher == null || !launcher.isActive()) {
            return null;
        }
        try {
            String[] parts = beanName.split(":", 2);
            if (parts.length < 2) {
                return null;
            }
            Class<?> type = ReflectUtils.forName(parts[1]);
            Object instance = launcher.getService(type);
            if (instance == null) {
                return null;
            }
            return new FrameworkBeanDefinition(beanName, type, instance);
        } catch (ClassNotFoundException e) {
            log.debug("[osgi-impl] OSGi 服务类型不存在: {}", beanName, e);
            return null;
        }
    }

    @Override
    /** 获取BeanDefinitionOfType */
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        OsgiLauncher launcher = this.osgiLauncher;
        if (launcher == null || !launcher.isActive()) {
            return Collections.emptyList();
        }
        try {
            Class<?> type = ReflectUtils.forName(typeName);
            List<?> services = launcher.getServices(type);
            if (services == null || services.isEmpty()) {
                return Collections.emptyList();
            }
            List<BeanDefinition> result = new ArrayList<>(services.size());
            for (Object svc : services) {
                String name = typeName + ":" + System.identityHashCode(svc);
                result.add(new FrameworkBeanDefinition(name, type, svc));
            }
            return result;
        } catch (ClassNotFoundException e) {
            return Collections.emptyList();
        }
    }

    @Override
    /** 获取BeanDefinitionOfType */
    public Collection<BeanDefinition> getBeanDefinitionOfType(String name, String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        if (name != null) {
            BeanDefinition def = getBeanDefinition(name);
            if (def != null && typeName.equals(def.getType())) {
                return List.of(def);
            }
            return Collections.emptyList();
        }
        return getBeanDefinitionOfType(typeName);
    }

    @Override
    /** ContainsBean */
    public boolean containsBean(String beanName) {
        if (beanName == null || closed) {
            return false;
        }
        return getBeanDefinition(beanName) != null;
    }

    @Override
    /** 获取BeanDefinitionNames */
    public Collection<String> getBeanDefinitionNames() {
        return Collections.emptyList();
    }

    @Override
    /** 获取BeansWithAnnotation */
    public Map<String, BeanDefinition> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        return Collections.emptyMap();
    }

    @Override
    /** 获取BeansWithMethodAnnotation */
    public Map<String, BeanDefinition> getBeansWithMethodAnnotation(Class<? extends Annotation> annotationType) {
        return Collections.emptyMap();
    }

    @Override
    /** 关闭 */
    public void close() {
        closed = true;
        destroySingletons();
    }

    @Override
    /** 是否Closed */
    public boolean isClosed() {
        return closed;
    }
}
