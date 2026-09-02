package com.chua.deeplearning.support.dl4j;

/**
 * 训练过程回调接口。
 *
 * <p>训练器在关键阶段（开始、每轮 epoch 结束、完成、失败）调用此接口上报进度，
 * 训练平台可借此实现进度推送或状态轮询。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TrainListener {

    /**
     * 训练即将开始（加载数据、构建模型阶段）。
     *
     * @param progress 当前进度快照
     */
    void onStarted(TrainProgress progress);

    /**
     * 当前 epoch 训练完成。
     *
     * @param progress 当前进度快照（包含 currentEpoch、bestScore 等已更新值）
     */
    void onEpochCompleted(TrainProgress progress);

    /**
     * 训练成功完成。
     *
     * @param progress 最终进度快照（包含 modelPath、classLabels）
     */
    void onFinished(TrainProgress progress);

    /**
     * 训练过程中发生异常而失败。
     *
     * @param progress 当前进度快照（status=FAILED，error 已填充）
     * @param cause    原始异常
     */
    void onFailed(TrainProgress progress, Throwable cause);

    /**
     * 空实现：忽略所有回调，适用于不需要进度上报的场景。
     */
    TrainListener NOOP = new TrainListener() {
        @Override
        public void onStarted(TrainProgress progress) { }

        @Override
        public void onEpochCompleted(TrainProgress progress) { }

        @Override
        public void onFinished(TrainProgress progress) { }

        @Override
        public void onFailed(TrainProgress progress, Throwable cause) { }
    };
}