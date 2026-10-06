package com.chua.deeplearning.support.tinyai;

import io.leavesfly.tinyai.func.Variable;
import io.leavesfly.tinyai.ml.dataset.ArrayDataset;
import io.leavesfly.tinyai.ml.dataset.Batch;
import io.leavesfly.tinyai.ml.dataset.DataSet;
import io.leavesfly.tinyai.ml.loss.Classify;
import io.leavesfly.tinyai.ml.loss.SoftmaxCrossEntropy;
import io.leavesfly.tinyai.ml.model.Model;
import io.leavesfly.tinyai.ml.optimize.Adam;
import io.leavesfly.tinyai.ndarr.NdArray;
import io.leavesfly.tinyai.ndarr.Shape;
import io.leavesfly.tinyai.nnet.container.Sequential;
import io.leavesfly.tinyai.nnet.layer.activation.ReLU;
import io.leavesfly.tinyai.nnet.layer.dnn.Linear;
import io.leavesfly.tinyai.util.Config;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TinyAI 神经网络分类引擎。
 *
 * <p>职责：在「业务行数据」与 TinyAI 张量世界之间搭桥——
 * 按 {@link ModelDomain} 把行编码为特征向量 / 标签下标，组装 MLP，
 * 手写训练循环（逐 batch 前向 -> 损失 -> 反向 -> Adam 更新），
 * 推理时前向 + softmax 取 argmax。</p>
 *
 * <p>不用 TinyAI 自带 {@code Trainer} 的原因：其 {@code train()} 收尾调
 * {@code Monitor.plot()} 弹 Swing 绘图窗口，CI / 服务端会卡住；
 * 手写循环只依赖前向 / 损失 / 反向 / 优化器四个稳定 API。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TinyAiEngine implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分类评估器（算准确率用，无状态，惰性重建以兼容反序列化）。
     */
    private transient Classify classify;

    /**
     * 取分类评估器（惰性初始化）。
     *
     * @return 评估器
     */
    private Classify classify() {
        if (classify == null) {
            classify = new Classify();
        }
        return classify;
    }

    /**
     * 训练 TinyAI MLP 模型。
     *
     * <p>标签编码：按域标签表下标编码为标量（TinyAI 分类损失按「预测 logits + 标量标签」
     * 约定消费）；输入向量按域编码。</p>
     *
     * @param domain  数据域，不能为 null
     * @param samples 样本行，不能为 null
     * @param options 训练参数，null 用默认值
     * @return 训练好的 TinyAI 模型
     */
    public Model train(ModelDomain domain, List<Map<String, Object>> samples, MlpOptions options) {
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(samples, "samples must not be null");
        var opts = options == null ? MlpOptions.defaults() : options;
        var dataset = toDataset(domain, samples, opts.batchSize());
        dataset.prepare();
        var model = buildModel(domain, opts);
        var loss = new SoftmaxCrossEntropy();
        var optimizer = new Adam(model, opts.learningRate(), 0.9F, 0.999F, 1e-8F);
        Config.train = true;
        model.getModule().train();
        var trainSet = dataset.getTrainDataSet() == null ? dataset : dataset.getTrainDataSet();
        for (var epoch = 0; epoch < opts.epochs(); epoch++) {
            trainSet.shuffle();
            for (var batch : trainSet.getBatches()) {
                var x = batch.toVariableX().setName("x").setRequireGrad(false);
                var y = batch.toVariableY().setName("y").setRequireGrad(false);
                var predict = model.forward(x);
                var lossValue = loss.loss(y, predict);
                lossValue.setName("loss");
                model.clearGrads();
                lossValue.backward();
                optimizer.update();
                lossValue.unChainBackward();
                model.tmpPredict = predict;
            }
        }
        model.getModule().eval();
        Config.train = false;
        return model;
    }

    /**
     * 增量训练（微调）：在已有模型权重上继续训练。
     *
     * <p>复用训练的同一套循环与 Adam 更新；新建 Adam 优化器（动量按当前参数
     * 身份重建，等价于延续训练）。新样本标签须落在原数据域标签表内，
     * 出现未见标签时抛错（输出层维度固定，无法容纳新类）。
     * 典型用于“上线后拿到新标注数据，追加训练几轮”。</p>
     *
     * @param model 已训练的 TinyAI 模型，不能为 null
     * @param domain 原数据域（特征口径与标签表须与模型一致），不能为 null
     * @param newSamples 新增样本行，不能为 null
     * @param options 微调参数（epochs 即追加轮数），null 用默认值
     */
    public void finetune(Model model, ModelDomain domain, List<Map<String, Object>> newSamples,
            MlpOptions options) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(newSamples, "newSamples must not be null");
        var opts = options == null ? MlpOptions.defaults() : options;
        var dataset = toDataset(domain, newSamples, opts.batchSize());
        dataset.prepare();
        var loss = new SoftmaxCrossEntropy();
        var optimizer = new Adam(model, opts.learningRate(), 0.9F, 0.999F, 1e-8F);
        Config.train = true;
        model.getModule().train();
        var trainSet = dataset.getTrainDataSet() == null ? dataset : dataset.getTrainDataSet();
        for (var epoch = 0; epoch < opts.epochs(); epoch++) {
            trainSet.shuffle();
            for (var batch : trainSet.getBatches()) {
                var x = batch.toVariableX().setName("x").setRequireGrad(false);
                var y = batch.toVariableY().setName("y").setRequireGrad(false);
                var predict = model.forward(x);
                var lossValue = loss.loss(y, predict);
                lossValue.setName("loss");
                model.clearGrads();
                lossValue.backward();
                optimizer.update();
                lossValue.unChainBackward();
                model.tmpPredict = predict;
            }
        }
        model.getModule().eval();
        Config.train = false;
    }

    /**
     * 单行推理：前向 + softmax，取 argmax 与概率分布。
     *
     * @param model  训练好的模型，不能为 null
     * @param domain 数据域，不能为 null
     * @param row    数据行，不能为 null
     * @return 概率向量（顺序与域标签表一致）
     */
    public float[] infer(Model model, ModelDomain domain, Map<String, Object> row) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(row, "row must not be null");
        var wasTrain = Config.train;
        Config.train = false;
        try {
            var vector = domain.encode(row);
            var input = new Variable(NdArray.of(vector, Shape.of(1, vector.length)));
            input.setRequireGrad(false);
            var logits = model.forward(input).getValue().softMax().getMatrix()[0];
            var out = new float[logits.length];
            System.arraycopy(logits, 0, out, 0, logits.length);
            return out;
        } finally {
            Config.train = wasTrain;
        }
    }

    /**
     * 在给定样本上算准确率（0~1）。
     *
     * @param model   训练好的模型，不能为 null
     * @param domain  数据域，不能为 null
     * @param samples 样本行，不能为 null
     * @return 准确率
     */
    public float accuracy(Model model, ModelDomain domain, List<Map<String, Object>> samples) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.isEmpty()) {
            return 0.0F;
        }
        var wasTrain = Config.train;
        Config.train = false;
        try {
            var hit = 0;
            for (var row : samples) {
                var probs = infer(model, domain, row);
                var best = argMax(probs);
                var expected = row == null ? null : row.get(domain.targetName());
                if (expected != null && domain.labels().get(best).equals(String.valueOf(expected))) {
                    hit++;
                }
            }
            return (float) hit / samples.size();
        } finally {
            Config.train = wasTrain;
        }
    }

    /**
     * 用 TinyAI 自带 {@link Classify#accuracyRate} 在数据集上算准确率（供评估器口径对齐）。
     *
     * @param model   训练好的模型，不能为 null
     * @param dataset 数据集，不能为 null
     * @return 准确率
     */
    public float accuracy(Model model, DataSet dataset) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(dataset, "dataset must not be null");
        var wasTrain = Config.train;
        Config.train = false;
        try {
            var batches = dataset.getBatches();
            if (batches.isEmpty()) {
                return 0.0F;
            }
            var sum = 0.0F;
            for (var batch : batches) {
                var x = batch.toVariableX().setName("x").setRequireGrad(false);
                var y = batch.toVariableY().setName("y").setRequireGrad(false);
                sum += classify().accuracyRate(y, model.forward(x));
            }
            return sum / batches.size();
        } finally {
            Config.train = wasTrain;
        }
    }

    /**
     * 组装 MLP：输入层 -> 隐藏层（ReLU） -> 输出层（类别数）。
     *
     * @param domain 数据域
     * @param options 训练参数
     * @return TinyAI 模型
     */
    public Model buildModel(ModelDomain domain, MlpOptions options) {
        var input = domain.inputSize();
        var output = domain.labels().size();
        var net = new Sequential("tinyai-mlp");
        var prev = input;
        for (var i = 0; i < options.hiddenLayers().length; i++) {
            var width = options.hiddenLayers()[i];
            net.add(new Linear("fc" + i, prev, width));
            net.add(new ReLU("relu" + i));
            prev = width;
        }
        net.add(new Linear("fc-out", prev, output));
        return new Model("tinyai-mlp", net);
    }

    /**
     * 把业务样本行转成 TinyAI 数据集。
     *
     * <p>输入按域编码为 {@code (1, inputSize)} 张量；标签按域下标编码为标量。
     * ArrayDataset 按 batchSize 整除切批（余数丢弃），故 batchSize 超过样本数时钳到样本数，
     * 保证至少一个 batch。切分 8/1/1 供训练 / 测试 / 验证复用。</p>
     *
     * @param domain 数据域
     * @param samples 样本行
     * @param batchSize 批大小
     * @return 数据集
     */
    public DataSet toDataset(ModelDomain domain, List<Map<String, Object>> samples, int batchSize) {
        var xs = new ArrayList<NdArray>(samples.size());
        var ys = new ArrayList<NdArray>(samples.size());
        for (var row : samples) {
            if (row == null) {
                continue;
            }
            var label = row.get(domain.targetName());
            if (label == null) {
                throw new TinyAiException("样本行标签列 " + domain.targetName() + " 缺失");
            }
            var index = domain.labelIndex(String.valueOf(label));
            if (index < 0) {
                throw new TinyAiException("样本标签未在数据域中: " + label);
            }
            var vector = domain.encode(row);
            xs.add(NdArray.of(vector, Shape.of(1, vector.length)));
            ys.add(NdArray.of((float) index));
        }
        if (xs.isEmpty()) {
            throw new TinyAiException("样本行数不足: 无有效样本");
        }
        var batch = Math.max(1, Math.min(batchSize, xs.size()));
        return new TabularDataset(batch, xs.toArray(new NdArray[0]), ys.toArray(new NdArray[0]));
    }

    /**
     * 取 argmax 下标。
     */
    private static int argMax(float[] probs) {
        var best = 0;
        for (var i = 1; i < probs.length; i++) {
            if (probs[i] > probs[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 表格数据集：TinyAI ArrayDataset 的精简实现。
     *
     * <p>与上游示例 {@code SimpleArrayDataset} 同构：doPrepare 做 8/1/1 切分，
     * 余数 batch 保留（不用上游按整除丢尾的 getBatches，保证小样本可用）。</p>
     */
    static final class TabularDataset extends ArrayDataset {

        /**
         * 构造数据集。
         *
         * @param batchSize 批大小
         * @param xs 输入张量
         * @param ys 标签张量
         */
        TabularDataset(int batchSize, NdArray[] xs, NdArray[] ys) {
            super(batchSize);
            this.xs = xs;
            this.ys = ys;
        }

        @Override
        public void doPrepare() {
            splitDataset(0.8F, 0.1F, 0.1F);
        }

        @Override
        public List<Batch> getBatches() {
            var batches = new ArrayList<Batch>();
            var order = new ArrayList<Integer>(xs.length);
            for (var i = 0; i < xs.length; i++) {
                order.add(i);
            }
            Collections.shuffle(order);
            for (var start = 0; start < order.size(); start += batchSize) {
                var end = Math.min(start + batchSize, order.size());
                var bx = new NdArray[end - start];
                var by = new NdArray[end - start];
                for (var i = start; i < end; i++) {
                    bx[i - start] = xs[order.get(i)];
                    by[i - start] = ys[order.get(i)];
                }
                batches.add(new Batch(bx, by));
            }
            return batches;
        }

        @Override
        protected DataSet build(int batchSize, NdArray[] xs, NdArray[] ys) {
            return new TabularDataset(batchSize, xs, ys);
        }
    }
}
