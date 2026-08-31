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
 * @author CH
 * @since 4.0.0.43
 */
public class AnnotationUtils {

    // ---- static aliases (Spring MVC defaults, loaded via reflection) ----

    private static final Set<String> ALIAS_SOURCE_SET = new ConcurrentReferenceHashMap<>(64);
    private static final Map<String, String> ALIAS_NARROW_TO_WIDE = new ConcurrentReferenceHashMap<>(32);
    private static volatile boolean aliasesLoaded = false;

    /**
     * 懒加载默认别名（Spring MVC）：通过反射检查类是否存在，不存在则跳过。
     */
    private static void ensureAliasesLoaded() {
        if (aliasesLoaded) {
            return;
        }
        synchronized (AnnotationUtils.class) {
            if (aliasesLoaded) {
                return;
            }
            loadSpringMvcAliases();
            loadSpiAliases();
            aliasesLoaded = true;
        }
    }

    private static void loadSpringMvcAliases() {
        String[] narrowAnns = {
                "org.springframework.web.bind.annotation.GetMapping",
                "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping",
                "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping"
        };
        String wide = "org.springframework.web.bind.annotation.RequestMapping";
        try {
            for (String narrow : narrowAnns) {
                if (ClassUtils.isPresent(narrow)) {
                    Class<?> narrowClass = ClassUtils.forName(narrow);
                    Class<?> wideClass = ClassUtils.forName(wide);
                    if (narrowClass != null && wideClass != null) {
                        ALIAS_NARROW_TO_WIDE.put(narrow, wide);
                        ALIAS_SOURCE_SET.add(narrow);
                        ALIAS_SOURCE_SET.add(wide);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void loadSpiAliases() {
        try {
            ServiceProvider<AnnotationDefinitionResolver> provider =
                    ServiceProvider.of(AnnotationDefinitionResolver.class);
            for (AnnotationDefinitionResolver resolver : provider.collect()) {
                for (AnnotationDefinitionResolver.AnnotationAliasMapping mapping : resolver.getAliasMappings()) {
                    ALIAS_NARROW_TO_WIDE.put(mapping.getNarrowName(), mapping.getWideName());
                    ALIAS_SOURCE_SET.add(mapping.getNarrowName());
                    ALIAS_SOURCE_SET.add(mapping.getWideName());
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
        // 2. 继承链匹配（父类/父接口/重写方法）
        if (element instanceof Class) {
            if (hasInheritedAnnotation((Class<?>) element, annotationClass)) {
                return true;
            }
        } else if (element instanceof Method) {
            if (hasOverriddenAnnotation((Method) element, annotationClass)) {
                return true;
            }
        }
        // 3. 别名穿透：检查是否有窄注解替代
        ensureAliasesLoaded();
        if (isAlias(annotationClass)) {
            return hasAliasMatch(element, annotationClass);
        }
        return false;
    }

    /**
     * 判断类是否存在指定注解（含继承链 + 别名穿透）。
     *
     * @param clazz           目标类
     * @param annotationClass 待检查的注解类型
     * @return 如果存在则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isAnnotationPresent(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        if (clazz == null || annotationClass == null) {
            return false;
        }
        return isAnnotationPresent((AnnotatedElement) clazz, annotationClass);
    }

    /**
     * 判断方法是否存在指定注解（含继承链 + 别名穿透）。
     *
     * @param method          目标方法
     * @param annotationClass 待检查的注解类型
     * @return 如果存在则返回 {@code true}
     * @since 4.0.0.43
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
     * <p>子类注解优先于父类注解；别名命中时返回窄注解实例本身。</p>
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

    /**
     * 获取类上的注解（继承链 + 别名穿透查找），未找到时返回 {@code null}。
     *
     * @param clazz           目标类
     * @param annotationClass 注解类型
     * @param <A>             注解泛型
     * @return 找到的注解实例，未找到返回 {@code null}
     * @since 4.0.0.43
     */
    public static <A extends Annotation> A getAnnotation(Class<?> clazz, Class<A> annotationClass) {
        return getAnnotation((AnnotatedElement) clazz, annotationClass);
    }

    /**
     * 获取方法上的注解（继承链 + 别名穿透查找），未找到时返回 {@code null}。
     *
     * @param method          目标方法
     * @param annotationClass 注解类型
     * @param <A>             注解泛型
     * @return 找到的注解实例，未找到返回 {@code null}
     * @since 4.0.0.43
     */
    public static <A extends Annotation> A getAnnotation(Method method, Class<A> annotationClass) {
        return getAnnotation((AnnotatedElement) method, annotationClass);
    }

    /**
     * 解析注解定义：从当前元素向上遍历继承链，
     * 按「子类优先 → 直接声明 > 继承 > 别名映射」顺序返回第一个匹配的定义。
     *
     * <pre>
     * // MyClass 直接有 @GetMapping("/users")
     * AnnotationDefinition<?> def = AnnotationUtils.resolveAnnotationDefinition(MyClass.class, RequestMapping.class);
     * // def.getSource() == DIRECT, def.getAnnotation() == GetMapping 实例
     *
     * // BaseController 直接有 @RequestMapping("/api")
     * AnnotationDefinition<?> def2 = AnnotationUtils.resolveAnnotationDefinition(BaseController.class, RequestMapping.class);
     * // def2.getSource() == DIRECT
     * </pre>
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

        // 构建所有要搜索的注解类型集合（自身 + 所有别名）
        Set<Class<? extends Annotation>> searchTypes = buildSearchSet(annotationClass);

        // 1. 当前元素直接声明（最高优先级）
        for (Class<? extends Annotation> annClass : searchTypes) {
            try {
                A ann = element.getAnnotation(annClass);
                if (ann != null) {
                    return (AnnotationDefinition<A>) createDefinition(ann, annClass,
                            element, Source.DIRECT, false);
                }
            } catch (Exception ignored) {
            }
        }

        // 2. 继承链（父类 / 父接口）
        if (element instanceof Class) {
            AnnotationDefinition<A> inherited = resolveFromHierarchy((Class<?>) element, annotationClass, searchTypes, Source.INHERITED);
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

    /**
     * 解析注解定义（类重载）。
     *
     * @param clazz           目标类
     * @param annotationClass 待解析的注解类型
     * @param <A>             注解泛型
     * @return 注解定义，未找到返回 {@code null}
     * @since 4.0.0.43
     */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation> AnnotationDefinition<A> resolveAnnotationDefinition(
            Class<?> clazz, Class<A> annotationClass) {
        return resolveAnnotationDefinition((AnnotatedElement) clazz, annotationClass);
    }

    /**
     * 解析注解定义（方法重载）。
     *
     * @param method          目标方法
     * @param annotationClass 待解析的注解类型
     * @param <A>             注解泛型
     * @return 注解定义，未找到返回 {@code null}
     * @since 4.0.0.43
     */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation> AnnotationDefinition<A> resolveAnnotationDefinition(
            Method method, Class<A> annotationClass) {
        return resolveAnnotationDefinition((AnnotatedElement) method, annotationClass);
    }

    /**
     * 判断类是否包含任意已知映射注解（{@code @RequestMapping}、{@code @GetMapping} 等）。
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
                Class<?> annClass = Class.forName(alias);
                if (isAnnotationPresent(clazz, (Class<? extends Annotation>) annClass)) {
                    return true;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * 判断方法是否包含任意已知映射注解（{@code @RequestMapping}、{@code @GetMapping} 等）。
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
                Class<?> annClass = Class.forName(alias);
                if (isAnnotationPresent(method, (Class<? extends Annotation>) annClass)) {
                    return true;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * 将窄注解 Class 解析为对应的宽注解 Class。
     *
     * <p>先从 SPI 解析器查找，再回退到内置别名表。</p>
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
            return (Class<? extends Annotation>) Class.forName(resolved);
        } catch (ClassNotFoundException e) {
            return annotationClass;
        }
    }

    /**
     * 将窄注解全限定名解析为对应的宽注解全限定名。
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

    // ---- private helpers ----

    private static boolean isAlias(Class<? extends Annotation> annotationClass) {
        return ALIAS_SOURCE_SET.contains(annotationClass.getName());
    }

    /**
     * 构建搜索集合：目标注解 + 所有别名（双向）。
     */
    private static Set<Class<? extends Annotation>> buildSearchSet(Class<? extends Annotation> target) {
        Set<Class<? extends Annotation>> set = new LinkedHashSet<>();
        set.add(target);
        ensureAliasesLoaded();
        String targetName = target.getName();
        // 正向：target 是窄注解，加入其宽注解
        String wide = ALIAS_NARROW_TO_WIDE.get(targetName);
        if (wide != null && !wide.equals(targetName)) {
            try {
                set.add((Class<? extends Annotation>) Class.forName(wide));
            } catch (ClassNotFoundException ignored) {
            }
        }
        // 反向：target 是宽注解，加入所有窄注解
        for (Map.Entry<String, String> entry : ALIAS_NARROW_TO_WIDE.entrySet()) {
            if (entry.getValue().equals(targetName) && !entry.getKey().equals(targetName)) {
                try {
                    set.add((Class<? extends Annotation>) Class.forName(entry.getKey()));
                } catch (ClassNotFoundException ignored) {
                }
            }
        }
        return set;
    }

    /**
     * 检查别名穿透：目标注解没有直接命中，但有别名命中。
     */
    private static boolean hasAliasMatch(AnnotatedElement element, Class<? extends Annotation> annotationClass) {
        for (Class<? extends Annotation> alt : buildSearchSet(annotationClass)) {
            if (!alt.equals(annotationClass) && element.isAnnotationPresent(alt)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 别名穿透时实际返回的注解实例（窄注解实例）。
     */
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

    /**
     * 在继承链中按「子类优先」语义查找注解定义。
     *
     * <p>从当前类开始向上遍历：优先返回子类直接声明的，再返回父类继承的。</p>
     */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> AnnotationDefinition<A> resolveFromHierarchy(
            Class<?> clazz, Class<A> targetAnnotation,
             Set<Class<? extends Annotation>> searchTypes, AnnotationDefinition.Source source) {
        // 先找子类（当前类及子类型）的直接声明，优先级高于父类
        AnnotationDefinition<A> subclassDirect = findDirectInSubclassHierarchy(clazz, searchTypes, source);
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
                        return (AnnotationDefinition<A>) createDefinition(ann, annClass, superClass, Source.INHERITED, false);
                    }
                } catch (Exception ignored) {
                }
            }
            AnnotationDefinition<A> parentResult = resolveFromHierarchy(superClass, targetAnnotation, searchTypes, source);
            if (parentResult != null) {
                return parentResult;
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            for (Class<? extends Annotation> annClass : searchTypes) {
                try {
                    A ann = (A) iface.getAnnotation(annClass);
                    if (ann != null) {
                        return (AnnotationDefinition<A>) createDefinition(ann, annClass, iface, Source.INHERITED, false);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 查找子类层次中直接声明的注解（子类优先于父类）。
     */
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> AnnotationDefinition<A> findDirectInSubclassHierarchy(
             Class<?> clazz, Set<Class<? extends Annotation>> searchTypes, AnnotationDefinition.Source source) {
        for (Class<? extends Annotation> annClass : searchTypes) {
            try {
                A ann = (A) clazz.getAnnotation(annClass);
                if (ann != null) {
                    return (AnnotationDefinition<A>) createDefinition(ann, annClass, clazz, source,
                            !clazz.getName().startsWith("java.") && clazz.getSuperclass() != null);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 从方法重写链中查找注解定义。
     */
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
                                    return (AnnotationDefinition<A>) createDefinition(ann, annClass, superMethod,
                                            Source.OVERRIDDEN_METHOD, false);
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

    private static Object createDefinition(Annotation annotation, Class<? extends Annotation> annotationClass,
                                           AnnotatedElement element, AnnotationDefinition.Source source, boolean subclassOverrides) {
        if (element instanceof Class) {
            Class<?> clazz = (Class<?>) element;
            if (subclassOverrides && clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
                return AnnotationDefinition.subclassOverrides((Class<Annotation>) annotationClass, (Annotation) annotation);
            }
        }
        switch (source) {
            case DIRECT:
                return AnnotationDefinition.ofDirect((Annotation) annotation, (Class<Annotation>) annotationClass);
            case INHERITED:
                return AnnotationDefinition.ofInherited((Annotation) annotation, (Class<Annotation>) annotationClass);
            case OVERRIDDEN_METHOD:
                return AnnotationDefinition.ofOverriddenMethod((Annotation) annotation, (Class<Annotation>) annotationClass);
            case ALIAS_RESOLVED:
                return AnnotationDefinition.ofAliasResolved((Annotation) annotation, (Class<Annotation>) annotationClass);
            default:
                return AnnotationDefinition.ofDirect((Annotation) annotation, (Class<Annotation>) annotationClass);
        }
    }

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
     * 获取注解属性
     *
     * @param clazz           类
     * @param annotationClass 注解类
     * @return 注解属性映射
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
}
