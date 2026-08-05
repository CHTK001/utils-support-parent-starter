package com.chua.common.support.objects.definition;

import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.utils.ClassUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.objects.inject.BeanDefinitionConfigInjector;
import com.chua.common.support.objects.environment.ConfigValueResolvers;
import com.chua.common.support.objects.inject.BeanDefinitionMethodInjector;
import com.chua.common.support.objects.inject.BeanDefinitionServiceInjector;
import com.chua.common.support.objects.lifecycle.BeanDefinitionLifecycleManager;
import com.chua.common.support.objects.environment.Environment;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Bean 定义抽象基类。
 *
 * <p>提供 Bean 元数据管理的通用实现，包括：
 * <ul>
 *   <li>类型层次缓存（快速判断 Bean 是否可赋值给指定类型）</li>
 *   <li>注解信息缓存（快速判断 Bean 是否标注了指定注解）</li>
 *   <li>依赖注入流程（服务注入 + 配置注入）</li>
 *   <li>Bean 生命周期管理（初始化 + 销毁）</li>
 * </ul></p>
 *
 * <p>子类需要实现 {@link #setBean(Object)} 方法来保存创建的实例。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@SuppressWarnings("unchecked")
public abstract class AbstractBeanDefinition implements BeanDefinition {

    /**
     * 初始化状态标志
     */
    @Getter
    protected final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * 销毁状态标志
     */
    @Getter
    protected final AtomicBoolean destroyed = new AtomicBoolean(false);

    /**
     * 是否启用代理
     */
    @Getter
    @Setter
    private boolean proxy = true;

    /**
     * 优先级，值越大优先级越高
     */
    @Getter
    @Setter
    private int priority;

    /**
     * Bean 作用域
     */
    @Getter
    @Setter
    private BeanScope scope = BeanScope.SINGLETON;

    /**
     * Bean 名称
     */
    @Getter
    @Setter
    /**
     * 名称
     */
    private String name;

    /**
     * Bean 类型全限定名
     */
    @Getter
    @Setter
    /**
     * 类型
     */
    private String type;

    /**
     * Bean 类
     */
    @Getter
    @Setter
    private Class<?> beanClass;

    /**
     * 是否可用
     */
    @Getter
    @Setter
    private boolean available = true;

    /**
     * 关联的注册器
     */
    private volatile BeanDefinitionRegister register;

    /**
     * 缓存的 Bean 类，用于判断缓存是否有效
     */
    private volatile Class<?> cachedBeanClass;

    /**
     * 类型层次缓存（类名 -> 是否存在），用于快速判断 isAssignableFrom
     */
    private volatile Set<String> typeHierarchyNames = Collections.emptySet();

    /**
     * 注解类型名缓存（注解类名 -> 是否存在），用于快速判断 isAnnotationPresent
     */
    private volatile Set<String> annotationTypeNames = Collections.emptySet();

    /**
     * 当前环境配置，注入时需要
     */
    @Setter
    @Getter
    private Environment environment;

    /**
     * 按名称查找 Bean 的函数，用于服务注入
     */
    @Setter
    protected Function<String, Object> beanNameProvider;

    /**
     * 按类型查找 Bean 的函数，用于服务注入
     */
    @Setter
    protected Function<Class<?>, Object> beanTypeProvider;

    /**
     * 占位符解析最大迭代次数，防止无限循环
     */
    private static final int MAX_PLACEHOLDER_ITERATIONS = 100;

    /**
     * 构造空的抽象 Bean 定义。
     */
    public AbstractBeanDefinition() {
    }

    /**
     * 构造抽象 Bean 定义。
     *
     * @param name      Bean 名称
     * @param beanClass Bean 类
     * @param scope     Bean 作用域
     */
    public AbstractBeanDefinition(String name, Class<?> beanClass, BeanScope scope) {
        this.name = name;
        this.beanClass = beanClass;
        this.scope = scope;
        this.type = beanClass != null ? beanClass.getName() : null;
    }

    /**
     * 获取 Bean 实例，未初始化时自动触发懒加载。
     *
     * <p>调用链：getBean() → 未初始化则 initializeBean() → createInstance() → setBean() → 注入 → 生命周期</p>
     *
     * @return Bean 实例，初始化失败返回 null
     */
    @Override
    public Object getBean() {
        if (!initialized.get()) {
            return initializeBean();
        }
        return doGetBean();
    }

    /**
     * 子类实现返回已缓存的 Bean 实例。
     *
     * @return Bean 实例，默认返回 null
     */
    protected Object doGetBean() {
        return null;
    }

    /**
     * 保存 Bean 实例。
     *
     * @param bean Bean 实例
     */
    protected void setBean(Object bean) {
    }

    /**
     * 获取类加载器。
     *
     * @return 类加载器
     */
    @Override
    public ClassLoader getClassLoader() {
        Class<?> cl = getBeanClass();
        if (cl != null) {
            return cl.getClassLoader();
        }
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * 创建 Bean 实例。
     *
     * @return Bean 实例，默认返回 null，子类按需重写
     */
    @Override
    public Object createInstance() {
        return null;
    }

    @Override
    public BeanDefinitionRegister getRegister() {
        return register;
    }

    @Override
    public void setRegister(BeanDefinitionRegister register) {
        this.register = register;
    }

    @Override
    public boolean isInitialized() {
        return initialized.get();
    }

    @Override
    public boolean isDestroyed() {
        return destroyed.get();
    }

    // ==================== 注解检测 ====================

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        // 直接检查类上的注解
        if (beanClass.isAnnotationPresent(annotationType)) {
            return true;
        }
        // 从缓存中检查
        ensureTypeCaches(beanClass);
        return annotationTypeNames.contains(annotationType.getName());
    }

    @Override
    public boolean isAnnotationPresent(String annotationTypeName) {
        if (annotationTypeName == null || annotationTypeName.isEmpty()) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        ensureTypeCaches(beanClass);
        return annotationTypeNames.contains(annotationTypeName);
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationType) {
        if (annotationType == null) {
            return null;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return null;
        }
        // 直接从类上获取
        T annotation = beanClass.getAnnotation(annotationType);
        if (annotation != null) {
            return annotation;
        }
        // 从缓存中查找
        ensureTypeCaches(beanClass);
        if (!annotationTypeNames.contains(annotationType.getName())) {
            return null;
        }
        return getAnnotationFromHierarchy(beanClass, annotationType);
    }

    @Override
    public Annotation getAnnotation(String annotationTypeName) {
        if (annotationTypeName == null || annotationTypeName.isEmpty()) {
            return null;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return null;
        }
        ensureTypeCaches(beanClass);
        if (!annotationTypeNames.contains(annotationTypeName)) {
            return null;
        }
        return getAnnotationFromHierarchy(beanClass, annotationTypeName);
    }

    // ==================== 类型判断 ====================

    @Override
    public boolean isAssignableFrom(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        // 直接 JDK 判断
        if (ClassUtils.isAssignable(beanClass, clazz)) {
            return true;
        }
        ensureTypeCaches(beanClass);
        return typeHierarchyNames.contains(clazz.getName());
    }

    @Override
    public boolean isAssignableFrom(String clazz) {
        if (clazz == null || clazz.isEmpty()) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        ensureTypeCaches(beanClass);
        return typeHierarchyNames.contains(clazz);
    }

    @Override
    public boolean isAssignableTo(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        // 直接 JDK 判断
        if (ClassUtils.isAssignable(clazz, beanClass)) {
            return true;
        }
        ensureTypeCaches(beanClass);
        return typeHierarchyNames.contains(clazz.getName());
    }

    @Override
    public boolean isAssignableTo(String clazz) {
        if (clazz == null || clazz.isEmpty()) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        ensureTypeCaches(beanClass);
        return typeHierarchyNames.contains(clazz);
    }

    @Override
    public boolean isType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        // 直接 JDK 判断
        if (beanClass == clazz || clazz.isAssignableFrom(beanClass)) {
            return true;
        }
        ensureTypeCaches(beanClass);
        return typeHierarchyNames.contains(clazz.getName());
    }

    @Override
    public boolean isType(String clazz) {
        if (clazz == null || clazz.isEmpty()) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        ensureTypeCaches(beanClass);
        return typeHierarchyNames.contains(clazz);
    }

    // ==================== 方法查询 ====================

    @Override
    public List<Method> getMethodsWithAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            return Collections.emptyList();
        }
        return getMethodsWithAnnotation(annotationType.getName());
    }

    @Override
    public List<Method> getMethodsWithAnnotation(String annotationTypeName) {
        if (annotationTypeName == null || annotationTypeName.isEmpty()) {
            return Collections.emptyList();
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return Collections.emptyList();
        }
        return ClassUtils.getLocalMethods(beanClass).stream()
                .filter(method -> hasAnnotation(method, annotationTypeName))
                .toList();
    }

    @Override
    public List<MethodDefinition> getMethodDefinitions() {
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return Collections.emptyList();
        }
        return ClassUtils.getLocalMethods(beanClass).stream()
                .map(method -> new MethodDefinition(this, method))
                .toList();
    }

    @Override
    public MethodDefinition getMethodDefinition(Method method) {
        if (method == null || getBeanClass() == null) {
            return null;
        }
        return new MethodDefinition(this, method);
    }

    @Override
    public Method getMethodWithAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            return null;
        }
        return getMethodWithAnnotation(annotationType.getName());
    }

    @Override
    public Method getMethodWithAnnotation(String annotationTypeName) {
        if (annotationTypeName == null || annotationTypeName.isEmpty()) {
            return null;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return null;
        }
        return ClassUtils.getLocalMethods(beanClass).stream()
                .filter(method -> hasAnnotation(method, annotationTypeName))
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean hasMethodWithAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            return false;
        }
        return hasMethodWithAnnotation(annotationType.getName());
    }

    @Override
    public boolean hasMethodWithAnnotation(String annotationTypeName) {
        if (annotationTypeName == null || annotationTypeName.isEmpty()) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        return ClassUtils.getLocalMethods(beanClass).stream()
                .anyMatch(method -> hasAnnotation(method, annotationTypeName));
    }

    // ==================== Bean 生命周期 ====================

    @Override
    public Object initializeBean() {
        // 已初始化则直接返回
        if (initialized.get()) {
            return getBean();
        }
        // 已销毁则返回 null
        if (destroyed.get()) {
            return null;
        }
        if (initialized.compareAndSet(false, true)) {
            try {
                // 1. 创建原始实例
                Object bean = createInstance();
                if (bean == null) {
                    initialized.set(false);
                    return null;
                }
                // 2. 保存实例
                setBean(bean);
                // 3. 依赖注入（@AutoInject、@ConfigValue 等）
                injectAndAssemble(bean);
                // 4. 执行生命周期初始化
                BeanDefinitionLifecycleManager.init(this, bean);
                return bean;
            } catch (Exception e) {
                initialized.set(false);
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
        }
        return getBean();
    }

    @Override
    public void destroyBean() {
        if (destroyed.get()) {
            return;
        }
        if (!initialized.get()) {
            return;
        }
        if (destroyed.compareAndSet(false, true)) {
            Object bean = getBean();
            if (bean != null) {
                BeanDefinitionLifecycleManager.destroy(this, bean);
            }
        }
    }

    // ==================== 依赖注入 ====================

    /**
     * 执行 Bean 的依赖注入和装配。
     *
     * <p>包括字段注入（服务注入 + 配置注入）和方法注入。</p>
     *
     * @param instance Bean 实例
     */
    public void injectAndAssemble(Object instance) {
        if (instance == null) {
            return;
        }
        injectFields(instance);
        injectMethods(instance);
    }

    /**
     * 字段注入：遍历所有字段，尝试服务注入和配置注入。
     *
     * @param instance Bean 实例
     */
    protected void injectFields(Object instance) {
        if (instance == null) {
            return;
        }
        List<Field> fields = getAllFields(instance.getClass());
        var serviceInjectors = ServiceProvider.of(BeanDefinitionServiceInjector.class).collect();
        var configInjectors = ServiceProvider.of(BeanDefinitionConfigInjector.class).collect();

        for (Field field : fields) {
            // 先服务注入
            try {
                Object serviceValue = injectService(field, instance, serviceInjectors);
                if (serviceValue != null) {
                    setFieldOrSetter(field, instance, serviceValue);
                    continue;
                }
            } catch (Exception e) {
                log.error("注入服务字段失败: {}", field.getName(), e);
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
            // 后配置注入
            Object configValue = injectConfig(field, instance, configInjectors);
            if (configValue != null) {
                try {
                    setFieldOrSetter(field, instance, configValue);
                } catch (Exception e) {
                    log.warn("注入配置字段失败: {}", field.getName(), e);
                }
            }
        }
    }

    /**
     * 先按字段名找 setter 方法，有则调用 setter，无则直接设字段。
     *
     * @param field   目标字段
     * @param instance Bean 实例
     * @param value   注入值
     */
    private void setFieldOrSetter(Field field, Object instance, Object value) {
        String setterName = "set" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
        try {
            Method setter = instance.getClass().getMethod(setterName, field.getType());
            ClassUtils.setAccessible(setter);
            setter.invoke(instance, value);
            return;
        } catch (NoSuchMethodException ignored) {
            // 没有 setter，回退到直接设字段
        } catch (Exception e) {
            log.warn("setter 调用失败，回退到字段注入: {}.{}", instance.getClass().getSimpleName(), setterName, e);
        }
        ClassUtils.setFieldValue(field, instance.getClass(), value, instance);
    }

    /**
     * 方法注入：遍历所有 setter 方法，尝试注入。
     *
     * @param instance Bean 实例
     */
    protected void injectMethods(Object instance) {
        if (instance == null) {
            return;
        }
        List<Method> methods = ClassUtils.getLocalMethods(instance.getClass());
        var methodInjectors = ServiceProvider.of(BeanDefinitionMethodInjector.class).collect();
        var configInjectors = ServiceProvider.of(BeanDefinitionConfigInjector.class).collect();

        for (Method method : methods) {
            if (method.getParameterCount() == 0) {
                continue;
            }
            Function<String, Object> nameProvider = beanNameProvider != null ? beanNameProvider : n -> null;
            Function<Class<?>, Object> typeProvider = beanTypeProvider != null ? beanTypeProvider : t -> null;
            boolean injected = false;

            // 先尝试服务注入（按 SPI 链）
            for (BeanDefinitionMethodInjector injector : methodInjectors) {
                if (injector.isSupport(method, this)) {
                    injector.inject(method, instance, this, nameProvider, typeProvider);
                    injected = true;
                    break;
                }
            }
            if (injected) {
                continue;
            }

            // 后尝试配置注入（@ConfigValue 参数），统一解析 String 类型表达式
            if (environment != null) {
                for (BeanDefinitionConfigInjector injector : configInjectors) {
                    if (injector.isSupport(method, this)) {
                        Object[] args = injector.inject(method, instance, this, environment);
                        if (args != null) {
                            Parameter[] params = method.getParameters();
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof String str) {
                                    Object resolved = ConfigValueResolvers.resolve(str, params[i].getType(), environment);
                                    if (resolved != null) {
                                        args[i] = resolved;
                                    } else {
                                        Object fromEnv = environment.getProperty(str, params[i].getType());
                                        if (fromEnv != null) {
                                            args[i] = fromEnv;
                                        }
                                    }
                                }
                            }
                            try {
                                ClassUtils.setAccessible(method);
                                method.invoke(instance, args);
                            } catch (Exception e) {
                                log.warn("配置方法注入失败: {}.{}", instance.getClass().getSimpleName(), method.getName(), e);
                            }
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * 执行服务注入（通过 SPI 注入器链）。
     *
     * @param field            目标字段
     * @param instance         Bean 实例
     * @param serviceInjectors 服务注入器列表
     * @return 注入的值，null 表示不适配
     */
    protected Object injectService(Field field, Object instance, List<BeanDefinitionServiceInjector> serviceInjectors) {
        if (serviceInjectors == null || serviceInjectors.isEmpty()) {
            return null;
        }
        for (BeanDefinitionServiceInjector injector : serviceInjectors) {
            if (injector.isSupport(field, this)) {
                Function<String, Object> nameProvider = beanNameProvider != null ? beanNameProvider : n -> null;
                Function<Class<?>, Object> typeProvider = beanTypeProvider != null ? beanTypeProvider : t -> null;
                return injector.inject(field, instance, this, nameProvider, typeProvider);
            }
        }
        return null;
    }

    /**
     * 执行配置注入（通过 SPI 注入器链）。
     *
     * @param field           目标字段
     * @param instance        Bean 实例
     * @param configInjectors 配置注入器列表
     * @return 注入的值，null 表示不适配
     */
    protected Object injectConfig(Field field, Object instance, List<BeanDefinitionConfigInjector> configInjectors) {
        if (configInjectors == null || configInjectors.isEmpty()) {
            return null;
        }
        if (environment == null) {
            return null;
        }
        for (BeanDefinitionConfigInjector injector : configInjectors) {
            if (injector.isSupport(field, this)) {
                Object value = injector.inject(field, instance, this, environment);
                if (value instanceof String str) {
                    // 统一处理表达式（${} / #{}）和直接 key 查找
                    Object resolved = ConfigValueResolvers.resolve(str, field.getType(), environment);
                    if (resolved != null) {
                        return resolved;
                    }
                    Object fromEnv = environment.getProperty(str, field.getType());
                    if (fromEnv != null) {
                        return fromEnv;
                    }
                }
                return value;
            }
        }
        return null;
    }

    /**
     * 获取类及其所有父类的所有字段（包括私有字段）。
     *
     * @param clazz 目标类
     * @return 所有字段列表
     */
    protected List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    // ==================== 类型缓存 ====================

    /**
     * 确保类型层次缓存有效。
     *
     * <p>如果缓存的 beanClass 与当前 beanClass 不同，则重新构建缓存。</p>
     *
     * @param beanClass 当前 Bean 类
     */
    private void ensureTypeCaches(Class<?> beanClass) {
        if (beanClass == null) {
            cachedBeanClass = null;
            typeHierarchyNames = Collections.emptySet();
            annotationTypeNames = Collections.emptySet();
            return;
        }
        if (beanClass == cachedBeanClass) {
            return;
        }
        synchronized (this) {
            if (beanClass == cachedBeanClass) {
                return;
            }
            Set<String> hierarchy = new HashSet<>();
            Set<String> annotations = new HashSet<>();
            collectTypeHierarchy(beanClass, hierarchy);
            collectAnnotationTypes(beanClass, annotations);
            typeHierarchyNames = Collections.unmodifiableSet(hierarchy);
            annotationTypeNames = Collections.unmodifiableSet(annotations);
            cachedBeanClass = beanClass;
        }
    }

    /**
     * 收集类型层次结构中的所有类型名。
     *
     * @param type 当前类型
     * @param out  输出集合
     */
    private static void collectTypeHierarchy(Class<?> type, Set<String> out) {
        if (type == null || type == Object.class) {
            return;
        }
        out.add(type.getName());
        for (Class<?> iface : type.getInterfaces()) {
            collectTypeHierarchy(iface, out);
        }
        collectTypeHierarchy(type.getSuperclass(), out);
    }

    /**
     * 收集类型层次结构中所有注解类型名。
     *
     * @param type 当前类型
     * @param out  输出集合
     */
    private static void collectAnnotationTypes(Class<?> type, Set<String> out) {
        if (type == null || type == Object.class) {
            return;
        }
        for (Annotation a : type.getAnnotations()) {
            out.add(a.annotationType().getName());
        }
        for (Class<?> iface : type.getInterfaces()) {
            collectAnnotationTypes(iface, out);
        }
        collectAnnotationTypes(type.getSuperclass(), out);
    }

    /**
     * 从类型层次结构中获取指定注解。
     *
     * @param type           当前类型
     * @param annotationType 注解类型
     * @param <T>            注解泛型
     * @return 找到的注解，不存在返回 null
     */
    private static <T extends Annotation> T getAnnotationFromHierarchy(Class<?> type, Class<T> annotationType) {
        if (type == null || type == Object.class) {
            return null;
        }
        T annotation = type.getAnnotation(annotationType);
        if (annotation != null) {
            return annotation;
        }
        for (Class<?> iface : type.getInterfaces()) {
            T fromIface = getAnnotationFromHierarchy(iface, annotationType);
            if (fromIface != null) {
                return fromIface;
            }
        }
        return getAnnotationFromHierarchy(type.getSuperclass(), annotationType);
    }

    /**
     * 从类型层次结构中按类名获取注解。
     *
     * @param type               当前类型
     * @param annotationTypeName 注解类型名
     * @return 找到的注解，不存在返回 null
     */
    private static Annotation getAnnotationFromHierarchy(Class<?> type, String annotationTypeName) {
        if (type == null || type == Object.class) {
            return null;
        }
        for (Annotation a : type.getAnnotations()) {
            if (a.annotationType().getName().equals(annotationTypeName)) {
                return a;
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            Annotation fromIface = getAnnotationFromHierarchy(iface, annotationTypeName);
            if (fromIface != null) {
                return fromIface;
            }
        }
        return getAnnotationFromHierarchy(type.getSuperclass(), annotationTypeName);
    }

    /**
     * 判断方法是否标注了指定注解（按类名）。
     *
     * @param method             方法
     * @param annotationTypeName 注解类型名
     * @return 是否标注
     */
    private static boolean hasAnnotation(Method method, String annotationTypeName) {
        if (method == null) {
            return false;
        }
        for (Annotation a : method.getAnnotations()) {
            if (a.annotationType().getName().equals(annotationTypeName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return getType();
    }
}