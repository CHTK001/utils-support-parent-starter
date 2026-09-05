package com.chua.ueba.support;

import com.chua.ueba.support.engine.UebaEngineBuilder;
import com.chua.ueba.support.training.UebaTrainingBuilder;

/**
 * UEBA 统一门面。
 * <p>
 * 提供链式创建"使用（分析）"与"训练（建模）"两条流程的唯一入口：
 * </p>
 * <ul>
 *   <li>{@link #engine()}：链式构建分析引擎 {@code UebaEngine}，
 *       如 {@code Ueba.engine().configResource("ueba-config.yaml").modelDir("D:/models").build()}</li>
 *   <li>{@link #training()}：链式构建训练管线 {@code UebaTrainer}，
 *       如 {@code Ueba.training().configResource("ueba-config.yaml").data("access.csv").epochs(60).build().execute()}</li>
 * </ul>
 * <p>两条流程共享同一份 {@code ueba-config.yaml}，保证训练与识别的特征顺序、
 * 归一化参数与类别词表完全一致。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Ueba {

    /**
     * 私有构造，防止实例化。
     */
    private Ueba() {
    }

    /**
     * 创建分析引擎构建器。
     *
     * @return 引擎构建器，可链式配置后调用 {@code build()}
     */
    public static UebaEngineBuilder engine() {
        return new UebaEngineBuilder();
    }

    /**
     * 创建训练管线构建器。
     *
     * @return 训练构建器，可链式配置后调用 {@code build()} / {@code execute()}
     */
    public static UebaTrainingBuilder training() {
        return new UebaTrainingBuilder();
    }
}