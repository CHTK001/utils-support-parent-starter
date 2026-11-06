package com.chua.common.support.objects.acceptance;

import com.chua.common.support.objects.definition.BeanDefinition;

/**
 * Bean 验收器 SPI：定义进入容器视图前的最后一道关卡。
 *
 * <p>多个验收器命中同一定义时，任一拒绝即拒绝；验收器自身抛异常按拒绝处理，
 * 由调用方（如 OSGI 桥接注册器）记录拒绝原因。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface BeanAcceptance {

    /**
     * 判断当前验收器是否对指定定义生效。
     *
     * @param beanDefinition 待验收的 Bean 定义
     * @return 生效返回 true，否则返回 false
     */
    boolean isSupport(BeanDefinition beanDefinition);

    /**
     * 对指定定义给出验收结论。
     *
     * @param beanDefinition 待验收的 Bean 定义
     * @return 验收结论，拒绝时携带原因
     */
    AcceptanceResult accept(BeanDefinition beanDefinition);

    /**
     * 验收结论。
     *
     * @param accepted 是否通过验收
     * @param reason   拒绝原因；通过时为 {@code null}
     */
    record AcceptanceResult(boolean accepted, String reason) {

        /**
         * 通过验收。
         *
         * @return 通过的验收结论
         */
        public static AcceptanceResult accept() {
            return new AcceptanceResult(true, null);
        }

        /**
         * 拒绝验收。
         *
         * @param reason 拒绝原因
         * @return 拒绝的验收结论
         */
        public static AcceptanceResult reject(String reason) {
            return new AcceptanceResult(false, reason);
        }
    }
}
