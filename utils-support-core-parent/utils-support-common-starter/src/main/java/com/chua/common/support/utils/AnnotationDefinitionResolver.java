package com.chua.common.support.utils;

import com.chua.common.support.reflection.ReflectUtils;

import java.lang.annotation.Annotation;
import java.util.Collection;

/**
 * 注解定义解析器 SPI 接口。
 *
 * <p>实现类负责提供注解别名映射（如 {@code @GetMapping} → {@code @RequestMapping}），
 * 由 {@link AnnotationUtils#resolveAnnotationDefinition} 通过 SPI 发现并调用。</p>
 *
 * <pre>
 * // 示例：注册自定义别名解析器
 * ServiceProvider&lt;AnnotationDefinitionResolver&gt; provider =
 *     ServiceProvider.of(AnnotationDefinitionResolver.class);
 * provider.register("my-alias-resolver", new MyAliasResolver());
 * </pre>
 *
 * @author CH
 * @since 4.0.0.43
 * @see AnnotationUtils
 */
public interface AnnotationDefinitionResolver {

    /**
     * 获取当前解析器支持的别名映射。
     *
     * <p>返回 {@code 窄注解全限定名 → 宽注解全限定名} 的映射，
     * 用于在继承链中查找别名时展开搜索。</p>
     *
     * <pre>
     * // Spring MVC 示例：
     * {
     *   "org.springframework.web.bind.annotation.GetMapping"  = "org.springframework.web.bind.annotation.RequestMapping",
     *   "org.springframework.web.bind.annotation.PostMapping" = "org.springframework.web.bind.annotation.RequestMapping",
     *   "org.springframework.web.bind.annotation.RequestMapping" = "org.springframework.web.bind.annotation.RequestMapping"
     * }
     * </pre>
     *
     * @return 别名映射，不能为 {@code null}
     */
    Collection<AnnotationAliasMapping> getAliasMappings();

    /**
      * 将窄注解 类 解析为宽注解 类。
     *
     * @param narrowAnnotation 窄注解类型（如 {@code GetMapping.class}）
     * @return 宽注解类型（如 {@code RequestMapping.class}），无映射时返回原值
     */
    default Class<? extends Annotation> resolveAlias(Class<? extends Annotation> narrowAnnotation) {
        if (narrowAnnotation == null) {
            return narrowAnnotation;
        }
        String name = narrowAnnotation.getName();
        for (AnnotationAliasMapping mapping : getAliasMappings()) {
            if (mapping.getNarrowName().equals(name)) {
                Class<? extends Annotation> cls = (Class<? extends Annotation>) ReflectUtils.forName(mapping.getWideName());
                return cls != null ? cls : narrowAnnotation;
            }
        }
        return narrowAnnotation;
    }

    /**
      * 判断给定的注解 类 是否为映射别名（有对应的宽注解）。
     *
     * @param annotationClass 注解类型
     * @return 如果存在别名映射返回 {@code true}
     */
    default boolean isAlias(Class<? extends Annotation> annotationClass) {
        if (annotationClass == null) {
            return false;
        }
        String name = annotationClass.getName();
        return getAliasMappings().stream()
                .anyMatch(m -> m.getNarrowName().equals(name));
    }

    /**
     * 注解别名映射，描述窄注解与宽注解的对应关系。
     * @author CH
     * @since 4.0.0
     * @return 获取wide名称的结果
     */
    final class AnnotationAliasMapping {
        private final String narrowName; // narrow名称
        private final String wideName; // wide名称
/**
 * 注解别名mapping。
 * @param narrowName narrow名称
 * @param wideName wide名称
 * @return 获取wide名称的结果
 */

        public AnnotationAliasMapping(String narrowName, String wideName) {
            this.narrowName = narrowName;
            this.wideName = wideName;
        }

        public String getNarrowName() {
            return narrowName;
        }

        public String getWideName() {
            return wideName;
        }

        @Override
        public String toString() {
            return narrowName + " → " + wideName;
        }
    }
}
