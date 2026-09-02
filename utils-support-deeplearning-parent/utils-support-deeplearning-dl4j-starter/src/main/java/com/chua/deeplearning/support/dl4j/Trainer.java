package com.chua.deeplearning.support.dl4j;

import java.io.File;

/**
 * 模型训练器接口。
 *
 * <p>不同模型架构（ResNet50 / Vgg16 等）各自实现此接口，
 * 由 {@link com.chua.deeplearning.support.dl4j.TrainerFactory} 或 Spring 注入选择。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Trainer {

    /**
     * 执行训练。
     *
     * @param argument     训练超参数
     * @param modelPath    预训练模型路径（本地 zip 或目录）
     * @param savePath     模型保存路径（目录或完整文件路径）
     * @param dataRootPath 训练数据根目录（子目录为类别）
     * @param listener     进度回调（可为 {@link TrainListener#NOOP}）
     * @return 训练结果
     * @throws Exception 训练过程中发生异常
     */
    TrainResult train(TrainArgument argument,
                      String modelPath,
                      String savePath,
                      String dataRootPath,
                      TrainListener listener) throws Exception;
}