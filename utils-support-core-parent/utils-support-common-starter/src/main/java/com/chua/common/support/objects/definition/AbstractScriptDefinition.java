package com.chua.common.support.objects.definition;

import com.chua.common.support.lang.script.marker.ScriptMarker;
import com.chua.common.support.lang.script.marker.listener.Listener;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 脚本 Bean 定义抽象基类。
 *
 * <p>提供 {@link ScriptDefinition} 的通用实现，管理脚本标记器、源码监听器和类加载器生命周期。
 * 子类只需关注具体脚本语言的编译和实例化细节。</p>
 *
 * <p>热重载流程：
 * <ol>
 *   <li>{@link #createInstance()} 调用 {@link Listener#isChange()} 检测源码变化</li>
 *   <li>若变化，调用 {@link #destroyScriptClassLoader()} 销毁旧 ClassLoader</li>
 *   <li>调用 {@link ScriptMarker#createObject(Listener, ClassLoader, Object[])} 重新编译</li>
 *   <li>保存新的 ClassLoader 供下次热重载使用</li>
 * </ol></p>
 *
 * <p>线程安全说明：{@link #createInstance()} 使用 {@code synchronized} 保护热重载流程，
 * 避免多线程并发检测变更和编译导致的 ClassLoader 泄漏。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see ScriptDefinition
 * @see AbstractBeanDefinition
 */
@Slf4j
public abstract class AbstractScriptDefinition extends AbstractBeanDefinition implements ScriptDefinition {

    /**
     * 脚本类加载器引用，热重载时替换
     */
    private final AtomicReference<ClassLoader> scriptClassLoader = new AtomicReference<>();

    /**
     * 热重载锁，保护 createInstance() 中的变更检测 → 销毁 → 编译流程
     */
    private final Object hotReloadLock = new Object();

    /**
     * 脚本标记器，负责脚本编译和对象创建
     */
    private ScriptMarker scriptMarker;

    /**
     * 脚本源码监听器，负责检测源码变更
     */
    private Listener listener;

    /**
     * 缓存的脚本实例，避免每次调用 getBean() 都重新编译
     */
    private Object scriptInstance;

    /**
     * 构造空的脚本定义。
     */
    protected AbstractScriptDefinition() {
    }

    /**
     * 构造脚本定义。
     *
     * @param name      Bean 名称
     * @param beanClass Bean 类
     * @param scope     Bean 作用域
     */
    protected AbstractScriptDefinition(String name, Class<?> beanClass, BeanScope scope) {
        super(name, beanClass, scope);
    }

    @Override
    /** 获取ScriptClassLoader */
    public ClassLoader getScriptClassLoader() {
        return scriptClassLoader.get();
    }

    @Override
    /** 设置ScriptClassLoader */
    public void setScriptClassLoader(ClassLoader classLoader) {
        this.scriptClassLoader.set(classLoader);
    }

    @Override
    /** 获取ScriptMarker */
    public ScriptMarker getScriptMarker() {
        return scriptMarker;
    }

    /**
     * 设置脚本标记器。
     *
     * @param scriptMarker 脚本标记器实例
     */
    public void setScriptMarker(ScriptMarker scriptMarker) {
        this.scriptMarker = scriptMarker;
    }

    @Override
    /** 获取Listener */
    public Listener getListener() {
        return listener;
    }

    /**
     * 设置脚本源码监听器。
     *
     * @param listener 脚本源码监听器实例
     */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    /**
     * 创建脚本对象实例（线程安全）。
     *
     * <p>使用 {@code synchronized} 保护热重载流程，避免多线程并发检测变更和编译
     * 导致 ClassLoader 泄漏或重复创建。</p>
     *
     * <p>热重载时，先断开旧实例引用再编译新实例，确保旧实例及其关联的类可被 GC 回收。</p>
     */
    public Object createInstance() {
        if (listener == null || scriptMarker == null) {
            return null;
        }

        synchronized (hotReloadLock) {
            // 检测脚本源码是否变化，变化则销毁旧 ClassLoader 并断开旧实例引用
            if (listener.isChange()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ScriptDefinition] 检测到脚本源码变更，执行热重载: name={}", getName());
                }
                destroyScriptClassLoader();
                this.scriptInstance = null;
            }

            // 获取当前 ClassLoader，首次使用时取默认类加载器
            ClassLoader current = scriptClassLoader.get();
            if (current == null) {
                current = ClassUtils.getDefaultClassLoader();
            }

            // 调用脚本标记器编译并创建对象
            Object instance = scriptMarker.createObject(listener, current, new Object[0]);
            if (instance != null) {
                // 缓存实例，供 getBean() 复用
                this.scriptInstance = instance;
                // 更新 Bean 类型，若标记器未返回类型则使用实例类
                Class<?> type = scriptMarker.getType();
                if (type == null) {
                    type = instance.getClass();
                }
                setBeanClass(type);
                // 保存脚本标记器产生的 ClassLoader
                ClassLoader newClassLoader = scriptMarker.getScriptClassLoader();
                if (newClassLoader != null) {
                    setScriptClassLoader(newClassLoader);
                }
            }
            return instance;
        }
    }

    @Override
    /** 是否AssignableFrom */
    public boolean isAssignableFrom(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        // 优先走 JDK 类型判断（同 ClassLoader 场景）
        if (ClassUtils.isAssignable(beanClass, clazz)) {
            return true;
        }
        // 跨 ClassLoader 场景：字符串全限定名匹配（含父类/接口递归）
        return isAssignableFromTypeHierarchy(beanClass, clazz.getName());
    }

    @Override
    /** 是否AssignableFrom */
    public boolean isAssignableFrom(String clazz) {
        if (clazz == null || clazz.isEmpty()) {
            return false;
        }
        Class<?> beanClass = getBeanClass();
        if (beanClass == null) {
            return false;
        }
        // 直接走字符串全限定名匹配（含父类/接口递归）
        return isAssignableFromTypeHierarchy(beanClass, clazz);
    }

    /**
     * 递归检查类型层次（类、父类、接口）是否与目标全限定名匹配。
     * <p>用于跨 ClassLoader 场景，避免同名类被 JVM 视为不同类的问题。</p>
     *
     * @param source    源类
     * @param targetName 目标类全限定名
     * @return true 表示类型层次中存在匹配
     */
    private boolean isAssignableFromTypeHierarchy(Class<?> source, String targetName) {
        if (source == null || source == Object.class) {
            return false;
        }
        if (source.getName().equals(targetName)) {
            return true;
        }
        for (Class<?> iface : source.getInterfaces()) {
            if (isAssignableFromTypeHierarchy(iface, targetName)) {
                return true;
            }
        }
        return isAssignableFromTypeHierarchy(source.getSuperclass(), targetName);
    }

    @Override
    /** Do获取Bean */
    protected Object doGetBean() {
        return scriptInstance;
    }

    @Override
    /** 设置Bean */
    protected void setBean(Object bean) {
        this.scriptInstance = bean;
    }

    /**
     * 销毁脚本类加载器。
     *
     * <p>热重载时调用，释放旧 ClassLoader 占用的 Metaspace 内存。
     * 如果 ClassLoader 实现了 {@link AutoCloseable}，优先调用 close()；
     * 否则回退到 {@link ClassUtils#unregisterClassLoader(ClassLoader)}。</p>
     *
     * <p>异常安全保证：即使 close() 失败，也会兜底调用
     * {@link ClassUtils#unregisterClassLoader(ClassLoader)} 尝试从全局注册表移除，
     * 避免 ClassLoader 成为孤儿对象。</p>
     */
    public void destroyScriptClassLoader() {
        ClassLoader classLoader = this.scriptClassLoader.getAndSet(null);
        if (classLoader == null) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ScriptDefinition] 销毁脚本类加载器: name={}, type={}, hash={}",
                    getName(), classLoader.getClass().getName(), System.identityHashCode(classLoader));
        }
        // 新增：销毁前清理 Groovy 内部缓存（sourceCache、ClassInfo 反射缓存）
        clearGroovyCache(classLoader);
        try {
            if (classLoader instanceof AutoCloseable autoCloseable) {
                autoCloseable.close();
            } else {
                ClassUtils.unregisterClassLoader(classLoader);
            }
        } catch (Exception e) {
            log.error("[ScriptDefinition] 销毁脚本类加载器失败: {}", classLoader, e);
            // 兜底：即使 close() 失败，也尝试从全局注册表移除，
            // 避免 ClassLoader 成为孤儿对象（既不在 scriptClassLoader 中，也不在注册表中）
            try {
                ClassUtils.unregisterClassLoader(classLoader);
            } catch (Exception ignored) {
                // 移除失败时静默忽略，避免二次异常
            }
        }
    }

    /**
     * 清理 GroovyClassLoader 的内部缓存。
     *
     * <p>通过反射调用 {@code GroovyClassLoader.clearCache()} 方法，
     * 在 close() 之前释放 sourceCache 和 ClassInfo 反射缓存，
     * 帮助 Metaspace 内存回收。仅当 ClassLoader 是 GroovyClassLoader 实例时执行。</p>
     */
    private void clearGroovyCache(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        // 通过类名判断是否为 GroovyClassLoader，避免直接依赖 Groovy 类
        if ("groovy.lang.GroovyClassLoader".equals(classLoader.getClass().getName())) {
            try {
                ReflectUtils.invoke(classLoader, "clearCache", void.class);
            } catch (Exception e) {
                // clearCache 调用失败时静默忽略，不影响后续 close() 流程
            }
        }
    }

    /**
     * 销毁 Bean。
     *
     * <p>除执行父类生命周期销毁外，额外释放脚本 ClassLoader，
     * 避免脚本引擎产生的 ClassLoader 内存泄漏被忽略。</p>
     */
    @Override
    public void destroyBean() {
        try {
            super.destroyBean();
        } finally {
            destroyScriptClassLoader();
            this.scriptMarker = null;
            this.listener = null;
        }
    }
}
