package com.chua.ast.support.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编译期依赖检测器，缓存 classpath 中类的存在性判断结果
 *
 * <p>通过 {@code ProcessingEnvironment.getElementUtils().getTypeElement()} 判断类是否存在，
 * 结果缓存避免重复查询。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * DependencyDetector detector = new DependencyDetector(processingEnv);
 * if (detector.isPresent("org.slf4j.Logger")) {
 *     // slf4j 可用，生成 log.info(...)
 * } else {
 *     // slf4j 不可用，生成 System.out.println(...)
 * }
 * }</pre>
 *
 * @author CH
 */
public class DependencyDetector {

    private final javax.lang.model.util.Elements elementUtils;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    public DependencyDetector(ProcessingEnvironment processingEnv) {
        this.elementUtils = processingEnv.getElementUtils();
    }

    /**
     * 检测指定类是否存在于 classpath 中（带缓存）
     *
     * @param className 类全限定名
     * @return 如果类存在返回 true，否则返回 false
     */
    public boolean isPresent(String className) {
        return cache.computeIfAbsent(className, name -> {
            TypeElement typeElement = elementUtils.getTypeElement(name);
            return typeElement != null;
        });
    }
}
