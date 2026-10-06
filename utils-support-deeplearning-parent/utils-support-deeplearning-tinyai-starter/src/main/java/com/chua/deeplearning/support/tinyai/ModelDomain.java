package com.chua.deeplearning.support.tinyai;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TinyAI 建模数据域：特征列定义 + 标签取值表。
 *
 * <p>与 weka 模块 {@code ModelDomain} 对应的 TinyAI 侧数据域：
 * 记录特征列顺序 / 数值列集合 / 标签有序取值，供训练编码、
 * 预测编码、评估复用同一套口径。特征顺序按样本行首次出现顺序固定，
 * 同一份域对象在训练与预测间共享，保证向量下标一致。</p>
 *
 * @param features   特征列定义（顺序即编码顺序）
 * @param targetName 标签列名
 * @param labels     标签有序取值（首次出现顺序）
 * @author CH
 * @since 4.0.0.42
 */
public record ModelDomain(List<FeatureColumn> features, String targetName, List<String> labels)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造数据域。
     *
     * @param features   特征列定义
     * @param targetName 标签列名
     * @param labels     标签有序取值
     */
    public ModelDomain {
        Objects.requireNonNull(features, "features 不能为 null");
        Objects.requireNonNull(targetName, "targetName 不能为 null");
        Objects.requireNonNull(labels, "labels 不能为 null");
        if (features.isEmpty()) {
            throw new TinyAiException("特征列不能为空");
        }
        if (targetName.isBlank()) {
            throw new TinyAiException("标签列名不能为空白");
        }
        if (labels.size() < 2) {
            throw new TinyAiException("分类标签至少需要 2 种取值，实际 " + labels.size());
        }
        features = List.copyOf(features);
        labels = List.copyOf(labels);
    }

    /**
     * 从样本行推断数据域（排除标签列，保持首次出现顺序）。
     *
     * <p>类型推断规则与 weka 模块一致：某列全部非空值均为
     * {@link Number}（或可解析为数值的字符串）时为数值列，否则为类别列。</p>
     *
     * @param samples     样本行，不能为 null
     * @param labelColumn 标签列名，不能为 null / 空白
     * @return 数据域
     */
    public static ModelDomain of(List<Map<String, Object>> samples, String labelColumn) {
        Objects.requireNonNull(samples, "samples 不能为 null");
        Objects.requireNonNull(labelColumn, "labelColumn 不能为 null");
        if (labelColumn.isBlank()) {
            throw new TinyAiException("标签列名不能为空白");
        }
        var names = new LinkedHashSet<String>();
        for (var row : samples) {
            if (row != null) {
                row.keySet().stream().filter(name -> !name.equals(labelColumn)).forEach(names::add);
            }
        }
        if (names.isEmpty()) {
            throw new TinyAiException("样本行不包含任何特征列（仅标签列?）");
        }
        var features = new ArrayList<FeatureColumn>(names.size());
        for (var name : names) {
            var numeric = samples.stream()
                    .filter(row -> row != null && row.get(name) != null)
                    .map(row -> row.get(name))
                    .allMatch(ModelDomain::isNumericValue);
            features.add(numeric ? FeatureColumn.numeric(name) : FeatureColumn.categorical(name));
        }
        return of(features, labelColumn, samples);
    }

    /**
     * 从显式特征列定义构建数据域（TrainData / SPI 内部转化入口）。
     *
     * <p>特征顺序按传入定义固定；标签取值表按样本首次出现顺序收集；
     * 类别列取值表同步收集，供预测期编码复用。</p>
     *
     * @param features    特征列定义，不能为 null / 空
     * @param labelColumn 标签列名，不能为 null / 空白
     * @param samples     样本行，不能为 null
     * @return 数据域
     */
    public static ModelDomain of(List<FeatureColumn> features, String labelColumn,
            List<Map<String, Object>> samples) {
        Objects.requireNonNull(features, "features 不能为 null");
        Objects.requireNonNull(labelColumn, "labelColumn 不能为 null");
        Objects.requireNonNull(samples, "samples 不能为 null");
        if (features.isEmpty()) {
            throw new TinyAiException("特征列不能为空");
        }
        if (labelColumn.isBlank()) {
            throw new TinyAiException("标签列名不能为空白");
        }
        var labels = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        for (var row : samples) {
            if (row == null) {
                continue;
            }
            var value = row.get(labelColumn);
            if (value == null) {
                throw new TinyAiException("样本行标签列 " + labelColumn + " 缺失");
            }
            var label = String.valueOf(value);
            if (seen.add(label)) {
                labels.add(label);
            }
        }
        if (labels.size() < 2) {
            throw new TinyAiException("分类标签至少需要 2 种取值，实际 " + labels.size());
        }
        var domain = new ModelDomain(new ArrayList<>(features), labelColumn, labels);
        domain.collectCategories(samples);
        return domain;
    }

    /**
     * 判断单个值是否可作为数值列内容。
     *
     * @param value 原始值
     * @return true 表示数值语义
     */
    private static boolean isNumericValue(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String text) {
            try {
                Double.parseDouble(text.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 收集类别列取值表（首次出现顺序），供预测期编码复用。
     *
     * @param samples 样本行
     */
    private void collectCategories(List<Map<String, Object>> samples) {
        for (var feature : features) {
            if (feature.isNumeric()) {
                continue;
            }
            var ordered = new LinkedHashSet<String>();
            for (var row : samples) {
                if (row == null) {
                    continue;
                }
                var value = row.get(feature.name());
                if (value != null) {
                    ordered.add(String.valueOf(value));
                }
            }
            feature.categories().addAll(ordered);
        }
    }

    /**
     * 特征向量维度：数值列各占 1 维，类别列按取值表 one-hot 展开。
     *
     * @return 输入维度
     */
    public int inputSize() {
        var size = 0;
        for (var feature : features) {
            size += feature.isNumeric() ? 1 : Math.max(1, feature.categories().size());
        }
        return size;
    }

    /**
     * 把一行数据编码为特征向量（顺序与 {@link #features} 一致）。
     *
     * <p>数值列取 double 值（缺失按 0）；类别列 one-hot，预测期出现训练期
     * 未见取值时全 0（等价于未知类别，不抛错，保证线上可预测）。</p>
     *
     * @param row 数据行
     * @return 特征向量
     */
    public float[] encode(Map<String, Object> row) {
        Objects.requireNonNull(row, "row 不能为 null");
        var vector = new float[inputSize()];
        var cursor = 0;
        for (var feature : features) {
            if (feature.isNumeric()) {
                vector[cursor++] = toDouble(row.get(feature.name()));
            } else {
                var categories = feature.categories();
                var value = row.get(feature.name());
                var index = value == null ? -1 : categories.indexOf(String.valueOf(value));
                var width = Math.max(1, categories.size());
                if (index >= 0) {
                    vector[cursor + index] = 1.0F;
                }
                cursor += width;
            }
        }
        return vector;
    }

    /**
     * 标签转下标。
     *
     * @param label 标签
     * @return 下标，未见标签返回 -1
     */
    public int labelIndex(String label) {
        return label == null ? -1 : labels.indexOf(label);
    }

    /**
     * 转 double 值（缺失 / 非法按 0）。
     */
    private static float toDouble(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof String text) {
            try {
                return Float.parseFloat(text.trim());
            } catch (NumberFormatException e) {
                return 0.0F;
            }
        }
        return 0.0F;
    }

    /**
     * 特征列取值表快照（供模型落盘后恢复类别口径）。
     *
     * @return 列名到有序取值的映射
     */
    public Map<String, List<String>> categorySnapshot() {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (var feature : features) {
            if (!feature.isNumeric()) {
                snapshot.put(feature.name(), List.copyOf(feature.categories()));
            }
        }
        return snapshot;
    }
}
