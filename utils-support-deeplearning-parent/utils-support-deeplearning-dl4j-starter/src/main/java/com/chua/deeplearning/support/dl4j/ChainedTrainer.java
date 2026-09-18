package com.chua.deeplearning.support.dl4j;

import com.chua.deeplearning.support.dl4j.train.ResNet50TransferTrainer;

/**
* 可链式调用的训练管道（Fluent API）。
*
* <p>在底层 {@link Trainer}（当前为 {@link ResNet50TransferTrainer} 迁移学习训练器）之上，
* 提供一套声明式、可链式拼接的训练 DSL：用「只读配置 + 一次性 fit」模型，
* 让调用方以更贴合直觉的方式编排超参数与数据路径：</p>
*
* <pre>{@code
* TrainResult result = Trainer.fluent()            // 或 TrainerFactory.get().fluent()
*         .data("/data/imagenet")
*         .saveTo("/models/resnet50")
*         .argument(TrainArgument.defaults())
*         .epochs(20).batchSize(8).learningRate(1e-3)
*         .classifier(3)
*         .listener(new ProgressLogger())
*         .fit();
* }</pre>         .classifier(3)
*         .listener(new ProgressLogger())
*         .fit();
* }</pre>
*
* <p>每次 {@link #fit()} 消费当前配置并执行一次训练；本类是不可变/一次性使用语义——
* 分支 出的新管道不会影响源管道，便于并行试验不同超参数。</p>
*
* @author CH
* @since 4.0.0.42
 */
public final class ChainedTrainer {

    /** 底层训练器 */
    private final Trainer trainer;
    /** 训练超参数（可变副本，避免污染 默认 缓存） */
    private final TrainArgument argument;
    /** 预训练模型路径 */
    private final String modelPath;
    /** 模型保存路径 */
    private final String savePath;
    /** 训练数据根目录（子目录为类别） */
    private final String dataRootPath;
    /** 进度回调 */
    private final TrainListener listener;

    /**
    * 私有构造，仅供 {@link #of(Trainer)} 与链式 方法 使用。
    *
    * @param trainer      底层训练器
    * @param argument     超参数
    * @param modelPath    预训练模型路径
    * @param savePath     保存路径
    * @param dataRootPath 数据根目录
    * @param listener     回调
    */
    private ChainedTrainer(Trainer trainer, TrainArgument argument,
                           String modelPath, String savePath,
                           String dataRootPath, TrainListener listener) {
        this.trainer = trainer == null ? new ResNet50TransferTrainer() : trainer;
 // 复制默认配置，使用 mutable 副本避免污染静态 默认
        this.argument = argument == null ? TrainArgument.defaults() : copy(argument);
        this.modelPath = modelPath;
        this.savePath = savePath;
        this.dataRootPath = dataRootPath;
        this.listener = listener == null ? TrainListener.NOOP : listener;
    }

    /**
    * 从默认迁移学习训练器构建一条训练管道。
    *
    * @return 训练管道
    */
    public static ChainedTrainer of() {
        return new ChainedTrainer(new ResNet50TransferTrainer(),
                null, null, null, null, null);
    }

    /**
    * 从指定训练器构建一条训练管道（便于替换模型架构实现）。
    *
    * @param trainer 底层训练器
    * @return 训练管道
    */
    public static ChainedTrainer of(Trainer trainer) {
        return new ChainedTrainer(trainer, null, null, null, null, null);
    }

    /**
    * 直接构造一条管道（完全自定义）。
    *
    * @param trainer      底层训练器
    * @param argument     超参数
    * @param modelPath    预训练模型路径
    * @param savePath     保存路径
    * @param dataRootPath 数据根目录
    */
    public static ChainedTrainer of(Trainer trainer, TrainArgument argument,
                                    String modelPath, String savePath, String dataRootPath) {
        return new ChainedTrainer(trainer, argument, modelPath, savePath, dataRootPath, null);
    }

    /**
    * 分支：从当前管道派生出新管道（可继续修改超参数而不影响源管道）。
    *
    * @return 新管道
    */
    public ChainedTrainer branch() {
        return new ChainedTrainer(trainer, argument, modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 指定训练数据根目录（子目录即类别名）。
    *
    * @param dataRootPath 数据根目录
    * @return 新管道
    */
    public ChainedTrainer data(String dataRootPath) {
        return new ChainedTrainer(trainer, argument, modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 指定模型保存路径。
    *
    * @param savePath 保存路径
    * @return 新管道
    */
    public ChainedTrainer saveTo(String savePath) {
        return new ChainedTrainer(trainer, argument, modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 指定续训练用模型路径（从已有模型继续微调）。等价 {@code resumeModel}.
    *
    * @param resumePath 续训练模型路径
    * @return 新管道
    */
    public ChainedTrainer resume(String resumePath) {
        return withArgument(a -> a.setResumeModelPath(resumePath));
    }

    /**
    * 指定预训练模型路径（不指定则用默认预训练权重）。
    *
    * @param modelPath 预训练模型路径
    * @return 新管道
    */
    public ChainedTrainer model(String modelPath) {
        return new ChainedTrainer(trainer, argument, modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 整体覆盖训练超参数。
    *
    * @param argument 超参数
    * @return 新管道
    */
    public ChainedTrainer argument(TrainArgument argument) {
        return new ChainedTrainer(trainer, argument == null ? TrainArgument.defaults() : copy(argument),
                modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 设置迭代周期数。
    *
    * @param epochs 迭代周期
    * @return 新管道
    */
    public ChainedTrainer epochs(int epochs) {
        return withArgument(a -> a.setEpoch(epochs));
    }

    /**
    * 设置训练批次大小。
    *
    * @param batchSize 批次大小
    * @return 新管道
    */
    public ChainedTrainer batchSize(int batchSize) {
        return withArgument(a -> a.setBatchSize(batchSize));
    }

    /**
    * 设置分类数量（迁移学习输出层维度）。
    *
    * @param nClasses 分类数
    * @return 新管道
    */
    public ChainedTrainer classifier(int nClasses) {
        return withArgument(a -> a.setNClasses(nClasses));
    }

    /**
    * 设置学习率。
    *
    * @param learningRate 学习率
    * @return 新管道
    */
    public ChainedTrainer learningRate(double learningRate) {
        return withArgument(a -> a.setLearningRate(learningRate));
    }

    /**
    * 设置动量。
    *
    * @param momentum 动量
    * @return 新管道
    */
    public ChainedTrainer momentum(double momentum) {
        return withArgument(a -> a.setLrMomentum(momentum));
    }

    /**
    * 设置训练集占比（0~100，其余作为测试集）。
    *
    * @param percent 训练集占比
    * @return 新管道
    */
    public ChainedTrainer trainPercent(int percent) {
        return withArgument(a -> a.setTrainPercent(percent));
    }

    /**
    * 设置进度回调监听器。
    *
    * @param listener 监听器
    * @return 新管道
    */
    public ChainedTrainer listener(TrainListener listener) {
        return new ChainedTrainer(trainer, argument, modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 终端操作：以当前配置执行一次训练。
    *
    * @return 训练结果
    * @throws IllegalStateException 数据根目录或保存路径缺失
    * @throws Exception             训练失败
    */
    public TrainResult fit() throws Exception {
        if (dataRootPath == null || dataRootPath.isBlank()) {
            throw new IllegalStateException("请先通过 .data(path) 指定训练数据根目录");
        }
        if (savePath == null || savePath.isBlank()) {
            throw new IllegalStateException("请先通过 .saveTo(path) 指定模型保存路径");
        }
        return trainer.train(argument, modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 终端操作：以当前配置执行训练，并把底层异常包装为运行时异常（方便回调/线程中调用）。
    *
    * @return 训练结果
    */
    public TrainResult fitUnchecked() {
        try {
            return fit();
        } catch (Exception e) {
            throw new TrainException("训练失败: " + e.getMessage(), e);
        }
    }

    /**
    * 对参数副本执行的链式命令（同一终端调用内部复用）。
    * @author CH
    * @since 4.0.0
    */
    @FunctionalInterface
    private interface ArgumentConsumer {
        void apply(TrainArgument copy);
    }

    /**
    * 修改参数副本并返回新管道。
    *
    * @param consumer 修改器
    * @return 新管道
    */
    private ChainedTrainer withArgument(ArgumentConsumer consumer) {
        TrainArgument copy = copy(argument);
        consumer.apply(copy);
        return new ChainedTrainer(trainer, copy, modelPath, savePath, dataRootPath, listener);
    }

    /**
    * 深拷贝一个超参数配置。
    *
    * @param source 源配置
    * @return 副本
    */
    private static TrainArgument copy(TrainArgument source) {
        TrainArgument copy = new TrainArgument();
        copy.setEpoch(source.getEpoch());
        copy.setBatchSize(source.getBatchSize());
        copy.setNClasses(source.getNClasses());
        copy.setClassLabels(source.getClassLabels());
        copy.setDetLabels(source.getDetLabels());
        copy.setResumeModelPath(source.getResumeModelPath());
        copy.setLearningRate(source.getLearningRate());
        copy.setLrMomentum(source.getLrMomentum());
        copy.setTrainPercent(source.getTrainPercent());
        return copy;
    }

    /**
    * 可读的属性访问器（内部使用，便于调试/打印）。
    *
    * @return 当前数据根目录
    */
    public String dataRoot() {
        return dataRootPath;
    }

    /**
    * @return 底层训练器
    */
    public Trainer trainer() {
        return trainer;
    }

    /**
    * @return 当前超参数副本
    */
    public TrainArgument arguments() {
        return copy(argument);
    }
}
