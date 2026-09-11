package com.chua.deeplearning.support.weka.data;

import com.chua.deeplearning.support.weka.WekaException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

/**
 * Weka 建模输入数据对象。
 *
 * <p>以「特征列定义 + 行数据（列名 -> 值）」描述建模数据集。
 * 分类场景使用 {@link #labelColumn} 承载名义标签，回归场景使用 {@link #targetColumn} 承载数值目标，
 * 通过 {@link #toWekaInstances()} 转换为 Weka {@link Instances} 后喂给模型。</p>
 *
 * <p>行取值类型：数值列传 {@link Number}（或可解析的 {@link String}），
 * 类别列 / 标签列传 {@link String}，缺失传 {@code null}。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * List<FeatureColumn> features = List.of(
 *         FeatureColumn.numeric("age", "年龄"),
 *         FeatureColumn.categorical("city", "城市"));
 *
 * // 手工构建行数据（列名 -> 值，缺列 / 显式 null 均视为缺失）
 * Map<String, Object> row1 = Map.of("age", 30, "city", "北京");
 * Map<String, Object> row2 = Map.of("age", 45, "city", "上海");
 *
 * // 场景 1：分类（标签列 label）
 * WekaInstanceData training = WekaInstanceData.classification(
 *         features, "label",
 *         List.of(Map.of("age", 30, "city", "北京", "label", "高"),
 *                 Map.of("age", 45, "city", "上海", "label", "低")));
 *
 * // 场景 2：回归（目标列 target 为数值）
 * WekaInstanceData regression = WekaInstanceData.regression(
 *         features, "target",
 *         List.of(Map.of("age", 30, "city", "北京", "target", 98.5)));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public final class WekaInstanceData {

    /** 缺失值标记（Weka 3.8 以 NaN 表示缺失） */
    public static final double MISSING_VALUE = Double.NaN;

    /** 特征列定义（不含目标列） */
    private final List<FeatureColumn> features;

    /** 分类标签列名（名义值），与 targetColumn 二选一 */
    private final String labelColumn;

    /** 回归目标列名（数值），与 labelColumn 二选一 */
    private final String targetColumn;

    /** 行数据，每行为「列名 -> 值」，值可为 Number / String / null（缺失） */
    private final List<Map<String, Object>> rows;

    private WekaInstanceData(List<FeatureColumn> features, String labelColumn, String targetColumn,
            List<Map<String, Object>> rows) {
        if (features == null || features.isEmpty()) {
            throw new WekaException("特征列不能为空");
        }
        this.features = List.copyOf(features);
        this.labelColumn = labelColumn;
        this.targetColumn = targetColumn;
        this.rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * 构建通用数据对象（未指定标签 / 目标，需后续 {@link #withLabelColumn} 或 {@link #withTargetColumn}）。
     *
     * @param features 特征列定义
     * @param rows     行数据
     * @return 数据对象
     */
    public static WekaInstanceData of(List<FeatureColumn> features, List<Map<String, Object>> rows) {
        return new WekaInstanceData(features, null, null, rows);
    }

    /**
     * 构建分类数据对象。
     *
     * @param features    特征列定义
     * @param labelColumn 标签列名
     * @param rows        行数据
     * @return 数据对象
     */
    public static WekaInstanceData classification(List<FeatureColumn> features, String labelColumn,
            List<Map<String, Object>> rows) {
        return new WekaInstanceData(features, labelColumn, null, rows);
    }

    /**
     * 构建回归数据对象。
     *
     * @param features     特征列定义
     * @param targetColumn 目标列名
     * @param rows         行数据
     * @return 数据对象
     */
    public static WekaInstanceData regression(List<FeatureColumn> features, String targetColumn,
            List<Map<String, Object>> rows) {
        return new WekaInstanceData(features, null, targetColumn, rows);
    }

    /**
     * 基于当前数据指定标签列，返回新的分类数据对象。
     *
     * @param labelColumn 标签列名
     * @return 分类数据对象
     */
    public WekaInstanceData withLabelColumn(String labelColumn) {
        return new WekaInstanceData(features, labelColumn, null, rows);
    }

    /**
     * 基于当前数据指定目标列，返回新的回归数据对象。
     *
     * @param targetColumn 目标列名
     * @return 回归数据对象
     */
    public WekaInstanceData withTargetColumn(String targetColumn) {
        return new WekaInstanceData(features, null, targetColumn, rows);
    }

    /**
     * @return 是否设置了标签列
     */
    public boolean hasLabel() {
        return labelColumn != null;
    }

    /**
     * @return 是否设置了目标列
     */
    public boolean hasTarget() {
        return targetColumn != null;
    }

    /**
     * @return 是否具备目标列或标签列
     */
    public boolean hasTargetOrLabel() {
        return hasLabel() || hasTarget();
    }

    /**
     * @return 目标列名（优先目标列，其次标签列）
     */
    public String targetName() {
        return targetColumn != null ? targetColumn : labelColumn;
    }

    /**
     * @return 特征列定义
     */
    public List<FeatureColumn> getFeatures() {
        return features;
    }

    /**
     * @return 标签列名（可能为 null）
     */
    public String getLabelColumn() {
        return labelColumn;
    }

    /**
     * @return 目标列名（可能为 null）
     */
    public String getTargetColumn() {
        return targetColumn;
    }

    /**
     * @return 行数据
     */
    public List<Map<String, Object>> getRows() {
        return rows;
    }

    /**
     * 收集类别型列（含标签列）在数据中的取值集合。
     *
     * <p>供模型在预测阶段按相同的名义值顺序构建实例。</p>
     *
     * @return 列名 -> 取值列表（保持首次出现顺序）
     */
    public Map<String, List<String>> nominalValues() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (FeatureColumn feature : features) {
            if (feature.getType() == FeatureColumn.FeatureType.CATEGORICAL) {
                map.put(feature.getName(), collectValues(feature.getName()));
            }
        }
        if (labelColumn != null) {
            map.put(labelColumn, collectValues(labelColumn));
        }
        return map;
    }

    /**
     * 转换为 Weka 实例容器（含属性结构与全部行）。
     *
     * @return Weka 实例
     * @throws WekaException 未设置标签 / 目标列，或数值列内容非法
     */
    public Instances toWekaInstances() {
        if (!hasTargetOrLabel()) {
            throw new WekaException("请先指定标签列或目标列（withLabelColumn / withTargetColumn）");
        }
        Map<String, List<String>> nominal = nominalValues();
        ArrayList<Attribute> attrs = new ArrayList<>(features.size() + 1);
        for (FeatureColumn feature : features) {
            if (feature.getType() == FeatureColumn.FeatureType.NUMERIC) {
                attrs.add(new Attribute(feature.getName()));
            } else {
                attrs.add(new Attribute(feature.getName(), nominal.getOrDefault(feature.getName(), List.of(""))));
            }
        }
        if (hasTarget()) {
            attrs.add(new Attribute(targetColumn));
        } else {
            attrs.add(new Attribute(labelColumn, nominal.getOrDefault(labelColumn, List.of(""))));
        }
        Instances ins = new Instances("WekaInstanceData", attrs, 0);
        ins.setClassIndex(attrs.size() - 1);
        for (Map<String, Object> row : rows) {
            ins.add(new DenseInstance(1.0, toAttributeValues(ins, row)));
        }
        return ins;
    }

    /**
     * 将一行数据填入给定的实例结构，生成属性值数组。
     *
     * @param ins 提供属性结构的实例容器
     * @param row 一行数据（列名 -> 值）
     * @return 属性值数组
     * @throws WekaException 数值列内容非法
     */
    public static double[] toAttributeValues(Instances ins, Map<String, Object> row) {
        double[] values = new double[ins.numAttributes()];
        for (int i = 0; i < values.length; i++) {
            Attribute attr = ins.attribute(i);
            Object value = row == null ? null : row.get(attr.name());
            if (value == null) {
                values[i] = MISSING_VALUE;
            } else if (attr.isNumeric()) {
                values[i] = numericValue(attr.name(), value);
            } else {
                String text = value instanceof String s ? s : value.toString();
                int index = attr.indexOfValue(text);
                values[i] = index >= 0 ? index : MISSING_VALUE;
            }
        }
        return values;
    }

    /**
     * 解析数值列取值。
     *
     * @param name  列名
     * @param value 原始值
     * @return 解析结果
     * @throws WekaException 值无法解析为数值
     */
    public static double numericValue(String name, Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                throw new WekaException("数值列「" + name + "」存在非数值内容: " + text);
            }
        }
        throw new WekaException("数值列「" + name + "」内容类型不匹配: " + value.getClass().getSimpleName());
    }

    private List<String> collectValues(String columnName) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object value = row == null ? null : row.get(columnName);
            if (value != null) {
                values.add(value instanceof String s ? s : value.toString());
            }
        }
        return new ArrayList<>(values);
    }
}
