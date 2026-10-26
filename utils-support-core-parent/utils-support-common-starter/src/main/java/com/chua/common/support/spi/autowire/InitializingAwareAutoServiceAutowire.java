package com.chua.common.support.spi.autowire;

import com.chua.common.support.objects.describe.MethodDescribe;
import com.chua.common.support.objects.describe.ObjectDescribe;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 初始化感知自动装配
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class InitializingAwareAutoServiceAutowire implements ServiceAutowire {

    /**
     * Spring 初始化Bean 类名
     */
    private static final String SPRING = "org.springframework.beans.factory.InitializingBean";
    /**
     * Spring 初始化Bean 类型
     */
    private static Class<?> SPRING_TYPE;

    static {
        if (ClassUtils.isPresent(SPRING)) {
            SPRING_TYPE = ClassUtils.forName(SPRING);
        }
    }

    /**
     * Autowire
     */
    @Override
    public Object autowire(Object object) {
        if (null != SPRING_TYPE && SPRING_TYPE.isAssignableFrom(object.getClass())) {
            ObjectDescribe typeDescribe = ObjectDescribe.of(object);
            MethodDescribe methodDescribe = typeDescribe.getMethodDescribe("afterPropertiesSet");
            if (methodDescribe != null) {
                try {
                    methodDescribe.invoke();
                } catch (Exception e) {
                    log.debug("[autowire] afterPropertiesSet 调用失败，已忽略: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 创建Bean
     */
    @Override
    public Object createBean(Class<?> implClass) {
        return null;
    }
}
