package com.chua.spring.support.utils;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.AnnotationDefinitionResolver;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
* Spring MVC 注解族别名解析器，通过 SPI 注册到 {@link com.chua.common.support.utils.AnnotationUtils}。
*
* <h3>别名发现原理</h3>
* <pre>
* Spring 注解在源码中以元注解方式声明继承关系：
*
*   &#064;RequestMapping(value = {}, method = {})
*   public &#064;interface GetMapping { ... }
*
*   &#064;RequestMapping(value = {}, method = {})
*   public &#064;interface PostMapping { ... }
*
* 运行时通过反射扫描：
*   1. 定位 org.springframework.web.bind.annotation.RequestMapping 类
*   2. 扫描同包下所有 public 注解类（.class 文件）
*   3. 对每个注解检查：annClass.getAnnotation(RequestMapping.class) != null
*   4. 满足条件的即为其窄注解，建立 narrow → wide 映射
*
* 缓存使用 WeakHashMap，key 为窄注解 Class 引用：
*   窄注解类被 GC 回收时缓存自动清理，防止类加载器泄漏。
* </pre>
*
* @author CH
* @since 4.0.0.43
 */
public class SpringMvcAnnotationDefinitionResolver implements AnnotationDefinitionResolver {

    /**
    * 窄注解 类 → 宽注解全限定名，weak哈希映射 键 随类加载器回收自动清理。
    */
    private final java.util.Map<Class<? extends Annotation>, String> aliasCache = new WeakHashMap<>();

    @Override
    public List<AnnotationAliasMapping> getAliasMappings() {
        discoverAliases();
        List<AnnotationAliasMapping> result = new ArrayList<>();
        for (java.util.Map.Entry<Class<? extends Annotation>, String> entry : aliasCache.entrySet()) {
            result.add(new AnnotationAliasMapping(entry.getKey().getName(), entry.getValue()));
        }
        return result;
    }

    /**
    * 通过反射动态发现 Spring MVC 注解族别名。
    *
    * <p>扫描策略：
    * <ol>
    *   <li>定位 {@code RequestMapping} 类，获取其所在包路径</li>
    *   <li>扫描包目录下的所有 {@code .class} 文件（排除内部类）</li>
    *   <li>对每个注解检查其元注解中是否包含 {@code @RequestMapping}</li>
    *   <li>满足条件的建立 narrow → wide 映射</li>
    * </ol>
    *
    * <p>Fallback：若包目录不可直接访问，使用已知候选全限定名列表，
    * 同样通过反射检查元注解关系，不硬编码映射内容。</p>
    */
    @SuppressWarnings("unchecked")
    private void discoverAliases() {
        if (!aliasCache.isEmpty()) {
            return;
        }
        try {
            Class<?> requestMappingClass = ReflectUtils.forName(
                    "org.springframework.web.bind.annotation.RequestMapping");

            // 策略1：通过包目录扫描
            java.net.URL packageUrl = requestMappingClass.getResource(".");
            if (packageUrl != null) {
                java.io.File packageDir = new java.io.File(packageUrl.toURI());
                if (packageDir.isDirectory()) {
                    for (java.io.File file : packageDir.listFiles((dir, name) ->
                            name.endsWith(".class") && !name.startsWith("$"))) {
                        String className = requestMappingClass.getPackageName() + "." +
                                file.getName().replace(".class", "");
                        loadIfMappingAlias(className, requestMappingClass);
                    }
                    return;
                }
            }

 // 策略2：降级 — 已知候选名 + 反射验证元注解关系
            String[] candidates = {
                    "org.springframework.web.bind.annotation.GetMapping",
                    "org.springframework.web.bind.annotation.PostMapping",
                    "org.springframework.web.bind.annotation.PutMapping",
                    "org.springframework.web.bind.annotation.DeleteMapping",
                    "org.springframework.web.bind.annotation.PatchMapping",
                    "org.springframework.web.bind.annotation.RequestMapping"
            };
            for (String name : candidates) {
                loadIfMappingAlias(name, requestMappingClass);
            }
        } catch (Exception ignored) {
 // Spring 不在 类路径，忽略
        }
    }

    /**
    * 尝试加载单个候选注解：通过反射检查是否被 @请求mapping 元注解标注。
    * @param className 类名称
    * @param requestMappingClass 请求mapping类
    */
    @SuppressWarnings("unchecked")
    private void loadIfMappingAlias(String className, Class<?> requestMappingClass) {
        try {
            Class<?> rawClass = ReflectUtils.forName(className,
                    Thread.currentThread().getContextClassLoader());
            if (rawClass == null) {
                return;
            }
            Class<? extends Annotation> annClass = (Class<? extends Annotation>) rawClass;
            if (!annClass.isAnnotation()) {
                return;
            }
            // 核心：通过反射检查元注解关系，不硬编码映射内容
            Object mappingAnnotation = annClass.getAnnotation((Class<? extends Annotation>) requestMappingClass);
            if (mappingAnnotation != null) {
                aliasCache.put(annClass,
                        requestMappingClass.getName());
            }
        } catch (Exception ignored) {
        }
    }
}
