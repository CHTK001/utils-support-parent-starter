package com.chua.common.support.utils;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 注解工具类，提供注解存在性判断（含继承链 + 别名穿透）、
 * 注解定义解析（SPI + 子类优先）等能力。
 *
 * <h3>别名穿透规则</h3>
 * <pre>
 *   {@code @GetMapping} 的元注解包含 {@code @RequestMapping}
 *   → isAnnotationPresent(clazz, GetMapping.class)          == true
 *   → isAnnotationPresent(clazz, RequestMapping.class)      == true   // 别名展开
 *   → isAnnotationPresent(clazz, PostMapping.class)         == false  // 不跨方法名
 * </pre>
 *
 * <h3>子类优先规则</h3>
 * <pre>
 *   class BaseController { @RequestMapping("/api") }
 *   class UserController extends BaseController { @GetMapping("/users") }
 *
 *   resolveAnnotationDefinition(UserController.class, RequestMapping.class)
 *     → source=DIRECT (UserController 直接声明，优先级高于父类)
 *   resolveAnnotationDefinition(BaseController.class, RequestMapping.class)
 *     → source=INHERITED (父类继承)
 * </pre>
 *
 * <h3>别名发现架构</h3>
 * <pre>
 *   common-starter:    AnnotationUtils        → 通过 ServiceProvider 消费 AnnotationDefinitionResolver
 *   spring-starter:    SpringMvcResolver      → 通过反射自动发现 Spring MVC 注解族别名
 *   other-framework:   XxxResolver (SPI)      → 自定义框架实现
 * </pre>
 *
 * @author CH
 * @since 4.0.0.43
*/
public class AnnotationUtils {

    /**
    * 注解工具。
    */
    private AnnotationUtils() {}

    /**
    * 别名缓存：窄注解 类（weak哈希映射 键）→ 宽注解全限定名。
    * 使用 weak哈希映射 保证窄注解类被 GC 回收时缓存自动清理，防止类加载器泄漏。
    */
    private static final Map<Class<? extends Annotation>, String> ALIAS_NARROW_TO_WIDE =
            new java.util.WeakHashMap<>();

    /**
    * 别名族标记集合（字符串全限定名），用于快速判断某注解是否属于某个别名族。
    */
    private static final Set<String> ALIAS_SOURCE_SET = new HashSet<>();

    private static volatile boolean aliasesLoaded = false;

    /**
    * 懒加载别名映射：从所有 SPI 实现的 {@link AnnotationDefinitionResolver} 中收集别名。
    * common-starter 不包含任何具体框架的硬编码，别名发现完全由 SPI 承担。
    */
    private static void ensureAliasesLoaded() {
        if (aliasesLoaded) {
            return;
        }
        synchronized (AnnotationUtils.class) {
            if (aliasesLoaded) {
                return;
            }
            loadSpiAliases();
            aliasesLoaded = true;
        }
    }

    /**
    * 通过 SPI 加载所有 {@link AnnotationDefinitionResolver} 实现的别名映射，
    * 写入 {@link #ALIAS_NARROW_TO_WIDE} 和 {@link #ALIAS_SOURCE_SET}。
    */
    private static void loadSpiAliases() {
        try {
            ServiceProvider<AnnotationDefinitionResolver> provider =
                    ServiceProvider.of(AnnotationDefinitionResolver.class);
            for (AnnotationDefinitionResolver resolver : provider.collect()) {
                for (AnnotationDefinitionResolver.AnnotationAliasMapping mapping : resolver.getAliasMappings()) {
                    try {
                        Class<?> wideClass = ReflectUtils.forName(mapping.getWideName());
                        Class<?> narrowClass = ReflectUtils.forName(mapping.getNarrowName());
                        if (Annotation.class.isAssignableFrom(wideClass) &&
                                Annotation.class.isAssignableFrom(narrowClass)) {
                            ALIAS_NARROW_TO_WIDE.put((Class<? extends Annotation>) narrowClass,
                                    mapping.getWideName());
                            ALIAS_SOURCE_SET.add(mapping.getNarrowName());
                            ALIAS_SOURCE_SET.add(mapping.getWideName());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---- 公共 API ----

    /**
    * 判断目标元素上是否存在指定注解（含继承链 + 别名穿透）。
    *
    * <p>若目标元素有 {@code @GetMapping}，则查询 {@code RequestMapping} 也返回 {@code true}。</p>
    *
    * @param element         注解所在的目标元素
    * @param annotationClass 待检查的注解类型
    * @return 如果存在则返回 {@code true}
    * @since 4.0.0.43
    */
    public static boolean isAnnotationPresent(AnnotatedElement element, Class<? extends Annotation> annotationClass) {
        if (element == null || annotationClass == null) {
            return false;
        }
        // 1. 直接匹配
        if (element.isAnnotationPresent(annotationClass)) {
            return true;
        }
        // 2. 继承链匹配
        if (element instanceof Class) {
            if (hasInheritedAnnotation((Class<?>) element, annotationClass)) {
                return true;
            }
        } else if (element instanceof Method) {
            if (hasOverriddenAnnotation((Method) element, annotationClass)) {
                return true;
            }
        }
        // 3. 别名穿透
        ensureAliasesLoaded();
        if (isAlias(annotationClass)) {
            return hasAliasMatch(element, annotationClass);
        }
        return false;
    }

    /**
    * 是否注解present。
    * @param clazz clazz
    * @param annotationClass 注解类
    * @return 是否注解present的结果
    */
    public static boolean isAnnotationPresent(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        if (clazz == null || annotationClass == null) {
            return false;
        }
        return isAnnotationPresent((AnnotatedElement) clazz, annotationClass);
    }

    /**
    * 是否注解present。
    * @param method 方法
    * @param annotationClass 注解类
    * @return 是否注解present的结果
    */
    public static boolean isAnnotationPresent(Method method, Class<? extends Annotation> annotationClass) {
        if (method == null || annotationClass == null) {
            return false;
        }
        return isAnnotationPresent((AnnotatedElement) method, annotationClass);
    }

    /**
    * 获取目标元素上的注解（继承链 + 别名穿透查找），未找到时返回 {@code null}。
    *
    * <p>别名命中时返回窄注解实例本身。</p>
    *
    * @param element         注解所在的目标元素
    * @param annotationClass 注解类型
    * @param <A>             注解泛型
    * @return 找到的注解实例，未找到返回 {@code null}
    * @since 4.0.0.43
    */
    public static <A extends Annotation> A getAnnotation(AnnotatedElement element, Class<A> annotationClass) {
        if (element == null || annotationClass == null) {
            return null;
        }
        A direct = element.getAnnotation(annotationClass);
        if (direct != null) {
            return direct;
        }
        if (element instanceof Class) {
            A inherited = findInheritedAnnotation((Class<?>) element, annotationClass);
            if (inherited != null) {
                return inherited;
            }
        } else if (element instanceof Method) {
            A overridden = findOverriddenAnnotation((Method) element, annotationClass);
            if (overridden != null) {
                return overridden;
            }
        }
        ensureAliasesLoaded();
        if (isAlias(annotationClass)) {
            return findAliasAnnotation(element, annotationClass);
        }
        return null;
    }

    public static <A extends Annotation> A getAnnotation(Class<?> clazz, Class<A> annotationClass) {
        return getAnnotation((AnnotatedElement) clazz, annotationClass);
    }

    public static <A extends Annotation> A getAnnotation(Method method, Class<A> annotationClass) {
        return getAnnotation((AnnotatedElement) method, annotationClass);
    }

    /**
    * 解析注解定义：从当前元素向上遍历继承链，
    * 按「子类优先 → 直接声明 > 继承 > 重写方法」顺序返回第一个匹配的定义。
    *
    * @param element         目标元素（类或方法）
    * @param annotationClass 待解析的注解类型
    * @param <A>             注解泛型
    * @return 注解定义，未找到返回 {@code null}
    * @since 4.0.0.43
    */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation> AnnotationDefinition<A> resolveAnnotationDefinition(
            AnnotatedElement element, Class<A> annotationClass) {
        if (element == null || annotationClass == null) {
            return null;
        }
        ensureAliasesLoaded();
        Set<Class<? extends Annotation>> searchTypes = buildSearchSet(annotationClass);

        // 1. 当前元素直接声明（最高优先级，子类优先）
        for (Class<? extends Annotation> annClass : searchTypes) {
            try {
                A ann = (A) element.getAnnotation(annClass);
                if (ann != null) {
                    boolean isSubclass = element instanceof Class &&
                            ((Class<?>) element).getSuperclass() != null &&
                            ((Class<?>) element).getSuperclass() != Object.class;
                    if (isSubclass) {
                        return AnnotationDefinition.subclassOverrides(annotationClass, ann);
                    }
                    return AnnotationDefinition.ofDirect(ann, annotationClass);
                }
            } catch (Exception ignored) {
            }
        }

        // 2. 继承链（父类 / 父接口）
        if (element instanceof Class) {
            AnnotationDefinition<A> inherited = resolveFromHierarchy(
                    (Class<?>) element, annotationClass, searchTypes);
            if (inherited != null) {
                return inherited;
            }
        }

        // 3. 方法重写链
        if (element instanceof Method) {
            AnnotationDefinition<A> overridden = resolveFromMethodOverride(
                    (Method) element, annotationClass, searchTypes);
            if (overridden != null) {
                return overridden;
            }
        }

        return null;
    }

    public static <A extends Annotation> AnnotationDefinition<A> resolveAnnotationDefinition(
            Class<?> clazz, Class<A> annotationClass) {
        return resolveAnnotationDefinition((AnnotatedElement) clazz, annotationClass);
    }

    public static <A extends Annotation> AnnotationDefinition<A> resolveAnnotationDefinition(
            Method method, Class<A> annotationClass) {
        return resolveAnnotationDefinition((AnnotatedElement) method, annotationClass);
    }

    /**
    * 判断类是否包含任意已知映射注解（通过 SPI 注册的别名族）。
    *
    * @param clazz 目标类
    * @return 如果存在任意映射注解则返回 {@code true}
    * @since 4.0.0.43
    */
    public static boolean isMappingAnnotation(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        ensureAliasesLoaded();
        for (String alias : ALIAS_SOURCE_SET) {
            try {
                Class<?> annClass = ReflectUtils.forName(alias);
                if (isAnnotationPresent(clazz, (Class<? extends Annotation>) annClass)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
    * 判断方法是否包含任意已知映射注解（通过 SPI 注册的别名族）。
    *
    * @param method 目标方法
    * @return 如果存在任意映射注解则返回 {@code true}
    * @since 4.0.0.43
    */
    public static boolean isMappingAnnotation(Method method) {
        if (method == null) {
            return false;
        }
        ensureAliasesLoaded();
        for (String alias : ALIAS_SOURCE_SET) {
            try {
                Class<?> annClass = ReflectUtils.forName(alias);
                if (isAnnotationPresent(method, (Class<? extends Annotation>) annClass)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
    * 将窄注解 类 解析为对应的宽注解 类（通过 SPI）。
    *
    * @param annotationClass 窄注解类型
    * @return 对应的宽注解类型，无别名时返回原值
    * @since 4.0.0.43
    */
    public static Class<? extends Annotation> resolveRequestMappingAlias(Class<? extends Annotation> annotationClass) {
        if (annotationClass == null) {
            return annotationClass;
        }
        String name = annotationClass.getName();
        String resolved = ALIAS_NARROW_TO_WIDE.get(name);
        if (resolved == null || resolved.equals(name)) {
            return annotationClass;
        }
        try {
            return (Class<? extends Annotation>) ReflectUtils.forName(resolved);
        } catch (Exception e) {
            return annotationClass;
        }
    }

    /**
    * 将窄注解全限定名解析为对应的宽注解全限定名（通过 SPI）。
    *
    * @param annotationClassName 窄注解全限定名
    * @return 对应的宽注解全限定名，无别名时返回原值
    * @since 4.0.0.43
    */
    public static String resolveRequestMappingAlias(String annotationClassName) {
        if (annotationClassName == null || annotationClassName.isEmpty()) {
            return annotationClassName;
        }
        ensureAliasesLoaded();
        return ALIAS_NARROW_TO_WIDE.getOrDefault(annotationClassName, annotationClassName);
    }

 // ---- 私募 助手 ----

    /**
    * 是否别名。
    * @param annotationClass 注解类
    * @return 是否别名的结果
    */
    private static boolean isAlias(Class<? extends Annotation> annotationClass) {
        return ALIAS_SOURCE_SET.contains(annotationClass.getName());
    }

    /**
    * 构建双向搜索集合：目标注解 + 所有同族别名。
    * <pre>
    * 正向：GetMapping → RequestMapping
    * 反向：RequestMapping → {GetMapping, PostMapping, PutMapping, DeleteMapping, ...}
    * </pre>
    * @param target Target
    * @return 构建搜索设置的结果
    */
    private static Set<Class<? extends Annotation>> buildSearchSet(Class<? extends Annotation> target) {
        Set<Class<? extends Annotation>> set = new LinkedHashSet<>();
        set.add(target);
        ensureAliasesLoaded();
        String targetName = target.getName();
 // 正向：Target 是窄注解，加入其宽注解
        String wide = ALIAS_NARROW_TO_WIDE.get(targetName);
        if (wide != null && !wide.equals(targetName)) {
            try {
                set.add((Class<? extends Annotation>) ReflectUtils.forName(wide));
            } catch (Exception ignored) {
            }
        }
 // 反向：Target 是宽注解，加入所有窄注解
        for (Map.Entry<Class<? extends Annotation>, String> entry : ALIAS_NARROW_TO_WIDE.entrySet()) {
            if (entry.getValue().equals(targetName) && !entry.getKey().equals(targetName)) {
                try {
                    set.add((Class<? extends Annotation>) ReflectUtils.forName(entry.getKey().getName()));
                } catch (Exception ignored) {
                }
            }
        }
        return set;
    }

    /**
    * 是否包含别名匹配。
    * @param element element
    * @param annotationClass 注解类
    * @return 是否包含别名匹配的结果
    */
    private static boolean hasAliasMatch(AnnotatedElement element, Class<? extends Annotation> annotationClass) {
        for (Class<? extends Annotation> alt : buildSearchSet(annotationClass)) {
            if (!alt.equals(annotationClass) && element.isAnnotationPresent(alt)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findAliasAnnotation(AnnotatedElement element,
                                                                Class<A> annotationClass) {
        for (Class<? extends Annotation> alt : buildSearchSet(annotationClass)) {
            if (!alt.equals(annotationClass)) {
                try {
                    Annotation ann = element.getAnnotation(alt);
                    if (ann != null) {
                        return (A) ann;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
    * 是否包含inherited注解。
    * @param clazz clazz
    * @param annotationClass 注解类
    * @return 是否包含inherited注解的结果
    */
    private static boolean hasInheritedAnnotation(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            if (isAnnotationPresent(superClass, annotationClass)) {
                return true;
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            if (isAnnotationPresent(iface, annotationClass)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    /**
    * findinherited注解。
    * @param clazz clazz
    * @param annotationClass 注解类
    * @return findinherited注解的结果
    */
    private static <A extends Annotation> A findInheritedAnnotation(Class<?> clazz, Class<A> annotationClass) {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            A annotation = getAnnotation(superClass, annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            A annotation = getAnnotation(iface, annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    /**
    * 是否包含overridden注解。
    * @param method 方法
    * @param annotationClass 注解类
    * @return 是否包含overridden注解的结果
    */
    private static boolean hasOverriddenAnnotation(Method method, Class<? extends Annotation> annotationClass) {
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        Class<?> declaringClass = method.getDeclaringClass();
        for (Class<?> superClazz : getSuperClasses(declaringClass)) {
            try {
                for (Method superMethod : superClazz.getDeclaredMethods()) {
                    if (superMethod.getName().equals(name) &&
                            java.util.Arrays.equals(superMethod.getParameterTypes(), paramTypes)) {
                        if (superMethod.isAnnotationPresent(annotationClass)) {
                            return true;
                        }
                    }
                }
            } catch (NoClassDefFoundError ignored) {
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    /**
    * findoverridden注解。
    * @param method 方法
    * @param annotationClass 注解类
    * @return findoverridden注解的结果
    */
    private static <A extends Annotation> A findOverriddenAnnotation(Method method, Class<A> annotationClass) {
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        Class<?> declaringClass = method.getDeclaringClass();
        for (Class<?> superClazz : getSuperClasses(declaringClass)) {
            try {
                for (Method superMethod : superClazz.getDeclaredMethods()) {
                    if (superMethod.getName().equals(name) &&
                            java.util.Arrays.equals(superMethod.getParameterTypes(), paramTypes)) {
                        A annotation = (A) superMethod.getAnnotation(annotationClass);
                        if (annotation != null) {
                            return annotation;
                        }
                    }
                }
            } catch (NoClassDefFoundError ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> AnnotationDefinition<A> resolveFromHierarchy(
            Class<?> clazz, Class<A> targetAnnotation,
            Set<Class<? extends Annotation>> searchTypes) {
        // 先检查当前类直接声明（子类优先于父类）
        AnnotationDefinition<A> subclassDirect = findDirectInSubclassHierarchy(clazz, searchTypes);
        if (subclassDirect != null) {
            return subclassDirect;
        }
        // 父类继承
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            for (Class<? extends Annotation> annClass : searchTypes) {
                try {
                    A ann = (A) superClass.getAnnotation(annClass);
                    if (ann != null) {
                        return AnnotationDefinition.ofInherited(ann, targetAnnotation);
                    }
                } catch (Exception ignored) {
                }
            }
            AnnotationDefinition<A> parentResult = resolveFromHierarchy(
                    superClass, targetAnnotation, searchTypes);
            if (parentResult != null) {
                return parentResult;
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            for (Class<? extends Annotation> annClass : searchTypes) {
                try {
                    A ann = (A) iface.getAnnotation(annClass);
                    if (ann != null) {
                        return AnnotationDefinition.ofInherited(ann, targetAnnotation);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> AnnotationDefinition<A> findDirectInSubclassHierarchy(
            Class<?> clazz, Set<Class<? extends Annotation>> searchTypes) {
        for (Class<? extends Annotation> annClass : searchTypes) {
            try {
                A ann = (A) clazz.getAnnotation(annClass);
                if (ann != null) {
                    return AnnotationDefinition.ofDirect(ann, (Class<A>) annClass);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <A extends Annotation> AnnotationDefinition<A> resolveFromMethodOverride(
            Method method, Class<A> targetAnnotation,
            Set<Class<? extends Annotation>> searchTypes) {
        String name = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        Class<?> declaringClass = method.getDeclaringClass();
        for (Class<?> superClazz : getSuperClasses(declaringClass)) {
            try {
                for (Method superMethod : superClazz.getDeclaredMethods()) {
                    if (superMethod.getName().equals(name) &&
                            java.util.Arrays.equals(superMethod.getParameterTypes(), paramTypes)) {
                        for (Class<? extends Annotation> annClass : searchTypes) {
                            try {
                                Annotation ann = superMethod.getAnnotation(annClass);
                                if (ann != null) {
                                    return AnnotationDefinition.ofOverriddenMethod((A) ann, targetAnnotation);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            } catch (NoClassDefFoundError ignored) {
            }
        }
        return null;
    }

    /**
    * 获取父类。
    * @param clazz clazz
    * @return 获取父类的结果
    */
    private static java.util.List<Class<?>> getSuperClasses(Class<?> clazz) {
        java.util.List<Class<?>> list = new java.util.ArrayList<>();
        Class<?> current = clazz.getSuperclass();
        while (current != null && current != Object.class) {
            list.add(current);
            current = current.getSuperclass();
        }
        return list;
    }

    // ---- 原有方法保留 ----

    /**
    * 获取注解attributes。
    * @param clazz clazz
    * @param annotationClass 注解类
    * @return 获取注解attributes的结果
    */
    public static Map<String, Object> getAnnotationAttributes(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        Map<String, Object> attributes = new HashMap<>();
        Annotation annotation = clazz.getAnnotation(annotationClass);
        if (annotation == null) {
            return attributes;
        }
        for (Method method : annotationClass.getDeclaredMethods()) {
            try {
                Object value = ReflectUtils.invoke(annotation, method.getName(), Object.class);
                attributes.put(method.getName(), value);
            } catch (Exception e) {
                // ignore
            }
        }
        return attributes;
    }

    /**
    * 从注解实例读取属性值（遍历注解接口声明的方法，反射调用取值）。
    *
    * @param annotation 注解实例
    * @return 属性名 → 属性值 映射
    */
    public static Map<String, Object> getAnnotationAttributes(Annotation annotation) {
        Map<String, Object> attributes = new HashMap<>();
        if (annotation == null) {
            return attributes;
        }
        for (Method method : annotation.annotationType().getDeclaredMethods()) {
            try {
                Object value = ReflectUtils.invoke(annotation, method.getName(), Object.class);
                attributes.put(method.getName(), value);
            } catch (Exception e) {
                // ignore
            }
        }
        return attributes;
    }
}
