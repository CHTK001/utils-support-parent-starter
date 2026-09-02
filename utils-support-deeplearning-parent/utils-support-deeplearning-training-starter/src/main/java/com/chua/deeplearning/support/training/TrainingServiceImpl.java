package com.chua.deeplearning.support.training;

import com.chua.deeplearning.support.training.infer.ImageClassification;
import com.chua.deeplearning.support.training.infer.FeatureExtraction;
import com.chua.deeplearning.support.training.infer.ImageComparison;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 训练服务默认实现。
 *
 * <p>基于 DJL PyTorch 引擎，通过 ResNet50 迁移学习训练图像分类模型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TrainingServiceImpl implements TrainingService {

    private final ImageClassification classificationInfer;
    private final FeatureExtraction featureInfer;
    private final ImageComparison comparisonInfer;

    /**
     * 构造训练服务实例。
     */
    public TrainingServiceImpl() {
        this.classificationInfer = new ImageClassification();
        this.featureInfer = new FeatureExtraction();
        this.comparisonInfer = new ImageComparison();
    }

    @Override
    public TrainingResult train(TrainingArgument argument, TrainingListener listener) throws Exception {
        log.info("开始训练 - 分类数={}, 轮数={}, 批量大小={}, 学习率={}",
                argument.getNClasses(), argument.getEpoch(),
                argument.getBatchSize(), argument.getLearningRate());

        ImageClassificationTrainer trainer = new ImageClassificationTrainer();
        TrainingResult result = trainer.train(argument, listener);

        if (result.isSuccess()) {
            log.info("训练完成 - 模型路径={}, 最佳准确率={}, 耗时={}ms",
                    result.getModelPath(), result.getBestAccuracy(),
                    result.getTotalTrainingTimeMs());
        } else {
            log.error("训练失败: {}", result.getErrorMessage());
        }

        return result;
    }

    @Override
    public String classify(String modelPath, byte[] imageData, List<String> labels) throws Exception {
        return classificationInfer.classify(modelPath, imageData, labels);
    }

    @Override
    public float[] extractFeature(String modelPath, byte[] imageData) throws Exception {
        return featureInfer.extract(modelPath, imageData);
    }

    @Override
    public float compare(String modelPath, byte[] imageData1, byte[] imageData2, List<String> labels) throws Exception {
        return comparisonInfer.compare(modelPath, imageData1, imageData2);
    }

    @Override
    public ImageClassification imageClassification() {
        return classificationInfer;
    }

    @Override
    public FeatureExtraction featureExtraction() {
        return featureInfer;
    }

    @Override
    public ImageComparison imageComparison() {
        return comparisonInfer;
    }
}
