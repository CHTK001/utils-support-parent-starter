package com.chua.common.support.spi.autowire;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 服务自动装配接口
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ServiceAutowire {

    /**
     * 对已有对象进行依赖注入/属性装配
     *
     * @param object 需要装配的目标对象
     * @return 装配后的对象
     */
    Object autowire(Object object);

    /**
     * 根据指定的类创建Bean实例并完成属性装配
     *
     * @param implClass Bean的实现类
     * @return 创建并装配完成的Bean实例
     */
    Object createBean(Class<?> implClass);
}
