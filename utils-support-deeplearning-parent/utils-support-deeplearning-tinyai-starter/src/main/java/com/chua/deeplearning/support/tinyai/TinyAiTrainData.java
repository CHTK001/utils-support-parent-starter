package com.chua.deeplearning.support.tinyai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TinyAI 分类训练数据。
 *
 * <p>用户侧唯一的训练数据入口：特征列定义 + 标签列名 + 行数据三件套。
 * 支持链式 Builder、手写行追加、JSON 反序列化；内部训练时由
 * {@link #toDomain()} 转为张量编码口径（SPI 侧自动完成，用户无感知）。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * TinyAiTrainData data = TinyAiTrainData.classification("label")
 *         .numeric("x1").numeric("x2")
 *         .addRow(Map.of("x1", 0.1, "x2", 0.2, "label", "low"))
 *         .addRow(Map.of("x1", 0.9, "x2", 0.8, "label", "high"));
 * TinyAiMlpModel model = new TinyAiMlpClassifier().train(data, MlpOptions.defaults());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TinyAiTrainData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 行数据 JSON 反序列化类型。
     */
    private static final TypeReference<List<Map<String, Object>>> ROWS_TYPE = new TypeReference<>() {
    };

    /**
     * Jackson 映射器（行数据 JSON 解析用）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 特征列定义（顺序即编码顺序）。
     */
    private final List<FeatureColumn> features;

    /**
     * 标签列名。
     */
    private final String labelColumn;

    /**
     * 行数据（列名 -> 值）。
     */
    private final List<Map<String, Object>> rows;

    /**
     * 内部构造器。
     *
     * @param features 特征列定义
     * @param labelColumn 标签列名
     * @param rows 行数据
     */
    private TinyAiTrainData(List<FeatureColumn> features, String labelColumn, List<Map<String, Object>> rows) {
        Objects.requireNonNull(features, "features 不能为 null");
        Objects.requireNonNull(labelColumn, "labelColumn 不能为 null");
        Objects.requireNonNull(rows, "rows 不能为 null");
        if (features.isEmpty()) {
            throw new TinyAiException("特征列不能为空");
        }
        if (labelColumn.isBlank()) {
            throw new TinyAiException("标签列名不能为空白");
        }
        if (rows.size() < 2) {
            throw new TinyAiException("样本行数不足: 至少 2 行，实际 " + rows.size());
        }
        var featuresCopy = new ArrayList<FeatureColumn>(features.size());
        for (var feature : features) {
            Objects.requireNonNull(feature, "特征列不能为 null");
            var copy = feature.isNumeric() ? FeatureColumn.numeric(feature.name())
                    : FeatureColumn.categorical(feature.name());
            copy.categories().addAll(feature.categories());
            featuresCopy.add(copy);
        }
        this.features = List.copyOf(featuresCopy);
        this.labelColumn = labelColumn;
        var copy = new ArrayList<Map<String, Object>>(rows.size());
        for (var row : rows) {
            Objects.requireNonNull(row, "样本行不能为 null");
            copy.add(Map.copyOf(row));
        }
        this.rows = List.copyOf(copy);
    }

    /**
     * 特征列定义（顺序即编码顺序）。
     *
     * @return 特征列
     */
    public List<FeatureColumn> features() {
        return features;
    }

    /**
     * 标签列名。
     *
     * @return 标签列名
     */
    public String labelColumn() {
        return labelColumn;
    }

    /**
     * 行数据。
     *
     * @return 行数据
     */
    public List<Map<String, Object>> rows() {
        return rows;
    }

    /**
     * 转为内部数据域（含标签表收集与类别口径）。
     *
     * @return 数据域
     */
    public ModelDomain toDomain() {
        return ModelDomain.of(features, labelColumn, rows);
    }

    /**
     * 新建分类训练数据构建器。
     *
     * @param labelColumn 标签列名，不能为 null / 空白
     * @return 构建器
     */
    public static Builder classification(String labelColumn) {
        return new Builder(labelColumn);
    }

    /**
     * 从样本行自动推断特征列（排除标签列）。
     *
     * @param labelColumn 标签列名，不能为 null / 空白
     * @param rows 样本行，至少 2 行，不能为 null
     * @return 训练数据
     */
    public static TinyAiTrainData infer(String labelColumn, List<Map<String, Object>> rows) {
        Objects.requireNonNull(labelColumn, "labelColumn 不能为 null");
        Objects.requireNonNull(rows, "rows 不能为 null");
        if (labelColumn.isBlank()) {
            throw new TinyAiException("标签列名不能为空白");
        }
        var names = new LinkedHashSet<String>();
        for (var row : rows) {
            if (row != null) {
                row.keySet().stream().filter(name -> !name.equals(labelColumn)).forEach(names::add);
            }
        }
        if (names.isEmpty()) {
            throw new TinyAiException("样本行不包含任何特征列（仅标签列?）");
        }
        var builder = new Builder(labelColumn);
        for (var name : names) {
            var numeric = rows.stream()
                    .filter(row -> row != null && row.get(name) != null)
                    .map(row -> row.get(name))
                    .allMatch(TinyAiTrainData::isNumericValue);
            if (numeric) {
                builder.numeric(name);
            } else {
                builder.categorical(name);
            }
        }
        return builder.rows(rows).build();
    }

    /**
     * 从 JSON 数组反序列化行数据并推断特征列。
     *
     * <p>JSON 形如 {@code [{"x1":0.1,"x2":0.2,"label":"low"}, ...]}；
     * 特征列自动推断（数值 / 类别），内部走同一套推断口径。</p>
     *
     * @param labelColumn 标签列名，不能为 null / 空白
     * @param json 行数据 JSON 数组，不能为 null / 空白
     * @return 训练数据
     */
    public static TinyAiTrainData fromJson(String labelColumn, String json) {
        Objects.requireNonNull(labelColumn, "labelColumn 不能为 null");
        Objects.requireNonNull(json, "json 不能为 null");
        if (json.isBlank()) {
            throw new TinyAiException("行数据 JSON 不能为空白");
        }
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(json, ROWS_TYPE);
            return infer(labelColumn, rows);
        } catch (TinyAiException e) {
            throw e;
        } catch (Exception e) {
            throw new TinyAiException("行数据 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断单个值是否可作为数值列内容。
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
     * TinyAI 分类训练数据构建器。
     */
    public static final class Builder {

        /**
         * 标签列名。
         */
        private final String labelColumn;

        /**
         * 特征列定义。
         */
        private final List<FeatureColumn> features = new ArrayList<>();

        /**
         * 行数据。
         */
        private final List<Map<String, Object>> rows = new ArrayList<>();

        /**
         * 构造构建器。
         *
         * @param labelColumn 标签列名
         */
        private Builder(String labelColumn) {
            Objects.requireNonNull(labelColumn, "labelColumn 不能为 null");
            if (labelColumn.isBlank()) {
                throw new TinyAiException("标签列名不能为空白");
            }
            this.labelColumn = labelColumn;
        }

        /**
         * 声明数值特征列。
         *
         * @param name 列名
         * @return this
         */
        public Builder numeric(String name) {
            features.add(FeatureColumn.numeric(name));
            return this;
        }

        /**
         * 声明类别特征列。
         *
         * @param name 列名
         * @return this
         */
        public Builder categorical(String name) {
            features.add(FeatureColumn.categorical(name));
            return this;
        }

        /**
         * 追加一行数据。
         *
         * @param row 行数据（列名 -> 值）
         * @return this
         */
        public Builder addRow(Map<String, Object> row) {
            Objects.requireNonNull(row, "row 不能为 null");
            rows.add(new LinkedHashMap<>(row));
            return this;
        }

        /**
         * 批量追加行数据。
         *
         * @param rows 行数据
         * @return this
         */
        public Builder rows(List<Map<String, Object>> rows) {
            Objects.requireNonNull(rows, "rows 不能为 null");
            for (var row : rows) {
                addRow(row);
            }
            return this;
        }

        /**
         * 构建训练数据。
         *
         * @return 训练数据
         */
        public TinyAiTrainData build() {
            return new TinyAiTrainData(features, labelColumn, rows);
        }
    }
}
