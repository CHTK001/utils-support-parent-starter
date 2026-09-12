package com.chua.deeplearning.support.dl4j;

import com.chua.deeplearning.support.dl4j.train.ResNet50TransferTrainer;

/**
* 训练器工厂。
*
* <p>集中提供 {@link Trainer} 实现的获取与选择：默认返回迁移学习训练器
* {@link ResNet50TransferTrainer}；当以 Spring 容器运行时，可优先取注入到容器中的
* {@code Trainer} Bean（通过 {@link #setCurrent(Trainer)} 或 SPI 扩展替换实现）。</p>
*
* <p>同时提供 {@link #fluent()} 便捷入口，等价于 {@code get().fluent()}。</p>
*
* @author CH
* @since 4.0.0.42
 */
public final class TrainerFactory {

    /** 默认训练器实例（懒加载） */
    private static volatile Trainer current;

    /**
    * trainer工厂。
     */
    private TrainerFactory() {
    }

    /**
    * 获取当前训练器实现。
    *
    * @return 训练器
     */
    public static Trainer get() {
        Trainer t = current;
        if (t == null) {
            synchronized (TrainerFactory.class) {
                if (current == null) {
                    current = new ResNet50TransferTrainer();
                }
                t = current;
            }
        }
        return t;
    }

    /**
    * 替换当前训练器实现（例如注入 Spring 管理或自定义实现的 Bean）。
    *
    * @param trainer 新训练器；传 空 恢复默认
     */
    public static void setCurrent(Trainer trainer) {
        synchronized (TrainerFactory.class) {
            current = trainer;
        }
    }

    /**
    * 便捷入口：以当前训练器构建一条链式训练管道。
    *
    * @return 链式训练管道
     */
    public static ChainedTrainer fluent() {
        return get().fluent();
    }
}
