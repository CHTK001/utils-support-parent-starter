package com.chua.common.support.objects.definition;

import com.chua.common.support.proxy.ProxyProvider;
import com.chua.common.support.proxy.intercept.BridgingMethodIntercept;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.objects.exception.BeanDefinitionException;
import com.chua.common.support.objects.lifecycle.BeanDefinitionLifecycleManager;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.resolver.BeanConstructorResolver;
import com.chua.common.support.objects.scope.BeanScopeDetector;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Objects;

/**
* 类型 Bean 定义，统一处理单例和原型作用域。
*
* <p>基于 Java Class 的 BeanDefinition 实现，根据扫描注解自动判断作用域（默认单例）：
* <ul>
*   <li>单例：首次 {@link #getBean()} 创建实例并缓存，后续复用</li>
*   <li>原型：每次 {@link #getBean()} 创建新实例</li>
* </ul></p>
*
* <p>创建方式：
* <ul>
*   <li>{@link #of(Class)} — 从 Class 创建，自动检测作用域</li>
*   <li>{@link #of(Class, String)} — 从 Class 创建并指定 Bean 名称</li>
*   <li>{@link #of(Class, String, BeanDefinitionRegister)} — 创建并附加注册器</li>
* </ul></p>
*
* @author CH
* @since 2024/12/20
 */
@Slf4j
public class TypeBeanDefinition extends AbstractBeanDefinition {

    /**
    * 类加载器
     */
    @Setter
    /** Classloader */
    private ClassLoader classLoader;

    /**
    * 单例缓存实例
     */
    private volatile Object singletonInstance;

    // ==================== 工厂方法 ====================

    /**
    * 从 类 创建 类型Beandefinition，自动检测作用域。
    *
    * <p>Bean 名称默认取类名首字母小写（如 UserService -> userService）。
    * 如果类名为空字符串，则取全限定名。</p>
    *
    * @param beanClass Bean 类
    * @return TypeBeanDefinition 实例，Bean类 为 空 时返回 空
     */
    public static TypeBeanDefinition of(Class<?> beanClass) {
        if (beanClass == null) {
            return null;
        }
        TypeBeanDefinition def = new TypeBeanDefinition();
        def.setBeanClass(beanClass);
        def.setType(beanClass.getName());
        def.setClassLoader(beanClass.getClassLoader());
        def.setScope(detectScope(beanClass));
        String simpleName = beanClass.getSimpleName();
        if (simpleName.isEmpty()) {
            def.setName(beanClass.getName());
        } else {
            def.setName(Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1));
        }
        Spi spi = beanClass.getDeclaredAnnotation(Spi.class);
        if (spi != null) {
            def.setPriority(spi.order());
        }
        return def;
    }

    /**
    * 从 类 创建 类型Beandefinition，指定 Bean 名称。
    *
    * @param beanClass Bean 类
    * @param beanName  Bean 名称
    * @return TypeBeanDefinition 实例，Bean类 为 空 时返回 空
     */
    public static TypeBeanDefinition of(Class<?> beanClass, String beanName) {
        if (beanClass == null) {
            return null;
        }
        TypeBeanDefinition def = new TypeBeanDefinition();
        def.setBeanClass(beanClass);
        def.setType(beanClass.getName());
        def.setScope(detectScope(beanClass));
        def.setName(Objects.requireNonNullElseGet(beanName, beanClass::getSimpleName));
        def.setClassLoader(beanClass.getClassLoader());
        return def;
    }

    /**
    * 从 类 创建 类型Beandefinition，并附加注册器。
    *
    * @param beanClass Bean 类
    * @param beanName  Bean 名称
    * @param register  注册器
    * @return TypeBeanDefinition 实例，Bean类 为 空 时返回 空
     */
    public static TypeBeanDefinition of(Class<?> beanClass, String beanName, BeanDefinitionRegister register) {
        TypeBeanDefinition def = of(beanClass, beanName);
        if (def != null && register != null) {
            def.setRegister(register);
        }
        return def;
    }

    /**
    * 通过 SPI {@link BeanScopeDetector} 链检测 Bean 作用域，默认单例。
    *
    * @param beanClass Bean 类
    * @return 检测到的作用域
     */
    private static BeanScope detectScope(Class<?> beanClass) {
        for (BeanScopeDetector detector : ServiceProvider.of(BeanScopeDetector.class).collect()) {
            try {
                BeanScope scope = detector.detect(beanClass);
                if (scope != null) {
                    return scope;
                }
            } catch (Exception e) {
                log.debug("BeanScopeDetector 检测失败: {} - {}", detector.getClass().getName(), e.getMessage());
            }
        }
        return BeanScope.SINGLETON;
    }

    // ==================== 类加载器 ====================

    @Override
    /** 获取类加载 */
    public ClassLoader getClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        Class<?> cl = getBeanClass();
        if (cl != null) {
            return cl.getClassLoader();
        }
        return Thread.currentThread().getContextClassLoader();
    }

    // ==================== 实例创建 ====================

    @Override
    /** 创建Instance */
    public Object createInstance() {
        Class<?> bc = getBeanClass();
        if (bc == null) {
            return null;
        }
        if (BeanScope.SINGLETON == getScope() && singletonInstance != null) {
            return singletonInstance;
        }
        try {
            Constructor<?> constructor = selectMaxParamConstructor(bc);
            if (constructor == null) {
                log.warn("类没有声明构造器: {}", bc.getName());
                return null;
            }
            Object instance = ClassUtils.newInstance(constructor, resolveConstructorArgs(constructor));

            injectAndAssemble(instance);
            BeanDefinitionLifecycleManager.init(this, instance);
            instance = wrapWithProxy(instance, bc);

            return instance;
        } catch (Exception e) {
            log.error("创建 Bean 实例失败: {}", bc.getName(), e);
            return null;
        }
    }

    /**
    * 选择参数最多的构造器。
    * @param beanClass Bean类
    * @return 选择最大参数constructor的结果
     */
    private static Constructor<?> selectMaxParamConstructor(Class<?> beanClass) {
        Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
        if (constructors.length == 0) {
            return null;
        }
        Constructor<?> target = constructors[0];
        int max = target.getParameterCount();
        for (int i = 1; i < constructors.length; i++) {
            int count = constructors[i].getParameterCount();
            if (count > max) {
                max = count;
                target = constructors[i];
            }
        }
        return target;
    }

    /**
    * SPI 解析器缓存（延迟加载）。
     */
    private volatile List<BeanConstructorResolver> constructorResolvers;

    /**
    * 解析构造器参数，通过 SPI 解析器链按类型/名称查找 Bean。
    *
    * <p>解析顺序：
    * <ol>
    *   <li>SPI 加载的 {@link BeanConstructorResolver}（按 order 降序，框架特异性解析器优先）</li>
    *   <li>兜底的 DefaultBeanConstructorResolver（order=-1000，使用 typeProvider/nameProvider）</li>
    * </ol></p>
    *
    * @param constructor 构造器
    * @return 参数值数组
    * @throws BeanDefinitionException 参数标记了 @Spi 但无法解析
     */
    private Object[] resolveConstructorArgs(Constructor<?> constructor) {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (paramTypes.length == 0) {
            return new Object[0];
        }
        List<BeanConstructorResolver> resolvers = getConstructorResolvers();
        var params = constructor.getParameters();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = null;
            for (BeanConstructorResolver resolver : resolvers) {
                try {
                    arg = resolver.resolve(paramTypes[i], params[i].getName(),
                            params[i].getAnnotations(),
                            beanTypeProvider, beanNameProvider, this);
                    if (arg != null) {
                        break;
                    }
                } catch (Exception e) {
                    if (e instanceof BeanDefinitionException) {
                        throw (BeanDefinitionException) e;
                    }
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    log.debug("构造器参数解析器执行失败: {} - {}", resolver.getClass().getName(), e.getMessage());
                }
            }
            if (arg == null) {
                throw new BeanDefinitionException(
                        "构造器参数 [" + paramTypes[i].getName() + " " + params[i].getName() +
                                "] 在 " + constructor.getDeclaringClass().getName() + " 中无法解析");
            }
            args[i] = arg;
        }
        return args;
    }

    /**
    * 获取constructor解析器
    *
    * @return 获取constructor解析器的结果
     */
    private List<BeanConstructorResolver> getConstructorResolvers() {
        if (constructorResolvers == null) {
            synchronized (this) {
                if (constructorResolvers == null) {
                    constructorResolvers = ServiceProvider.of(BeanConstructorResolver.class).collect();
                }
            }
        }
        return constructorResolvers;
    }

    /**
    * 用动态代理包装实例，开启方法注解拦截能力。
    *
    * @param instance 原始实例
    * @param beanClass Bean 类
    * @return 代理实例
     */
    @SuppressWarnings("unchecked")
    private Object wrapWithProxy(Object instance, Class<?> beanClass) {
        try {
            return ProxyProvider.of((Class<Object>) beanClass)
                    .classLoader(getClassLoader())
                    .target(instance)
                    .methodIntercept(new BridgingMethodIntercept<>(instance, beanClass))
                    .enableAnnotationScan(true)
                    .enableArround(true)
                    .build();
        } catch (Exception e) {
            log.warn("创建代理失败，使用原始实例: {}", beanClass.getName(), e);
            return instance;
        }
    }

    // ==================== 单例缓存 ====================

    @Override
    /** 执行获取Bean */
    protected Object doGetBean() {
        return singletonInstance;
    }

    @Override
    /** 设置Bean */
    protected void setBean(Object bean) {
        if (BeanScope.SINGLETON == getScope()) {
            this.singletonInstance = bean;
        }
    }

    @Override
    /** 销毁Bean */
    public void destroyBean() {
        if (isDestroyed()) {
            return;
        }
        super.destroyBean();
        this.singletonInstance = null;
    }

    // ==================== Bean 获取 ====================

    @Override
    /** 获取Bean */
    public Object getBean() {
        if (BeanScope.SINGLETON == getScope()) {
            return super.getBean();
        }
        return createInstance();
    }

    @Override
    /** 初始化Bean */
    public Object initializeBean() {
        if (initialized.get()) {
            return doGetBean();
        }
        if (destroyed.get()) {
            return null;
        }
        if (initialized.compareAndSet(false, true)) {
            try {
                Object bean = createInstance();
                if (bean == null) {
                    initialized.set(false);
                    return null;
                }
                setBean(bean);
                return bean;
            } catch (Exception e) {
                initialized.set(false);
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
        }
        return doGetBean();
    }

}