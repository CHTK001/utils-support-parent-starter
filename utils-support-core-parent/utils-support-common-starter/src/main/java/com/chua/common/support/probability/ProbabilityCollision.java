package com.chua.common.support.probability;

/**
 * 概率碰撞 SPI（服务 提供者 接口）。
 *
 * <p>抽象一次「伯努利试验」：给定命中概率，返回本次是否命中。通过 SPI 暴露，
 * 业务侧不直接依赖具体随机实现，便于在需要时替换为可注入种子、可统计或分布式
 * 的实现；默认提供 {@code simple} 实现。</p>
 *
 * <h2>边界约定</h2>
 * <ul>
 *   <li>{@code probability <= 0} 时恒为 {@code false}；</li>
 *   <li>{@code probability >= 1} 时恒为 {@code true}；</li>
 *   <li>其余情况按真实随机判定。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ProbabilityCollision {

    /**
     * 以指定概率命中。
     *
     * @param probability 命中概率，取值 [0.0, 1.0]
     * @return 本次是否命中
     */
    boolean hit(double probability);

    /**
     * 以百分比形式命中。
     *
     * @param percent 命中概率百分比，取值 [0, 100]
     * @return 本次是否命中
     */
    default boolean hitPercent(double percent) {
        return hit(percent / 100.0);
    }

    /**
     * 返回 [0.0, 1.0) 之间的随机概率值。
     *
     * @return 随机概率
     */
    double nextProbability();
}
