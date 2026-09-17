package com.chua.common.support.objects.scope;

import com.chua.common.support.objects.definition.BeanScope;
import com.chua.common.support.spi.annotations.Spi;

/**
 * Bean 作用域检测器 SPI。
 *
 * <p>通过反射按类名字符串检测类上的注解，判断 Bean 的作用域。
 * 各框架实现此接口来识别各自的作用域注解。</p>
 *
 * <p>返回 {@code null} 表示不支持 / 不匹配，由下一个检测器继续判断。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
@Spi
public interface BeanScopeDetector {

    /**
    * 检测 Bean 作用域。
    *
    * @param beanClass Bean 类
    * @return 检测结果，null 表示不匹配
    */
    BeanScope detect(Class<?> beanClass);
}
