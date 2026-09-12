package com.chua.common.support.spi.definition;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.function.NameAware;
import com.chua.common.support.spi.autowire.ServiceAutowire;
import com.chua.common.support.spi.condition.SpiCondition;
import com.chua.common.support.utils.ArrayUtils;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
* SPI 服务定义对象，封装服务实现类、元数据、加载状态及实例化信息。
* <p>
* 该类用于描述一个扩展点服务的注册信息，包含实现类、服务类型、优先级、描述信息、
* 以及实例创建和条件过滤等相关信息，便于 SPI 解析器统一管理服务实现。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class ServiceDefinition implements Comparable<ServiceDefinition> {


    /**
    * 默认排序比较器，优先级越高的服务定义排在前面。
     */
    public static final Comparator<ServiceDefinition> COMPARATOR = (o1, o2) -> Integer.compare(o2.getOrder(), o1.getOrder());

    /**
    * 服务实现类。
     */
    protected Class<?> implClass;

    /**
    * 服务实现类的父子类型集合，用于快速判断类型兼容性。
     */
    private Set<Class<?>> subType;

    /**
    * 子类型集合是否已初始化。
     */
    private volatile boolean subTypeInitialized = false;

    /**
    * 服务描述信息。
     */
    private String describe;

    /**
    * 服务描述类型。
     */
    private String describeType;

    /**
    * 服务描述详情。
     */
    private String describeDetail;

    /**
    * 支持的目标类型集合。
     */
    private String[] supportedTypes;

    /**
    * 可选参数描述列表。
     */
    private List<DescribeOptional> describeOptional;

    /**
    * 服务名称。
     */
    private String name;

    /**
    * 服务优先级，值越大优先级越高。
     */
    private int order = 0;

    /**
    * 服务定义来源资源地址。
     */
    private URL url;

    /**
    * 服务定义加载时间戳。
     */
    private long loadTime;

    /**
    * 加载服务定义时使用的类加载器。
     */
    private ClassLoader classLoader;

    /**
    * 服务扩展点类型。
     */
    private Class<?> type;

    /**
    * 是否为默认实现。
     */
    private boolean isDefault;

    /**
    * 已创建的服务对象实例。
     */
    private volatile Object obj;

    /**
    * 服务对象是否已经加载完成。
     */
    private volatile boolean isLoaded;

    /**
    * 服务加载或实例化过程中产生的异常。
     */
    private Throwable ex;

    /**
    * 发现该服务定义的类型或入口类型。
     */
    private Class<?> finderType;

    /**
    * 触发异常时保存的调用栈信息。
     */
    private StackTraceElement[] stack;

    /**
    * 创建一个已经初始化完成且绑定了实例对象的服务定义。
    *
    * @param name 服务名称
    * @param proxy 已创建的服务实例对象
    * @param type 服务扩展点类型
    * @param classLoader 使用的类加载器
    * @return 创建好的服务定义对象
     */
    public static ServiceDefinition createSingle(String name, Object proxy, Class<?> type, ClassLoader classLoader) {
        ServiceDefinition serviceDefinition = new ServiceDefinition();
        serviceDefinition.setName(name);
        serviceDefinition.setClassLoader(classLoader);
        serviceDefinition.setObj(proxy);
        serviceDefinition.setType(type);
        serviceDefinition.setFinderType(type);
        serviceDefinition.setLoaded(true);
        serviceDefinition.setLoadTime(System.currentTimeMillis());
        return serviceDefinition;
    }

    /**
    * 设置服务实现类，并重置其子类型缓存。
    *
    * @param implClass 服务实现类
     */
    public void setImplClass(Class<?> implClass) {
        this.implClass = implClass;
        this.subTypeInitialized = false;
        this.subType = null;
    }

    /**
    * 获取服务实现类。
    *
    * @return 服务实现类
     */
    public Class<?> getImplClass() {
        return implClass;
    }

    /**
    * 获取Describe
    *
    * @return 获取describe的结果
     */
    public String getDescribe() {
        return describe;
    }

    /**
    * 设置Describe
    *
    * @param describe describe
     */
    public void setDescribe(String describe) {
        this.describe = describe;
    }

    /**
    * 获取describe类型
    *
    * @return 获取describe类型的结果
     */
    public String getDescribeType() {
        return describeType;
    }

    /**
    * 设置describe类型
    *
    * @param describeType describe类型
     */
    public void setDescribeType(String describeType) {
        this.describeType = describeType;
    }

    /**
    * 获取describedetail
    *
    * @return 获取describedetail的结果
     */
    public String getDescribeDetail() {
        return describeDetail;
    }

    /**
    * 设置describedetail
    *
    * @param describeDetail describedetail
     */
    public void setDescribeDetail(String describeDetail) {
        this.describeDetail = describeDetail;
    }

    /**
    * 获取支持类型
    *
    * @return 获取支持类型的结果
     */
    public String[] getSupportedTypes() {
        return supportedTypes;
    }

    /**
    * 设置支持类型
    *
    * @param supportedTypes 支持类型
     */
    public void setSupportedTypes(String[] supportedTypes) {
        this.supportedTypes = supportedTypes;
    }

    /**
    * 获取describe期权
    *
    * @return 获取describe期权的结果
     */
    public List<DescribeOptional> getDescribeOptional() {
        return describeOptional;
    }

    /**
    * 设置describe期权
    *
    * @param describeOptional describe期权
     */
    public void setDescribeOptional(List<DescribeOptional> describeOptional) {
        this.describeOptional = describeOptional;
    }

    /**
    * 获取名称
    *
    * @return 获取名称的结果
     */
    public String getName() {
        return name;
    }

    /**
    * 设置名称
    *
    * @param name 名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
    * 获取订单
    *
    * @return 获取订单的结果
     */
    public int getOrder() {
        return order;
    }

    /**
    * 设置订单
    *
    * @param order 订单
     */
    public void setOrder(int order) {
        this.order = order;
    }

    /**
    * 获取Url
    *
    * @return 获取url的结果
     */
    public URL getUrl() {
        return url;
    }

    /**
    * 设置Url
    *
    * @param url url
     */
    public void setUrl(URL url) {
        this.url = url;
    }

    /**
    * 获取加载时间
    *
    * @return 获取加载时间的结果
     */
    public long getLoadTime() {
        return loadTime;
    }

    /**
    * 设置加载时间
    *
    * @param loadTime 加载时间
     */
    public void setLoadTime(long loadTime) {
        this.loadTime = loadTime;
    }

    /**
    * 获取类加载
    *
    * @return 获取类加载的结果
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
    * 设置类加载。
    * @param classLoader 类加载
     */
    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public Class<?> getType() {
        return type;
    }

    /**
    * 设置类型
    *
    * @param type 类型
     */
    public void setType(Class<?> type) {
        this.type = type;
    }

    /**
    * 是否默认
    *
    * @return 是否默认的结果
     */
    public boolean isDefault() {
        return isDefault;
    }

    /**
    * 设置默认
    *
    * @param isDefault 是否默认
     */
    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    /**
    * 获取Obj
    *
    * @return 获取obj的结果
     */
    public Object getObj() {
        return obj;
    }

    /**
    * 设置Obj
    *
    * @param obj obj
     */
    public void setObj(Object obj) {
        this.obj = obj;
    }

    /**
    * 是否加载
    *
    * @return 是否加载的结果
     */
    public boolean isLoaded() {
        return isLoaded;
    }

    /**
    * 设置加载
    *
    * @param loaded 加载
     */
    public void setLoaded(boolean loaded) {
        isLoaded = loaded;
    }

    /**
    * 获取Ex
    *
    * @return 获取ex的结果
     */
    public Throwable getEx() {
        return ex;
    }

    /**
    * 设置Ex
    *
    * @param ex ex
     */
    public void setEx(Throwable ex) {
        this.ex = ex;
    }

    public Class<?> getFinderType() {
        return finderType;
    }

    /**
    * 设置查找类型
    *
    * @param finderType 查找类型
     */
    public void setFinderType(Class<?> finderType) {
        this.finderType = finderType;
    }

    /**
    * 获取Stack
    *
    * @return 获取stack的结果
     */
    public StackTraceElement[] getStack() {
        return stack;
    }

    /**
    * 设置Stack
    *
    * @param stack stack
     */
    public void setStack(StackTraceElement[] stack) {
        this.stack = stack;
    }

    /**
    * 获取实现类的所有父子类型集合，首次访问时进行懒加载。
    *
    * @return 实现类相关的类型集合
     */
    private Set<Class<?>> getSubType() {
        if (!subTypeInitialized) {
            synchronized (this) {
                if (!subTypeInitialized) {
                    if (null == implClass) {
                        this.subType = Collections.emptySet();
                    } else {
                        this.subType = new HashSet<>(ClassUtils.getAllType(implClass));
                        this.subType.add(implClass);
                    }
                    this.subTypeInitialized = true;
                }
            }
        }
        return subType;
    }

    /**
    * 是否启用构造器缓存，默认开启。
     */
    private static final boolean ENABLE_CONSTRUCTOR_CACHE =
        Boolean.parseBoolean(System.getProperty("spi.constructor.cache.enabled", "true"));

    /**
    * 根据给定参数创建服务实例，并执行依赖注入与 SPI 条件检查。
    *
    * @param serviceAutowire 服务注入器
    * @param args 构造参数列表
    * @param <T> 实例类型
    * @return 创建后的服务实例
     */
    @SuppressWarnings("ALL")
    public <T> T newInstance(ServiceAutowire serviceAutowire, Object... args) {
        if(null == implClass) {
            return null;
        }

        try {
            Constructor<?> declaredConstructor = ClassUtils.getConstructor(implClass, ClassUtils.toType(args));
            if(null != declaredConstructor) {
                ClassUtils.setAccessible(declaredConstructor);
                T instance = (T) serviceAutowire.autowire(declaredConstructor.newInstance(args));
                if (instance != null && !checkSpiCondition(instance)) {
                    return null;
                }
                return instance;
            }
        } catch (Exception e) {
            throw new RuntimeException(" : " + implClass.getName(), e);
        }
        Constructor<?>[] constructors = implClass.getConstructors();
        for (Constructor<?> constructor : constructors) {
            if(constructor.getParameterCount() != args.length) {
                if (constructor.getParameterCount() > args.length) {
                    continue;
                }
                try {
                    ClassUtils.setAccessible(constructor);
                    T instance = (T) serviceAutowire.autowire(newInstance(constructor, args));
                    if (instance != null && !checkSpiCondition(instance)) {
                        return null;
                    }
                    return instance;
                } catch (Exception ignored) {
                }
            }
            ClassUtils.setAccessible(constructor);
            try {
                if(ArrayUtils.isEquals(constructor.getParameterTypes(), args)) {
                    T instance = (T) serviceAutowire.autowire(constructor.newInstance(args));
                    if (instance != null && !checkSpiCondition(instance)) {
                        return null;
                    }
                    return instance;
                }
            } catch (Exception e) {
                T bean = (T) serviceAutowire.createBean(implClass);
                if(null == bean) {
                    log.error("", e);
                    try {
                        throw e;
                    } catch (InstantiationException exc) {
                        throw new RuntimeException(exc);
                    } catch (IllegalAccessException exc) {
                        throw new RuntimeException(exc);
                    } catch (InvocationTargetException exc) {
                        throw new RuntimeException(exc);
                    }
                }
                if (bean != null && !checkSpiCondition(bean)) {
                    return null;
                }
                return bean;
            }
        }

        try {
            T instance = (T) ClassUtils.newInstance(implClass);
            if (instance != null && !checkSpiCondition(instance)) {
                return null;
            }
            return instance;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
    * 检查服务实例是否满足 {@link SpiCondition} 的条件约束。
    *
    * @param instance 服务实例
    * @return 如果未实现条件接口或条件为真，则返回 {@code true}
     */
    private boolean checkSpiCondition(Object instance) {
        if (!(instance instanceof SpiCondition)) {
            return true;
        }
        try {
            boolean condition = ((SpiCondition) instance).isCondition();
            if (!condition && log.isDebugEnabled()) {
                log.debug("SpiCondition : {} false", implClass != null ? implClass.getName() : "unknown");
            }
            return condition;
        } catch (Exception e) {
            log.warn(" SpiCondition : {}", e.getMessage(), e);
            return true;
        }
    }

    /**
    * 使用指定构造器创建实例，并根据参数类型进行必要的转换。
    *
    * @param constructor 目标构造器
    * @param args 构造参数
    * @return 创建好的实例对象
    * @throws InstantiationException 实例化失败时抛出
    * @throws IllegalAccessException 无权访问构造器时抛出
     */
    private Object newInstance(Constructor<?> constructor, Object[] args) throws InstantiationException, IllegalAccessException {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] args1 = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            Object value = getValue(parameterType, args);
            if (null == value) {
                throw new IllegalArgumentException();
            }
            args1[i] = value;
        }
        return ClassUtils.newInstance(constructor, args1);
    }

    /**
    * 获取值
    *
    * @param parameterType 参数类型
    * @param args 参数
    * @return 获取值的结果
     */
    private Object getValue(Class<?> parameterType, Object[] args) {
        for (Object arg : args) {
            Object necessary = Converter.convertIfNecessary(arg, parameterType);
            if (null != necessary) {
                return necessary;
            }
        }
        return null;
    }

    /**
    * 懒加载服务实例对象，首次访问时创建并注入依赖。
    *
    * @param serviceAutowire 服务注入器
    * @param <T> 实例类型
    * @return 服务实例对象
     */
    public <T> T getObj(ServiceAutowire serviceAutowire) {
        if (!isLoaded && null == obj) {
            synchronized (this) {
                if (!isLoaded && null == obj) {
                    isLoaded = true;
                    try {
                        this.obj = null == implClass ? null : ClassUtils.newInstance(implClass);
                        if(null != serviceAutowire) {
                            serviceAutowire.autowire(obj);
                        }
                    } catch (Exception e) {
                        stack = Thread.currentThread().getStackTrace();
                        if(log.isDebugEnabled()) {
                            log.error(" :{} : : {}", implClass.getTypeName(), e.getLocalizedMessage());
                        }
                        ex = e;
                    }
                }
            }
        }
        return (T) obj;
    }

    /**
    * 判断当前实现类是否实现了 {@link NameAware} 接口。
    *
    * @return 如果实现类实现了 {@link NameAware}，则返回 {@code true}
     */
    public boolean isPresent() {
        return null != implClass &&
                (NameAware.class.isAssignableFrom(implClass));
    }
    /**
    * 判断当前实现类是否可赋值给指定父类型。
    *
    * @param parentType 目标父类型
    * @return 如果实现类可赋值给父类型，则返回 {@code true}
     */
    public boolean isAssignableFrom(Class<?> parentType) {
        return null != parentType && parentType.isAssignableFrom(implClass);
    }
    @Override
    /** 比较转为 */
    public int compareTo(ServiceDefinition o) {
        return Integer.compare(o.order, this.order);
    }

}
