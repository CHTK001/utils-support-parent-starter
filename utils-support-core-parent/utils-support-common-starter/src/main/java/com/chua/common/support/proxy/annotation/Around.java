package com.chua.common.support.proxy.annotation;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.MatchUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 环绕拦截注解，用于声明方法签名级别的环绕拦截器匹配规则。
 *
 * <p>该注解标注在 {@link com.chua.common.support.proxy.intercept.MethodArroundIntercept}
 * 实现类上，声明该拦截器需要匹配哪些方法。与基于注解类型的 {@link MethodAnnotationIntercept}
 * 不同，{@code @Around} 走<b>方法签名/方法名匹配</b>路线，适用于那些不依赖业务注解、
 * 想覆盖一类方法（如所有 {@code save*} 方法、所有 {@code Service#*} 方法）的横切关注点，
 * 如统一日志、全方法耗时统计、全局异常归一化等。</p>
 *
 * <p><b>简化后的语义：</b></p>
 * <ul>
 *   <li><b>省略 {@code value()}</b>：匹配所有方法，等价于切点 {@code "*"}。最简用法，
 *       适合需要"全方法 around"的能力（如全局 tracing）。</li>
 *   <li><b>填写 {@code value()}</b>：每个 pattern 可以是方法名（如 {@code "save*"}）
 *       或全签名（如 {@code "com.foo.UserService#saveUser(..)"}）。匹配策略由 {@link #matchType()}
 *       决定，默认 {@link MatchUtils.MatchType#AUTO} 自动判断。</li>
 *   <li><b>不指定 {@code order()}</b>：执行顺序由 {@link com.chua.common.support.proxy.intercept.MethodArroundIntercept#order()}
 *       覆盖，进一步简化。两处都未指定则使用默认值 1000。</li>
 * </ul>
 *
 * <p><b>SPI 注册约定：</b></p>
 * <p>{@link com.chua.common.support.proxy.intercept.MethodArroundIntercept} 实现类应当使用
 * {@link Spi @Spi} 标注并给定一个 SPI 名（任何便于定位的名字即可，框架按 SPI 名查找后才会
 * 读取 {@code @Around} 做签名匹配），以便代理能从任意包加载到拦截器实现。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 1. 全方法 around（最简）
 * @Spi("global-trace")
 * @Around
 * public class GlobalTraceIntercept implements MethodArroundIntercept {
 *     @Override
 *     public Object invoke(ProxyMethod pm, MethodInvocation ivk) throws Throwable {
 *         long start = System.nanoTime();
 *         try {
 *             return ivk.proceed();
 *         } finally {
 *             log.info("{} cost {} ns", pm.getMethod().getName(), System.nanoTime() - start);
 *         }
 *     }
 * }
 *
 * // 2. 按方法名前缀匹配
 * @Spi("save-audit")
 * @Around("save*")
 * public class SaveAuditIntercept implements MethodArroundIntercept { ... }
 *
 * // 3. 按全签名精确匹配
 * @Spi("user-save-log")
 * @Around("com.foo.UserService#saveUser")
 * public class UserSaveIntercept implements MethodArroundIntercept { ... }
 *
 * // 4. 多 pattern + 指定匹配策略
 * @Spi("repo-write-trace")
 * @Around(value = {"com.foo.*Repository#save*", "com.foo.*Repository#update*"}, matchType = MatchUtils.MatchType.WILDCARD)
 * public class RepoWriteIntercept implements MethodArroundIntercept { ... }
 * }</pre>= 匹配工具.匹配类型.WILDCARD)
 * 公共 类 repo写入intercept implements 方法arroundintercept { ... }
 * }</pre>
 *
 * @author CH
 * @since 2025/11/26
 * @版本 1.1.0
 * @see com.chua.common.support.proxy.intercept.MethodArroundIntercept
 * @see MethodAnnotationIntercept
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Around {

    /**
     * 方法匹配模式数组，支持以下两种形态：
     * <ul>
     *     <li>{@code com.foo.UserService#saveUser(..)} — 全签名匹配</li>
     *     <li>{@code save*} — 仅方法名匹配（框架会同时匹配并任一命中即生效）</li>
     * </ul>
     *
     * <p>留空数组（默认值）表示<b>匹配所有方法</b>，等价于切点 {@code "*"}。</p>
     *
     * @return 匹配模式数组，空数组代表全方法匹配
     */
    String[] value() default {};

    /**
     * 匹配类型，支持精确匹配、通配符匹配、正则匹配和自动匹配。
     *
     * <p>默认 {@link MatchUtils.MatchType#AUTO} 由框架根据 pattern 形态自动判断：
     * 含 {@code #} 视为签名匹配，否则视为方法名匹配；含 {@code * / ?} 视为通配符，否则精确匹配。</p>
     *
     * @return 匹配类型，默认 AUTO
     */
    MatchUtils.MatchType matchType() default MatchUtils.MatchType.AUTO;

    /**
     * 拦截器执行顺序，数值越小优先级越高（越靠外层执行）。
     *
     * <p>仅作为 {@code @Around} 上的快速覆盖，未指定（即使用默认 1000）时
     * 回落到 {@link com.chua.common.support.proxy.intercept.MethodArroundIntercept#order()} 的返回值；
     * 二者都显式指定时取较小值（更靠外的生效）。</p>
     *
     * @return 执行顺序值，默认 1000 表示不覆盖
     */
    int order() default 1000;
}
