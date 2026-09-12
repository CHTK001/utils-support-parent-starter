package com.chua.deeplearning.support.dl4j.train;

import org.datavec.api.io.filters.BalancedPathFilter;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.split.FileSplit;
import org.datavec.api.split.InputSplit;
import org.datavec.image.loader.BaseImageLoader;
import org.datavec.image.loader.NativeImageLoader;
import org.datavec.image.recordreader.ImageRecordReader;
import org.datavec.image.transform.FlipImageTransform;
import org.datavec.image.transform.ImageTransform;
import org.datavec.image.transform.PipelineImageTransform;
import org.datavec.image.transform.WarpImageTransform;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.transferlearning.FineTuneConfiguration;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.deeplearning4j.zoo.ZooModel;
import org.deeplearning4j.zoo.model.ResNet50;
import org.nd4j.common.primitives.Pair;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.api.preprocessor.VGG16ImagePreProcessor;
import org.nd4j.linalg.learning.config.Nesterovs;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
* DL4J Rnet50 迁移学习模型：数据加载 + 模型构建。
*
* <p>移植自 AIAS 2_training_platform 的 {@code ResNet50Model}，核心流程：</p>
* <ol>
*   <li>加载训练/测试数据（datavec ImageRecordReader + 数据增强）</li>
*   <li>构建 FineTuneConfiguration（优化器、学习率、动量、L2 正则）</li>
*   <li>从预训练 ResNet50 zoo 模型（或本地 zip）加载后替换 fc1000 输出层为新分类数</li>
* </ol>
*
* @author CH
* @since 4.0.0.42
 */
public class ResNet50Model {

    /**
    * 训练数据标签数（分类数）。
     */
    protected int nClasses = 3;

    /**
    * 标签：模型输出分类。
     */
    protected List<String> labels;

    /**
    * 小批量大小。
     */
    protected Integer batchSize = 10;

    /**
    * 图片宽度。
     */
    protected int width = 224;

    /**
    * 图片高度。
     */
    protected int height = 224;

    /**
    * 图片通道数。
     */
    protected int nChannels = 3;

    /**
    * 学习率。
     */
    protected Double learningRate = 1e-3;

    /**
    * 动量。
     */
    protected double lrMomentum = 0.9;

    /**
    * 随机数生成器。
     */
    protected Random rng = new Random(13);

    /**
    * 训练迭代器。
     */
    protected RecordReaderDataSetIterator trainIter;

    /**
    * 测试迭代器。
     */
    protected RecordReaderDataSetIterator testIter;

    /**
    * 计算图（训练后保持引用，供推理使用）。
     */
    protected ComputationGraph computationGraph;

    /**
    * 父路径标签生成器（目录结构即标签）。
     */
    protected ParentPathLabelGenerator labelMaker = new ParentPathLabelGenerator();

    /**
    * 设置批量大小。
    *
    * @param batchSize 批次大小
     */
    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    /**
    * 设置分类数量。
    *
    * @param nClasses 分类数量
     */
    public void setNClasses(int nClasses) {
        this.nClasses = nClasses;
    }

    /**
    * 获取类别标签列表。
    *
    * @return 标签列表
     */
    public List<String> getLabels() {
        return labels;
    }

    /**
    * 设置类别标签列表。
    *
    * @param labels 标签列表
     */
    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    /**
    * 获取图片宽度。
    *
    * @return 图片宽度
     */
    public int getWidth() {
        return width;
    }

    /**
    * 获取图片高度。
    *
    * @return 图片高度
     */
    public int getHeight() {
        return height;
    }

    /**
    * 获取图片通道数。
    *
    * @return 图片通道数
     */
    public int getNChannels() {
        return nChannels;
    }

    /**
    * 获取训练迭代器。
    *
    * @return 训练数据迭代器
     */
    public RecordReaderDataSetIterator getTrainIter() {
        return trainIter;
    }

    /**
    * 获取测试迭代器。
    *
    * @return 测试数据迭代器
     */
    public RecordReaderDataSetIterator getTestIter() {
        return testIter;
    }

    /**
    * 获取构建好的计算图。
    *
    * @return ComputationGraph
     */
    public ComputationGraph getComputationGraph() {
        return computationGraph;
    }

    /**
    * 设置学习率（迁移学习 罚金-tune 用，默认 1e-3）。
    *
    * @param learningRate 学习率
     */
    public void setLearningRate(Double learningRate) {
        if (learningRate != null && learningRate > 0) {
            this.learningRate = learningRate;
        }
    }

    /**
    * 设置动量（Nesterov momentum，默认 0.9）。
    *
    * @param lrMomentum 动量
     */
    public void setLrMomentum(Double lrMomentum) {
        if (lrMomentum != null && lrMomentum >= 0) {
            this.lrMomentum = lrMomentum;
        }
    }

    /**
    * 从本地 压缩 文件加载模型。
    *
    * @param model 模型文件
    * @return 加载后的计算图
    * @throws IOException 文件读取失败
     */
    public ComputationGraph loadModel(File model) throws IOException {
        computationGraph = ComputationGraph.load(model, true);
        return computationGraph;
    }

    /**
    * 加载数据并自动划分训练/测试集（按比例）。
    *
    * @param parentDir  数据根目录（子目录为类别）
    * @param trainPerc  训练集占比（0~99）
    * @throws IOException 数据读取失败
     */
    public void loadData(File parentDir, int trainPerc) throws IOException {
        FileSplit filesInDir = new FileSplit(parentDir, BaseImageLoader.ALLOWED_FORMATS, rng);
        BalancedPathFilter pathFilter = new BalancedPathFilter(rng, BaseImageLoader.ALLOWED_FORMATS, labelMaker);
        if (trainPerc >= 100) {
            throw new IllegalArgumentException("训练百分比应该小于 100%.");
        }
        InputSplit[] filesInDirSplit = filesInDir.sample(pathFilter, trainPerc, 100 - trainPerc);
        InputSplit trainData = filesInDirSplit[0];
        InputSplit testData = filesInDirSplit[1];

        buildDataIterators(trainData, testData);
    }

    /**
    * 加载数据（已划分训练/测试目录）。
    *
    * @param trainDir 训练数据目录
    * @param testDir  测试数据目录
    * @throws IOException 数据读取失败
     */
    public void loadData(File trainDir, File testDir) throws IOException {
        FileSplit trainData = new FileSplit(trainDir, NativeImageLoader.ALLOWED_FORMATS, rng);
        FileSplit testData = new FileSplit(testDir, NativeImageLoader.ALLOWED_FORMATS, rng);

        buildDataIterators(trainData, testData);
    }

    /**
    * 构建数据迭代器（含数据增强）。
    *
    * @param trainData 训练数据分片
    * @param testData  测试数据分片
    * @throws IOException 数据读取失败
     */
    private void buildDataIterators(InputSplit trainData, InputSplit testData) throws IOException {
        // 数据增强管线
        boolean shuffle = false;
        ImageTransform flipTransform1 = new FlipImageTransform(rng);
        ImageTransform flipTransform2 = new FlipImageTransform(new Random(123));
        ImageTransform warpTransform = new WarpImageTransform(rng, 42);
        List<Pair<ImageTransform, Double>> pipeline = Arrays.asList(
                new Pair<>(flipTransform1, 0.9),
                new Pair<>(flipTransform2, 0.8),
                new Pair<>(warpTransform, 0.5));
        ImageTransform transform = new PipelineImageTransform(pipeline, shuffle);

        // 训练数据
        ImageRecordReader recordReaderTrain = new ImageRecordReader(height, width, nChannels, labelMaker);
        recordReaderTrain.initialize(trainData, transform);
        trainIter = new RecordReaderDataSetIterator(recordReaderTrain, batchSize, 1, nClasses);
        trainIter.setPreProcessor(new VGG16ImagePreProcessor());

        // 测试数据
        ImageRecordReader recordReaderTest = new ImageRecordReader(height, width, nChannels, labelMaker);
        recordReaderTest.initialize(testData);
        testIter = new RecordReaderDataSetIterator(recordReaderTest, 1, 1, nClasses);
        testIter.setPreProcessor(new VGG16ImagePreProcessor());

        labels = trainIter.getLabels();
    }

    /**
    * 构建迁移学习模型（从 zoo 在线下载预训练权重）。
    *
    * @return 构建好的计算图
    * @throws IOException zoo 模型下载失败
     */
    public ComputationGraph build() throws IOException {
        ZooModel zooModel = ResNet50.builder().build();
        ComputationGraph pretrained = (ComputationGraph) zooModel.initPretrained();
        return buildTransferModel(pretrained);
    }

    /**
    * 构建迁移学习模型（从本地 压缩 加载预训练权重）。
    *
    * @param model 本地预训练模型文件
    * @return 构建好的计算图
    * @throws IOException 文件读取失败
     */
    public ComputationGraph build(File model) throws IOException {
        ComputationGraph pretrained = loadModel(model);
        return buildTransferModel(pretrained);
    }

    /**
    * 构建 罚金tune配置。
    *
    * @return FineTuneConfiguration
     */
    private FineTuneConfiguration getFineTuneConfiguration() {
        return new FineTuneConfiguration.Builder()
                .seed(rng.nextInt())
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .gradientNormalization(GradientNormalization.RenormalizeL2PerLayer)
                .gradientNormalizationThreshold(1.0)
                .updater(new Nesterovs.Builder()
                        .learningRate(learningRate)
                        .momentum(lrMomentum)
                        .build())
                .l2(0.00001)
                .activation(Activation.IDENTITY)
                .trainingWorkspaceMode(WorkspaceMode.ENABLED)
                .inferenceWorkspaceMode(WorkspaceMode.ENABLED)
                .build();
    }

    /**
    * 构建迁移学习计算图：替换 函数计算1000 输出层为新分类数。
    *
    * @param pretrained 预训练计算图
    * @return 迁移学习后的计算图
     */
    private ComputationGraph buildTransferModel(ComputationGraph pretrained) {
        FineTuneConfiguration fineTuneConf = getFineTuneConfiguration();
        computationGraph = new TransferLearning.GraphBuilder(pretrained)
                .fineTuneConfiguration(fineTuneConf)
                .removeVertexAndConnections("fc1000")
                .addLayer("fc1000", new OutputLayer.Builder()
                        .nIn(2048)
                        .nOut(nClasses)
                        .activation(Activation.SOFTMAX)
                        .lossFunction(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                        .build(), "flatten_1")
                .setOutputs("fc1000")
                .build();
        return computationGraph;
    }
}