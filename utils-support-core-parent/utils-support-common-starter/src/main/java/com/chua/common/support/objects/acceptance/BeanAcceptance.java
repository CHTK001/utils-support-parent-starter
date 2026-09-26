package com.chua.common.support.objects.acceptance;

import com.chua.common.support.objects.definition.BeanDefinition;

/**
 * Bean 验收器：在外部对象被正式接纳进容器之前，对其做准入判定。
 * <p>
 * 容器侧对「由外部框架托管、经桥接纳入」的对象（如 OSGI 服务、Spring Bean），
 * 默认直接信任并放行。这类对象不受容器生命周期管辖，一旦其来源方
 * （bundle 被卸载、远程注册中心下线、依赖的扩展包被替换）发生变化，
 * 容器侧无从感知，坏引用会一路带到业务调用点。
 * </p>
 * <p>
 * 验收器提供一道显式准入关卡：实现类只关心自己能识别的那类 Bean 定义，
 * 返回是否放行以及拒绝原因，由调用方负责记录并跳过。
 * 多个验收器同时命中同一 Bean 定义时，<b>任一拒绝即拒绝</b>（与限流、鉴权类关卡语义一致）。
 * </p>
 * <p>
 * 验收器通过 SPI 装载，标记 {@link com.chua.common.support.spi.annotations.SpiIgnore} 者不参与装载。
 * </p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface BeanAcceptance {

    /**
     * 判断当前验收器是否参与该 Bean 定义的验收。
     *
     * @param beanDefinition 待验收的 Bean 定义
     * @return 参与验收返回 true，不关心返回 false
     */
    boolean isSupport(BeanDefinition beanDefinition);

    /**
     * 执行验收判定。
     * <p>
     * 实现方应只做无副作用的准入检查，不要在此处修改 Bean 定义或触发实例化。
     * </p>
     *
     * @param beanDefinition 待验收的 Bean 定义
     * @return 验收结论
     */
    AcceptanceResult accept(BeanDefinition beanDefinition);

    /**
     * 验收结论。
     *
     * @param accepted 是否放行
     * @param reason   拒绝原因，放行时为 {@code null}
     */
    record AcceptanceResult(boolean accepted, String reason) {

        /**
         * 构造放行结论。
         *
         * @return 放行结论
         */
        public static AcceptanceResult accept() {
            return new AcceptanceResult(true, null);
        }

        /**
         * 构造拒绝结论。
         *
         * @param reason 拒绝原因
         * @return 拒绝结论
         */
        public static AcceptanceResult reject(String reason) {
            return new AcceptanceResult(false, reason);
        }
    }
}
