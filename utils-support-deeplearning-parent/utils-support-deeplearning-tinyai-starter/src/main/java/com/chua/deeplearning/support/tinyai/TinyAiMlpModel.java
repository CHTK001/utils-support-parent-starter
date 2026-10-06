package com.chua.deeplearning.support.tinyai;

import io.leavesfly.tinyai.ml.model.Model;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * TinyAI 神经网络分类模型包装。
 *
 * <p>持有训练好的 TinyAI {@link Model}、建模域快照与训练参数，
 * 支持 JDK 序列化落盘 / 恢复，以及单条、批量预测。
 * TinyAI Model 本身可序列化（tmpPredict 为 transient），此处整体 JDK 序列化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TinyAiMlpModel implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 底层 TinyAI 模型。
     */
    private final Model model;

    /**
     * 训练参数快照。
     */
    private final MlpOptions options;

    /**
     * 建模域快照（特征列 / 标签列 / 标签表 / 类别口径）。
     */
    private final ModelDomain domain;

    /**
     * 训练引擎（无状态，推理时重建，不参与序列化）。
     */
    private transient TinyAiEngine engine = new TinyAiEngine();

    /**
     * 创建时间戳（毫秒）。
     */
    private final long createdAtMillis;

    /**
     * 内部构造器。
     *
     * @param model 训练好的模型，不能为 null
     * @param options 训练参数，不能为 null
     * @param domain 数据域，不能为 null
     */
    TinyAiMlpModel(Model model, MlpOptions options, ModelDomain domain) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.domain = Objects.requireNonNull(domain, "domain must not be null");
        this.createdAtMillis = System.currentTimeMillis();
    }

    /**
     * 底层 TinyAI 模型。
     *
     * @return 模型
     */
    public Model getModel() {
        return model;
    }

    /**
     * 训练参数快照。
     *
     * @return 参数
     */
    public MlpOptions getOptions() {
        return options;
    }

    /**
     * 建模域快照。
     *
     * @return 数据域
     */
    public ModelDomain getDomain() {
        return domain;
    }

    /**
     * 创建时间戳（毫秒）。
     *
     * @return 时间戳
     */
    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    /**
     * 反序列化后重建瞬时引擎。
     *
     * @param in 对象输入流
     */
    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        engine = new TinyAiEngine();
    }

    /**
     * 单条预测：softmax 概率分布 + argmax 标签。
     *
     * @param row 预测数据行（可缺省标签列），不能为 null
     * @return 分类结果（标签 + 置信度 + 概率分布）
     */
    public ClassificationResult predict(Map<String, Object> row) {
        Objects.requireNonNull(row, "row must not be null");
        var probs = engine.infer(model, domain, row);
        var labels = domain.labels();
        var best = 0;
        for (var i = 1; i < probs.length; i++) {
            if (probs[i] > probs[best]) {
                best = i;
            }
        }
        var distribution = new LinkedHashMap<String, Double>(labels.size());
        for (var i = 0; i < labels.size(); i++) {
            distribution.put(labels.get(i), (double) probs[i]);
        }
        return new ClassificationResult(labels.get(best), best, probs[best], distribution);
    }

    /**
     * 保存模型到磁盘（JDK 序列化）。
     *
     * @param file 目标文件，不能为 null
     */
    public void save(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        try (var oos = new ObjectOutputStream(new FileOutputStream(file.toFile()))) {
            oos.writeObject(this);
        } catch (IOException e) {
            throw new TinyAiException("模型保存失败: " + file, e);
        }
    }

    /**
     * 从磁盘加载模型。
     *
     * @param file 模型文件，不能为 null
     * @return 模型实例
     */
    public static TinyAiMlpModel load(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        try (var ois = new ObjectInputStream(new FileInputStream(file.toFile()))) {
            return (TinyAiMlpModel) ois.readObject();
        } catch (Exception e) {
            throw new TinyAiException("模型加载失败: " + file, e);
        }
    }

    /**
     * 分类预测结果。
     *
     * @param label 预测标签
     * @param labelIndex 标签下标
     * @param confidence 置信度（0~1）
     * @param probabilities 各类别概率分布
     */
    public record ClassificationResult(String label, int labelIndex, double confidence,
            Map<String, Double> probabilities) implements Serializable {

        /**
         * 规范构造器：概率分布防御性拷贝。
         *
         * @param label 预测标签
         * @param labelIndex 标签下标
         * @param confidence 置信度
         * @param probabilities 概率分布
         */
        public ClassificationResult {
            Objects.requireNonNull(label, "label 不能为 null");
            Objects.requireNonNull(probabilities, "probabilities 不能为 null");
            probabilities = Map.copyOf(probabilities);
        }
    }
}
