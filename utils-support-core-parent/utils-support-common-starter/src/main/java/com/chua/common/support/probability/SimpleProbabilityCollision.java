package com.chua.common.support.probability;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 简单概率碰撞实现。
 *
 * <p>基于 {@link ThreadLocalRandom} 的默认实现，线程安全、无共享状态；
 * 以 {@code nextDouble() < probability} 完成一次伯努利判定。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("simple")
@SpiDefault
public class SimpleProbabilityCollision implements ProbabilityCollision {

    @Override
    public boolean hit(double probability) {
        if (probability <= 0.0) {
            return false;
        }
        if (probability >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    @Override
    public double nextProbability() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
