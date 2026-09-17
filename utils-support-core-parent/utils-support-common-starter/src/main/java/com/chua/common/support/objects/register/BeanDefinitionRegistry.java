package com.chua.common.support.objects.register;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.generator.BeanDefinitionGenerator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bean 定义注册中心，统一管理所有 Beandefinition注册。
 *
 * @author CH
 * @since 2024/12/20
*/
@Slf4j
public class BeanDefinitionRegistry {

    /**
    * 存储所有已注册的 Beandefinition注册 实现类的列表，保持插入顺序且线程安全。
    */
    private final List<BeanDefinitionRegister> registers = new CopyOnWriteArrayList<>();

    /**
    * 存储以名称为键的 Beandefinition注册 映射，用于快速查找特定注册器。
    */
    private final Map<String, BeanDefinitionRegister> registerMap = new ConcurrentHashMap<>();

    /**
    * 缓存 Bean名称 到其所属 注册 的映射，减少重复遍历查找。
    */
    private final Map<String, BeanDefinitionRegister> beanNameCache = new ConcurrentHashMap<>();

    /**
    * 缓存类型名（类名）到包含该类型 Bean 的名称集合的映射，支持按类型快速查询。
    * <p>通过 {@link #typeCacheLock} 读写锁保护并发读写，避免全局阻塞。</p>
    */
    private final Map<String, Set<String>> typeToBeanNames = new ConcurrentHashMap<>();

    /**
    * 类型缓存读写锁，用于保护 {@link #typeToBeanNames} 的复合读写操作。
    */
    private final ReentrantReadWriteLock typeCacheLock = new ReentrantReadWriteLock();

    /**
    * 标记注册中心是否已完成初始化状态。
    */
    @Getter
    private volatile boolean initialized; // 初始化

    /**
    * Beandefinition 总数缓存，避免每次 {@link #getBeanDefinitionNames()} 遍历。
    * <p>在 register/unregister 中维护，{@link #getBeanDefinitionCount()} 直接读取无需遍历。</p>
    */
    private final AtomicInteger beanDefinitionCount = new AtomicInteger(0);

    /**
    * 初始化注册中心，默认启用 SPI 发现机制加载注册器。
    */
    public void initialize() {
        initialize(true);
    }

    /**
    * 初始化注册中心。
    *
    * @param spiEnabled 是否通过 SPI 发现并加载 Beandefinition注册 实现
    */
    public void initialize(boolean spiEnabled) {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            if (spiEnabled) {
                List<BeanDefinitionRegister> loaded = ServiceProvider.of(BeanDefinitionRegister.class)
                        .collect().stream()
                        .sorted(Comparator.comparingInt(BeanDefinitionRegister::getPriority).reversed())
                        .toList();
                for (BeanDefinitionRegister register : loaded) {
                    try {
                        register.initialize();
                        registers.add(register);
                        registerMap.put(register.getName(), register);
                    } catch (Exception e) {
                        log.error("初始化注册器失败: {}", register.getName(), e);
                    }
                }
            }
            initialized = true;
        }
    }

    /**
    * 编程式添加一个 Beandefinition注册（绕过 SPI）。
    *
    * <p>用于外部容器（如 Spring）将受管之外的注册器（例如 OSGi、远程节点）
    * 直接挂接到本注册中心。该方法线程安全，重复添加同名注册器将被忽略。</p>
    *
    * @param register 待注册的 Beandefinition注册
    * @return true 表示新增成功；false 表示入参为空或同名注册器已存在
    */
    public boolean addRegister(BeanDefinitionRegister register) {
        if (register == null) {
            return false;
        }
        String name = register.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        synchronized (this) {
            if (registerMap.containsKey(name)) {
                return false;
            }
            try {
                register.initialize();
            } catch (Exception e) {
                log.error("初始化注册器失败: {}", name, e);
                return false;
            }
            registers.add(register);
            registerMap.put(name, register);
            return true;
        }
    }

    /**
    * 获取指定 Bean名称 对应的 Beandefinition。
    * 优先从缓存中获取，若未命中则遍历所有注册器查找。
    *
    * @param beanName Bean 的唯一标识名称
    * @return 找到的 Beandefinition，否则返回 空
    */
    public BeanDefinition getBeanDefinition(String beanName) {
        if (beanName == null) {
            return null;
        }
        BeanDefinitionRegister register = beanNameCache.get(beanName);
        if (register != null) {
            BeanDefinition def = register.getBeanDefinition(beanName);
            if (def != null) {
                return def;
            }
            beanNameCache.remove(beanName);
        }
        for (BeanDefinitionRegister reg : registers) {
            BeanDefinition def = reg.getBeanDefinition(beanName);
            if (def != null) {
                beanNameCache.put(beanName, reg);
                return def;
            }
        }
        return null;
    }

    /**
    * 注册一个新的 Beandefinition。
    * 如果 Bean 已存在或无法找到合适的注册器，则注册失败。
    *
    * @param beanDefinition 待注册的 Bean 定义对象
    * @return 注册是否成功
    */
    public boolean register(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return false;
        }
        String beanName = beanDefinition.getName();
        if (beanName == null || beanName.isEmpty()) {
            return false;
        }
        if (containsBean(beanName)) {
            return false;
        }
        beanDefinition.setAvailable(true);
        BeanDefinitionRegister target = support(beanDefinition);
        if (target == null) {
            return false;
        }
        if (target.register(beanDefinition)) {
            beanDefinition.setRegister(target);
            beanNameCache.put(beanDefinition.getName(), target);
            Class<?> beanClass = beanDefinition.getBeanClass();
            if (beanClass != null) {
                updateTypeCache(beanClass, beanDefinition.getName());
            }
            beanDefinitionCount.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
    * 注销指定的 Beandefinition。
    * 从所有关联的注册器中移除该 Bean，并清理相关缓存。
    *
    * @param beanDefinition 待注销的 Bean 定义对象
    * @return 注销是否成功
    */
    public boolean unregister(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return false;
        }
        beanDefinition.setAvailable(false);
        String beanName = beanDefinition.getName();
        if (beanName == null) {
            return false;
        }
        boolean result = false;
        BeanDefinitionRegister reg = beanDefinition.getRegister();
        if (reg != null && reg.isWritable() && reg.containsBean(beanName)) {
            if (reg.unregister(beanDefinition)) {
                beanDefinition.setRegister(null);
                result = true;
            }
        }
        if (result) {
            beanNameCache.remove(beanName);
            Class<?> beanClass = beanDefinition.getBeanClass();
            if (beanClass != null) {
                removeFromTypeCache(beanClass, beanName);
            }
            beanDefinitionCount.decrementAndGet();
        }
        return result;
    }

    /**
    * 判断注册中心是否包含指定名称的 Bean。
    *
    * @param beanName Bean 的唯一标识名称
    * @return 是否包含该 Bean
    */
    public boolean containsBean(String beanName) {
        if (beanName == null) {
            return false;
        }
        BeanDefinitionRegister register = beanNameCache.get(beanName);
        if (register != null) {
            return register.containsBean(beanName);
        }
        for (BeanDefinitionRegister reg : registers) {
            if (reg.containsBean(beanName)) {
                beanNameCache.put(beanName, reg);
                return true;
            }
        }
        return false;
    }

    /**
    * 判断注册中心中是否已持有指定实例（用于防止重复注册同一对象）。
    * <p>遍历所有 registers，懒加载每个 BeanDefinition 来比对实例引用。</p>
    *
    * @param instance Bean 实例
    * @return true 表示已存在
    */
    public boolean containsInstance(Object instance) {
        if (instance == null) {
            return false;
        }
        for (String name : getBeanDefinitionNames()) {
            BeanDefinition def = getBeanDefinition(name);
            if (def == null) {
                continue;
            }
            try {
                Object bean = def.getBean();
                if (bean == instance) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
    * 获取所有已注册 Bean 的名称集合。
    *
    * @return 所有 Bean 名称的集合
    */
    public Collection<String> getBeanDefinitionNames() {
        Set<String> names = new LinkedHashSet<>();
        for (BeanDefinitionRegister reg : registers) {
            names.addAll(reg.getBeanDefinitionNames());
        }
        return names;
    }

    /**
    * 获取所有已注册的 Beandefinition 对象集合。
    *
    * @return 所有 Beandefinition 的集合
    */
    public Collection<BeanDefinition> getAllBeanDefinitions() {
        Set<BeanDefinition> definitions = new LinkedHashSet<>();
        for (BeanDefinitionRegister reg : registers) {
            for (String beanName : reg.getBeanDefinitionNames()) {
                BeanDefinition def = reg.getBeanDefinition(beanName);
                if (def != null) {
                    definitions.add(def);
                }
            }
        }
        return definitions;
    }

    /**
    * 根据类型名称获取所有匹配的 Beandefinition。
    *
    * @param typeName 类型的完整类名
    * @return 匹配类型的 Beandefinition 集合
    */
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null) {
            return Collections.emptyList();
        }
        typeCacheLock.readLock().lock();
        try {
            Set<BeanDefinition> definitions = new LinkedHashSet<>();
            for (BeanDefinitionRegister reg : registers) {
                definitions.addAll(reg.getBeanDefinitionOfType(typeName));
            }
            return definitions;
        } finally {
            typeCacheLock.readLock().unlock();
        }
    }

    /**
    * 根据 Java 类型获取所有匹配的 Beandefinition。
    * 优先使用类型缓存，若缓存无数据则回退到按类型名查询。
    *
    * @param type 目标类型
    * @return 匹配类型的 Beandefinition 集合
    */
    public Collection<BeanDefinition> getBeanDefinitionOfType(Class<?> type) {
        if (type == null) {
            return Collections.emptyList();
        }
        String typeName = type.getName();
        Set<String> beanNames = typeToBeanNames.get(typeName);
        if (beanNames != null && !beanNames.isEmpty()) {
            typeCacheLock.readLock().lock();
            try {
                List<BeanDefinition> result = new ArrayList<>();
                for (String name : beanNames) {
                    if (name != null) {
                        BeanDefinition def = getBeanDefinition(name);
                        if (def != null) {
                            result.add(def);
                        }
                    }
                }
                return result;
            } finally {
                typeCacheLock.readLock().unlock();
            }
        }
        return getBeanDefinitionOfType(typeName);
    }

    /**
    * 获取所有标注了指定注解的 Bean。
    *
    * @param annotationType 注解类型
    * @return 注解名称到 Beandefinition 的映射
    */
    public Map<String, BeanDefinition> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null) {
            return Collections.emptyMap();
        }
        return getBeansWithAnnotation(annotationType.getName());
    }

    /**
    * 按名称与类型从所有注册器中查找 Bean。
    * <p>遍历所有 register 找到指定名称的 BeanDefinition，并校验其类型是否可赋值给目标类型，
    * 兼容 Spring {@code getBean(name, requiredType)} 的语义。</p>
    *
    * @param name Bean 名称
    * @param type 目标类型
    * @param <T>  泛型类型
    * @return 找到的 Bean 实例，未找到则返回 空
    */
    public <T> T getBean(String name, Class<T> type) {
        if (name == null || type == null) {
            return null;
        }
        BeanDefinition definition = getBeanDefinition(name);
        if (definition == null) {
            return null;
        }
        try {
            Object bean = definition.getBean();
            if (bean == null) {
                return null;
            }
            if (type.isInstance(bean)) {
                return type.cast(bean);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
    * 按类型查找首个可用的 Bean。
    *
    * @param type 目标类型
    * @param <T>  泛型类型
    * @return 首个匹配的 Bean 实例，未找到则返回 空
    */
    public <T> T getBeanOfType(Class<T> type) {
        if (type == null) {
            return null;
        }
        for (BeanDefinition def : getBeanDefinitionOfType(type)) {
            if (def == null) {
                continue;
            }
            try {
                Object bean = def.getBean();
                if (bean != null && type.isInstance(bean)) {
                    return type.cast(bean);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
    * 按类型查找所有 Bean，返回 名称 -> Bean 映射。
    *
    * @param type 目标类型
    * @param <T>  泛型类型
    * @return name 到 Bean 的映射
    */
    public <T> Map<String, T> getBeansOfType(Class<T> type) {
        if (type == null) {
            return Collections.emptyMap();
        }
        Map<String, T> result = new LinkedHashMap<>();
        for (BeanDefinition def : getBeanDefinitionOfType(type)) {
            if (def == null) {
                continue;
            }
            try {
                Object bean = def.getBean();
                if (bean != null && type.isInstance(bean)) {
                    result.put(def.getName(), type.cast(bean));
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
    * 判断指定名称的 Bean 是否为单例作用域。
    * <p>本注册中心默认视所有 Bean 为单例，返回 {@link #containsBean(String)} 的结果。</p>
    *
    * @param name Bean 名称
    * @return 是否存在且为单例
    */
    public boolean isSingleton(String name) {
        return containsBean(name);
    }

    /**
    * 判断注册中心中是否包含指定类型的 Bean。
    *
    * @param type 目标类型
    * @param <T>  泛型类型
    * @return 是否存在至少一个匹配的 Bean
    */
    public <T> boolean hasBeanOfType(Class<T> type) {
        if (type == null) {
            return false;
        }
        for (BeanDefinition def : getBeanDefinitionOfType(type)) {
            if (def != null) {
                return true;
            }
        }
        return false;
    }

    /**
    * 获取所有匹配类型的 Bean 名称集合。
    *
    * @param type 目标类型
    * @return Bean 名称集合
    */
    public Collection<String> getBeanNames(Class<?> type) {
        if (type == null) {
            return Collections.emptyList();
        }
        Set<String> names = new LinkedHashSet<>();
        for (BeanDefinition def : getBeanDefinitionOfType(type)) {
            if (def != null && def.getName() != null) {
                names.add(def.getName());
            }
        }
        return names;
    }

    /**
    * 获取所有方法上标注了指定注解的 Bean 映射（聚合所有 注册）。
    *
    * @param annotationType 注解类型
    * @return Bean 名称到 Beandefinition 的映射
    */
    public Map<String, BeanDefinition> getBeansWithMethodAnnotation(Class<? extends Annotation> annotationType) {
        Map<String, BeanDefinition> result = new LinkedHashMap<>();
        for (BeanDefinitionRegister reg : registers) {
            try {
                Map<String, BeanDefinition> sub = reg.getBeansWithMethodAnnotation(annotationType);
                if (sub != null) {
                    result.putAll(sub);
                }
            } catch (Exception e) {
                log.trace("按方法注解获取 Bean 失败: {}", reg.getName(), e);
            }
        }
        return result;
    }

    /**
    * 获取所有标注了指定注解的 Bean（通过注解类名）。
    *
    * @param annotationTypeName 注解的完整类名
    * @return 注解名称到 Beandefinition 的映射
    */
    public Map<String, BeanDefinition> getBeansWithAnnotation(String annotationTypeName) {
        if (annotationTypeName == null || annotationTypeName.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, BeanDefinition> result = new LinkedHashMap<>();
        for (BeanDefinition def : getAllBeanDefinitions()) {
            if (def == null) {
                continue;
            }
            if (!def.isAnnotationPresent(annotationTypeName)) {
                continue;
            }
            String name = def.getName();
            if (name != null) {
                result.put(name, def);
            }
        }
        return result;
    }

    /**
    * 根据 类 对象自动注册 Bean。
    * 利用 SPI 发现的生成器创建 Beandefinition，并尝试注册到支持的注册器中。
    *
    * @param beanClass 待注册的类
    * @return true 表示至少有一个 Beandefinition 注册成功
    */
    public boolean registerBeanFromClass(Class<?> beanClass) {
        if (beanClass == null) {
            return false;
        }
        List<BeanDefinitionGenerator> generators = ServiceProvider.of(BeanDefinitionGenerator.class)
                .collect().stream()
                .sorted(Comparator.comparingInt(BeanDefinitionGenerator::getPriority).reversed())
                .toList();
        List<BeanDefinition> definitions = new ArrayList<>();
        for (BeanDefinitionGenerator generator : generators) {
            Boolean isSupport = generator.isSupport(beanClass);
            if (Boolean.TRUE.equals(isSupport)) {
                List<BeanDefinition> generated = generator.generate(beanClass);
                if (generated != null && !generated.isEmpty()) {
                    definitions.addAll(generated);
                    break;
                }
            }
        }

        if (definitions.isEmpty()) {
            return false;
        }

        boolean anyRegistered = false;
        for (BeanDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            definition.setAvailable(true);
            BeanDefinitionRegister target = support(definition);
            if (target == null) {
                continue;
            }
            if (target.register(definition)) {
                definition.setRegister(target);
                beanNameCache.put(definition.getName(), target);
                updateTypeCache(beanClass, definition.getName());
                beanDefinitionCount.incrementAndGet();
                anyRegistered = true;
            }
        }
        return anyRegistered;
    }

    /**
    * 关闭注册中心。
    * 销毁所有 Bean 并释放资源，清空内部缓存。
    */
    public void close() {
        for (BeanDefinitionRegister register : registers) {
            try {
                for (String beanName : register.getBeanDefinitionNames()) {
                    if (beanName != null) {
                        BeanDefinition def = register.getBeanDefinition(beanName);
                        if (def != null && !def.isDestroyed()) {
                            def.destroyBean();
                        }
                    }
                }
                register.close();
            } catch (Exception e) {
                log.error("关闭注册器失败: {}", register.getName(), e);
            }
        }
        registers.clear();
        registerMap.clear();
        beanNameCache.clear();
        typeToBeanNames.clear();
        beanDefinitionCount.set(0);
    }

    /**
    * 清空注册中心（不销毁 Beandefinition）。
    * <p>遍历所有 register 注销已注册的 BeanDefinition，但保留 register 列表本身（SPI 已加载的 register 保留）。
    * 与 {@link #close()} 的区别：本方法不调用 {@link BeanDefinitionRegister#close()}。</p>
    */
    public void clear() {
        for (BeanDefinitionRegister register : registers) {
            try {
                for (String name : new ArrayList<>(register.getBeanDefinitionNames())) {
                    BeanDefinition def = register.getBeanDefinition(name);
                    if (def != null) {
                        register.unregister(def);
                    }
                }
            } catch (Exception e) {
                log.debug("清空注册器失败: {}", register.getName(), e);
            }
        }
        beanNameCache.clear();
        typeToBeanNames.clear();
        beanDefinitionCount.set(0);
    }

    /**
    * 获取已注册的 Beandefinition 总数（O(1) 缓存读取）。
    * <p>由 register / unregister 维护，与 {@link #getBeanDefinitionNames()} 语义一致但性能更高。</p>
    *
    * @return BeanDefinition 总数
    */
    public int getBeanDefinitionCount() {
        return beanDefinitionCount.get();
    }

    /**
    * 根据 Beandefinition 寻找支持的注册器。
    * <p>按 register 的优先级（数值越小优先级越高）排序后依次查找首个支持目标 BeanDefinition 的可写注册器，
    * 避免依赖列表顺序导致的结果不稳定。</p>
    *
    * @param beanDefinition 待注册的 Bean 定义
    * @return 支持的注册器，若不支持则返回 空
    */
    private BeanDefinitionRegister support(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return null;
        }
        BeanDefinitionRegister[] snapshot = registers.toArray(new BeanDefinitionRegister[0]);
        // 按优先级排序：数值越小越靠前
        Arrays.sort(snapshot, Comparator.comparingInt(BeanDefinitionRegister::getPriority));
        for (BeanDefinitionRegister register : snapshot) {
            if (register.isSupport(beanDefinition) && register.isWritable()) {
                return register;
            }
        }
        return registerMap.get("default");
    }

    /**
    * 更新类型缓存，将 Bean名称 添加到对应类型及其父类、接口的缓存中。
    * <p>通过写锁保护复合写操作，避免并发更新丢失中间状态。</p>
    *
    * @param beanClass Bean 的类对象
    * @param beanName  Bean 的名称
    */
    private void updateTypeCache(Class<?> beanClass, String beanName) {
        if (beanClass == null || beanName == null) {
            return;
        }
        typeCacheLock.writeLock().lock();
        try {
            typeToBeanNames.computeIfAbsent(beanClass.getName(), k -> ConcurrentHashMap.newKeySet()).add(beanName);
            Class<?> superClass = beanClass.getSuperclass();
            while (superClass != null && superClass != Object.class) {
                typeToBeanNames.computeIfAbsent(superClass.getName(), k -> ConcurrentHashMap.newKeySet()).add(beanName);
                superClass = superClass.getSuperclass();
            }
            for (Class<?> iface : beanClass.getInterfaces()) {
                if (iface != null) {
                    typeToBeanNames.computeIfAbsent(iface.getName(), k -> ConcurrentHashMap.newKeySet()).add(beanName);
                }
            }
        } finally {
            typeCacheLock.writeLock().unlock();
        }
    }

    /**
    * 从类型缓存中移除指定的 Bean名称。
    * <p>仅遍历 beanClass 自身及其父类、接口的缓存集合，避免全量遍历。</p>
    *
    * @param beanClass Bean 的类对象
    * @param beanName  Bean 的名称
    */
    private void removeFromTypeCache(Class<?> beanClass, String beanName) {
        if (beanClass == null || beanName == null) {
            return;
        }
        typeCacheLock.writeLock().lock();
        try {
            typeToBeanNames.computeIfPresent(beanClass.getName(), (k, names) -> {
                names.remove(beanName);
                return names;
            });
            Class<?> superClass = beanClass.getSuperclass();
            while (superClass != null && superClass != Object.class) {
                typeToBeanNames.computeIfPresent(superClass.getName(), (k, names) -> {
                    names.remove(beanName);
                    return names;
                });
                superClass = superClass.getSuperclass();
            }
            for (Class<?> iface : beanClass.getInterfaces()) {
                if (iface != null) {
                    typeToBeanNames.computeIfPresent(iface.getName(), (k, names) -> {
                        names.remove(beanName);
                        return names;
                    });
                }
            }
        } finally {
            typeCacheLock.writeLock().unlock();
        }
    }
}
