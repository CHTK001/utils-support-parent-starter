package com.chua.deeplearning.support.weka.data;

import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;

/**
 * 特征列定义。
 *
 * <p>描述建模数据中的一列特征：列名、类型（数值 / 类别）与描述。
 * 描述字段仅供业务展示使用，不会传递到 Weka 属性域。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public final class FeatureColumn implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 特征类型。
     */
    public enum FeatureType {

        /** 数值特征，映射为 Weka 数值属性 */
        NUMERIC,

        /** 类别特征（字符串取值），映射为 Weka 名义属性 */
        CATEGORICAL
    }

    /** 列名 */
    private final String name;

    /** 特征类型 */
    private final FeatureType type;

    /** 列描述 */
    private final String description;

    private FeatureColumn(String name, FeatureType type, String description) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("特征列名不能为空白");
        }
        Objects.requireNonNull(type, "type must not be null");
        this.name = name;
        this.type = type;
        this.description = description == null ? "" : description;
    }

    /**
     * 构建数值特征。
     *
     * @param name 列名
     * @return 数值特征定义
     */
    public static FeatureColumn numeric(String name) {
        return new FeatureColumn(name, FeatureType.NUMERIC, "");
    }

    /**
     * 构建数值特征。
     *
     * @param name        列名
     * @param description 列描述
     * @return 数值特征定义
     */
    public static FeatureColumn numeric(String name, String description) {
        return new FeatureColumn(name, FeatureType.NUMERIC, description);
    }

    /**
     * 构建类别特征。
     *
     * @param name 列名
     * @return 类别特征定义
     */
    public static FeatureColumn categorical(String name) {
        return new FeatureColumn(name, FeatureType.CATEGORICAL, "");
    }

    /**
     * 构建类别特征。
     *
     * @param name        列名
     * @param description 列描述
     * @return 类别特征定义
     */
    public static FeatureColumn categorical(String name, String description) {
        return new FeatureColumn(name, FeatureType.CATEGORICAL, description);
    }

    @Override
    public String toString() {
        return name + "(" + type + ")";
    }
}
