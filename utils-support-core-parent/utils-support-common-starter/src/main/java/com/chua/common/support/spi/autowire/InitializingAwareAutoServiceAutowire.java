package com.chua.common.support.spi.autowire;

import com.chua.common.support.objects.describe.MethodDescribe;
import com.chua.common.support.objects.describe.ObjectDescribe;
import com.chua.common.support.utils.ClassUtils;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 初始化感知自动装配
 *
 * @author CH
 */
public class InitializingAwareAutoServiceAutowire implements ServiceAutowire {

    /**
     * Spring InitializingBean 类名
     */
    private static final String SPRING = "org.springframework.beans.factory.InitializingBean";
    /**
     * Spring InitializingBean 类型
     */
    private static Class<?> SPRING_TYPE;

    static {
        if (ClassUtils.isPresent(SPRING)) {
            SPRING_TYPE = ClassUtils.forName(SPRING);
        }
    }

    @Override
    public Object autowire(Object object) {
        if (null != SPRING_TYPE && SPRING_TYPE.isAssignableFrom(object.getClass())) {
            ObjectDescribe typeDescribe = ObjectDescribe.of(object);
            MethodDescribe methodDescribe = typeDescribe.getMethodDescribe("afterPropertiesSet");
            if (methodDescribe != null) {
                try {
                    methodDescribe.invoke(object);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    @Override
    public Object createBean(Class<?> implClass) {
        return null;
    }
}
