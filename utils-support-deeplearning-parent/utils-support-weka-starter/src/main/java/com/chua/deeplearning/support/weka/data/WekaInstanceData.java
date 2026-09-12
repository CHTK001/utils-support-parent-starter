package com.chua.deeplearning.support.weka.data;

import com.chua.deeplearning.support.weka.WekaException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
* }</pre>(
*         features, "target",
*         List.of(Map.of("age", 30, "city", "北京", "target", 98.5)));
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Getter
public final class WekaInstanceData {

    /** 缺失值标记（Weka 3.8 以 nan 表示缺失） */
    public static final double MISSING_VALUE = Double.NaN;

    /** 特征列定义（不含目标列） */
    private final List<FeatureColumn> features;

    /** 分类标签列名（名义值），与 Targetcolumn 二选一 */
    private final String labelColumn;

    /** 回归目标列名（数值），与 标签column 二选一 */
    private final String targetColumn;

    /** 行数据，每行为「列名 -> 值」，值可为 数字 / 字符串 / 空（缺失） */
    private final List<Map<String, Object>> rows;

    /** 内部构造器：校验特征列非空、标签/目标列名非空白，固化不可变特征与行数据副本 */
    private WekaInstanceData(List<FeatureColumn> features, String labelColumn, String targetColumn,
            List<Map<String, Object>> rows) {
        Objects.requireNonNull(features, "features must not be null");
        if (features.isEmpty()) {
            throw new WekaException("特征列不能为空");
        }
        if (labelColumn != null && labelColumn.isBlank()) {
            throw new WekaException("标签列名不能为空白");
        }
        if (targetColumn != null && targetColumn.isBlank()) {
            throw new WekaException("目标列名不能为空白");
        }
        this.features = List.copyOf(features);
        this.labelColumn = labelColumn;
        this.targetColumn = targetColumn;
        this.rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
    * 构建通用数据对象（未指定标签 / 目标，需后续 {@link #withLabelColumn} 或 {@link #withTargetColumn}）。
    *
    * @param features 特征列定义，不能为 空 且不能为空集合
    * @param rows     行数据，可为 空（按空数据集处理）
    * @return 数据对象
    * @throws NullPointerException 当特征列为 空 时
    * @throws WekaException        当特征列为空集合时
     */
    public static WekaInstanceData of(List<FeatureColumn> features, List<Map<String, Object>> rows) {
        return new WekaInstanceData(features, null, null, rows);
    }

    /**
    * 构建分类数据对象（标签列承载名义值）。
    *
    * @param features    特征列定义，不能为 空 且不能为空集合
    * @param labelColumn 标签列名，不能为空白字符串
    * @param rows        行数据，可为 空（按空数据集处理）
    * @return 分类数据对象
    * @throws NullPointerException 当特征列为 空 时
    * @throws WekaException        当特征列为空集合或标签列名为空白时
     */
    public static WekaInstanceData classification(List<FeatureColumn> features, String labelColumn,
            List<Map<String, Object>> rows) {
        return new WekaInstanceData(features, labelColumn, null, rows);
    }

    /**
    * 构建回归数据对象（目标列承载数值）。
    *
    * @param features     特征列定义，不能为 空 且不能为空集合
    * @param targetColumn 目标列名，不能为空白字符串
    * @param rows         行数据，可为 空（按空数据集处理）
    * @return 回归数据对象
    * @throws NullPointerException 当特征列为 空 时
    * @throws WekaException        当特征列为空集合或目标列名为空白时
     */
    public static WekaInstanceData regression(List<FeatureColumn> features, String targetColumn,
            List<Map<String, Object>> rows) {
        return new WekaInstanceData(features, null, targetColumn, rows);
    }

    /**
    * 基于当前数据指定标签列，返回新的分类数据对象（原对象不变）。
    *
    * @param labelColumn 标签列名，不能为空白字符串
    * @return 分类数据对象
    * @throws WekaException 当标签列名为空白时
     */
    public WekaInstanceData withLabelColumn(String labelColumn) {
        return new WekaInstanceData(features, labelColumn, null, rows);
    }

    /**
    * 基于当前数据指定目标列，返回新的回归数据对象（原对象不变）。
    *
    * @param targetColumn 目标列名，不能为空白字符串
    * @return 回归数据对象
    * @throws WekaException 当目标列名为空白时
     */
    public WekaInstanceData withTargetColumn(String targetColumn) {
        return new WekaInstanceData(features, null, targetColumn, rows);
    }

    /**
    * 判断当前数据是否为分类场景（是否已设置标签列）。
    *
    * @return true 表示已设置标签列
     */
    public boolean hasLabel() {
        return labelColumn != null;
    }

    /**
    * 判断当前数据是否为回归场景（是否已设置目标列）。
    *
    * @return true 表示已设置目标列
     */
    public boolean hasTarget() {
        return targetColumn != null;
    }

    /**
    * 判断当前数据是否具备标签列或目标列（两者满足其一即可转换 Weka 实例）。
    *
    * @return true 表示已设置标签列或目标列
     */
    public boolean hasTargetOrLabel() {
        return hasLabel() || hasTarget();
    }

    /**
    * 获取目标列名（回归目标列优先，其次分类标签列）。
    *
    * @return 目标列名；标签列与目标列均未设置时返回 空
     */
    public String targetName() {
        return targetColumn != null ? targetColumn : labelColumn;
    }

    /**
    * 收集类别型列（含标签列）在数据中的取值集合。
    *
    * <p>供模型在预测阶段按相同的名义值顺序构建实例。</p>
    *
    * @return 列名 -> 取值列表（保持首次出现顺序）
     */
    public Map<String, List<String>> nominalValues() {
        var map = new LinkedHashMap<String, List<String>>(features.size() + 1);
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
        var nominal = nominalValues();
        var attrs = new ArrayList<Attribute>(features.size() + 1);
        for (var feature : features) {
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
        var ins = new Instances("WekaInstanceData", attrs, 0);
        ins.setClassIndex(attrs.size() - 1);
        for (var row : rows) {
            ins.add(new DenseInstance(1.0, toAttributeValues(ins, row)));
        }
        return ins;
    }

    /**
    * 将一行数据填入给定的实例结构，生成属性值数组。
    *
    * @param ins 提供属性结构的实例容器，不能为 空
    * @param row 一行数据（列名 -> 值），可为 空（视为整行缺失）
    * @return 属性值数组
    * @throws NullPointerException 当实例容器为 空 时
    * @throws WekaException        数值列内容非法
     */
    public static double[] toAttributeValues(Instances ins, Map<String, Object> row) {
        Objects.requireNonNull(ins, "ins must not be null");
        var values = new double[ins.numAttributes()];
        for (int i = 0; i < values.length; i++) {
            var attr = ins.attribute(i);
            var value = row == null ? null : row.get(attr.name());
            if (value == null) {
                values[i] = MISSING_VALUE;
            } else if (attr.isNumeric()) {
                values[i] = numericValue(attr.name(), value);
            } else {
                var text = value instanceof String s ? s : value.toString();
                var index = attr.indexOfValue(text);
                values[i] = index >= 0 ? index : MISSING_VALUE;
            }
        }
        return values;
    }

    /**
    * 解析数值列取值（数字 直接取值，可解析的 字符串 按 double 解析）。
    *
    * @param name  列名（仅用于异常信息），不能为 空
    * @param value 原始值，须为 数字 或可解析为 double 的 字符串，不能为 空
    * @return 解析后的 double 值
    * @throws NullPointerException 当列名或取值为 空 时
    * @throws WekaException        值无法解析为数值
    * @param columnName column名称
     /**
      * numeric值。
      * @param name 名称
      * @param value 值
      * @return numeric值的结果
      */
      * @param columnName column名称
     /**
     * numeric值。
     * @param name 名称
     * @param value 值
     * @return numeric值的结果
      */
     */
    public static double numericValue(String name, Object value) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(value, "value must not be null");
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
        var values = new LinkedHashSet<String>();
        for (var row : rows) {
            var value = row == null ? null : row.get(columnName);
            if (value != null) {
                values.add(value instanceof String s ? s : value.toString());
            }
        }
        return new ArrayList<>(values);
    }
}
