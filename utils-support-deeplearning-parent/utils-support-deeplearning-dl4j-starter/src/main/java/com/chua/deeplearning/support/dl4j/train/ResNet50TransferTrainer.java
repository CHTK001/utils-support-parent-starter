package com.chua.deeplearning.support.dl4j.train;

import com.chua.deeplearning.support.dl4j.TrainArgument;
import com.chua.deeplearning.support.dl4j.TrainListener;
import com.chua.deeplearning.support.dl4j.TrainProgress;
import com.chua.deeplearning.support.dl4j.TrainResult;
import com.chua.deeplearning.support.dl4j.TrainStatus;
import com.chua.deeplearning.support.dl4j.Trainer;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.evaluation.classification.Evaluation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
* Rnet50 迁移学习训练器。
*
* <p>移植自 AIAS 2_training_platform 的 {@code TrainResNet50}，核心流程：</p>
* <ol>
*   <li>从本地 zip 加载预训练 ResNet50 模型</li>
*   <li>替换 fc1000 输出层为新的分类数</li>
*   <li>按 epoch 循环训练，每轮在测试集上评估 F1 值</li>
*   <li>保存最优 F1 对应的模型文件</li>
* </ol>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class ResNet50TransferTrainer implements Trainer {

    /**
    * 默认保存模型文件名（保存路径 为目录时使用）。
     */
    private static final String DEFAULT_MODEL_NAME = "NewResNet50.zip";

    @Override
    public TrainResult train(TrainArgument argument,
                             String modelPath,
                             String savePath,
                             String dataRootPath,
                             TrainListener listener) throws Exception {
        long startMs = System.currentTimeMillis();
        TrainProgress progress = new TrainProgress()
                .setStatus(TrainStatus.RUNNING)
                .setTotalEpochs(argument.getEpoch());

        // --- 阶段 1：加载数据 ---
        progress.setStage("加载数据");
        progress.setProgress(5);
        if (listener != null) {
            listener.onStarted(progress);
        }

        ResNet50Model model = new ResNet50Model();
        model.setBatchSize(argument.getBatchSize());
        model.setNClasses(argument.getNClasses());
        // 迁移学习超参（微调学习率/动量），未显式传入时用模型默认值
        model.setLearningRate(argument.getLearningRate());
        model.setLrMomentum(argument.getLrMomentum());

        File dataDir = new File(dataRootPath);
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            throw new IllegalArgumentException("数据目录不存在: " + dataRootPath);
        }

        int trainPercent = argument.getTrainPercent() != null ? argument.getTrainPercent() : 70;
        model.loadData(dataDir, trainPercent);

        // 打印标签
        List<String> labelsList = model.getLabels();
        String labels = Arrays.toString(labelsList.toArray());
        log.info("[DL4J] 类别标签: {}", labels);
        progress.setClassLabels(labels);

        RecordReaderDataSetIterator trainIter = model.getTrainIter();
        RecordReaderDataSetIterator testIter = model.getTestIter();

        // 实际使用的基模型：续训模型优先，否则默认预训练模型
        String baseModel = argument.getResumeModelPath() != null && !argument.getResumeModelPath().isBlank()
                ? argument.getResumeModelPath()
                : modelPath;
        if (argument.getResumeModelPath() != null && !argument.getResumeModelPath().isBlank()) {
            log.info("[DL4J] 从已有模型继续微调: {}", baseModel);
        } else {
            log.info("[DL4J] 使用默认预训练模型: {}", baseModel);
        }
        progress.setBaseModelPath(baseModel);

        // --- 阶段 2：构建模型 ---
        progress.setStage("构建模型");
        progress.setProgress(15);
        log.info("[DL4J] 构建迁移学习模型...");
        ComputationGraph computationGraph = model.build(new File(baseModel));
        log.info(computationGraph.summary());

        double bestScore = 0.0;
        int nEpochs = argument.getEpoch();
        File modelFile = resolveModelFile(savePath);

        // --- 阶段 3：训练 ---
        progress.setStage("训练中");
        for (int i = 0; i < nEpochs; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return cancelled(progress, listener);
            }

            progress.setCurrentEpoch(i + 1);
            progress.setStage("训练中 - epoch " + (i + 1) + "/" + nEpochs);

            // 训练
            trainIter.reset();
            while (trainIter.hasNext()) {
                computationGraph.fit(trainIter.next());
            }
            log.info("[DL4J] epoch {} 完成", i + 1);

            // 评估
            Evaluation eval = new Evaluation(argument.getNClasses());
            testIter.reset();
            while (testIter.hasNext()) {
                DataSet t = testIter.next();
                INDArray evalFeatures = t.getFeatures();
                INDArray evalLabels = t.getLabels();
                INDArray[] predicted = computationGraph.output(false, evalFeatures);
                eval.eval(evalLabels, predicted[0]);
            }
            double tempScore = eval.f1();
            log.info("[DL4J] epoch {} | F1: {} | 当前最优: {}", i + 1, tempScore, bestScore);

 // 保存最优模型（先更新 bestscore，再上报进度）
            if (tempScore > bestScore) {
                bestScore = tempScore;
                log.info("[DL4J] 保存模型，F1: {}", bestScore);
                ModelSerializer.writeModel(computationGraph, modelFile, true);
                progress.setModelPath(modelFile.getAbsolutePath());
            }

            progress.setBestScore(bestScore);
            progress.setProgress(15.0 + (70.0 * (i + 1) / nEpochs));

            if (listener != null) {
                listener.onEpochCompleted(progress);
            }
        }

        // --- 完成 ---
        long elapsed = System.currentTimeMillis() - startMs;
        progress.setStatus(TrainStatus.SUCCESS)
                .setStage("训练完成")
                .setProgress(100)
                .setBestScore(bestScore);

        if (listener != null) {
            listener.onFinished(progress);
        }

        return new TrainResult()
                .setSuccess(true)
                .setModelPath(progress.getModelPath())
                .setLabels(labelsList)
                .setBestScore(bestScore)
                .setElapsedMs(elapsed);
    }

    /**
    * 解析模型保存文件路径：若配置为目录则拼接默认文件名，否则原样使用。
    *
    * @param savePath 配置的保存路径（文件或目录）
    * @return 保存用模型文件
     */
    private File resolveModelFile(String savePath) {
        File target = new File(savePath);
        if (target.isDirectory() || savePath.endsWith(File.separator)) {
            File dir = new File(savePath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return new File(dir, DEFAULT_MODEL_NAME);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return target;
    }

    /**
    * 构造训练被取消的结果并通知监听器。
    *
    * @param progress 进度对象
    * @param listener 监听器（可为空）
    * @return 失败结果
     */
    private TrainResult cancelled(TrainProgress progress, TrainListener listener) {
        progress.setStatus(TrainStatus.CANCELLED);
        if (listener != null) {
            listener.onFailed(progress, new InterruptedException("训练被取消"));
        }
        return new TrainResult()
                .setSuccess(false)
                .setErrorMessage("训练被取消");
    }
}