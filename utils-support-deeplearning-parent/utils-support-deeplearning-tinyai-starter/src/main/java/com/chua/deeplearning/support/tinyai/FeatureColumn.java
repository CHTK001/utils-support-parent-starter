package com.chua.deeplearning.support.tinyai;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 特征列定义。
 *
 * <p>描述建模数据中的一列特征：列名、类型（数值 / 类别）与类别取值表。
 * 与 weka 模块 {@code FeatureColumn} 同构，差异仅在取值表：
 * 类别列的取值表在训练期收集（首次出现顺序），预测期复用同一口径做 one-hot 编码，
 * 预测期出现训练期未见取值时编码为全 0（未知类别不断言失败，保证线上可预测）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FeatureColumn implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 特征类型。
     */
    public enum FeatureType {

        /**
         * 数值特征，占 1 维
         */
        NUMERIC,

        /**
         * 类别特征，按取值表 one-hot 展开
         */
        CATEGORICAL
    }

    /**
     * 列名。
     */
    private final String name;

    /**
     * 特征类型。
     */
    private final FeatureType type;

    /**
     * 类别取值表（仅类别列使用，首次出现顺序；数值列为空表）。
     */
    private final List<String> categories = new ArrayList<>();

    /**
     * 内部构造器。
     *
     * @param name 列名，不能为 null / 空白
     * @param type 类型，不能为 null
     */
    private FeatureColumn(String name, FeatureType type) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("特征列名不能为空白");
        }
        Objects.requireNonNull(type, "type must not be null");
        this.name = name;
        this.type = type;
    }

    /**
     * 构建数值特征。
     *
     * @param name 列名，不能为 null / 空白
     * @return 数值特征定义
     */
    public static FeatureColumn numeric(String name) {
        return new FeatureColumn(name, FeatureType.NUMERIC);
    }

    /**
     * 构建类别特征。
     *
     * @param name 列名，不能为 null / 空白
     * @return 类别特征定义
     */
    public static FeatureColumn categorical(String name) {
        return new FeatureColumn(name, FeatureType.CATEGORICAL);
    }

    /**
     * 列名。
     *
     * @return 列名
     */
    public String name() {
        return name;
    }

    /**
     * 特征类型。
     *
     * @return 特征类型
     */
    public FeatureType type() {
        return type;
    }

    /**
     * 是否数值列。
     *
     * @return true 表示数值列
     */
    public boolean isNumeric() {
        return type == FeatureType.NUMERIC;
    }

    /**
     * 类别取值表（可变，训练期收集、预测期只读）。
     *
     * @return 取值表
     */
    public List<String> categories() {
        return categories;
    }

    @Override
    public String toString() {
        return name + "(" + type + ")";
    }
}
