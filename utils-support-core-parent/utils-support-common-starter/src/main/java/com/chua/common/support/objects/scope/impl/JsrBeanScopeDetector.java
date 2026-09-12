package com.chua.common.support.objects.scope.impl;

import com.chua.common.support.objects.definition.BeanScope;
import com.chua.common.support.objects.scope.BeanScopeDetector;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.lang.annotation.Annotation;

/**
* JSR/CDI 作用域检测器。
*
* <p>通过反射检测 javax/jakarta CDI 作用域注解，不依赖编译时 API。
* 支持的注解：
* <ul>
*   <li>@Dependent → PROTOTYPE</li>
*   <li>@RequestScoped → PROTOTYPE</li>
*   <li>@SessionScoped → PROTOTYPE</li>
*   <li>@Singleton → SINGLETON</li>
*   <li>@ApplicationScoped → SINGLETON</li>
* </ul></p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("jsr")
@SpiDescribe("JSR/CDI 作用域检测器")
public class JsrBeanScopeDetector implements BeanScopeDetector {

    @Override
    /** Detect */
    public BeanScope detect(Class<?> beanClass) {
        if (beanClass == null) {
            return null;
        }
        for (Annotation ann : beanClass.getAnnotations()) {
            String name = ann.annotationType().getName();
            if ("javax.enterprise.context.Dependent".equals(name)
                    || "jakarta.enterprise.context.Dependent".equals(name)
                    || "javax.enterprise.context.RequestScoped".equals(name)
                    || "jakarta.enterprise.context.RequestScoped".equals(name)
                    || "javax.enterprise.context.SessionScoped".equals(name)
                    || "jakarta.enterprise.context.SessionScoped".equals(name)
                    || "javax.enterprise.context.ConversationScoped".equals(name)
                    || "jakarta.enterprise.context.ConversationScoped".equals(name)) {
                return BeanScope.PROTOTYPE;
            }
            if ("javax.inject.Singleton".equals(name)
                    || "jakarta.inject.Singleton".equals(name)
                    || "javax.enterprise.context.ApplicationScoped".equals(name)
                    || "jakarta.enterprise.context.ApplicationScoped".equals(name)) {
                return BeanScope.SINGLETON;
            }
        }
        return null;
    }
}
