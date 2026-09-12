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

    private static final long serialVersionUID = 1L; // 串行版本uid

    /**
    * 特征类型。
    * @author CH
    * @since 4.0.0
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

    /**
    * 内部构造器：校验列名非空非空白、类型非空，描述为 空 时按空字符串固化
    *
    * @param name 名称
    * @param type 类型
    * @param description description
    * @return 特征column的结果
     */
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
    * 构建无描述的数值特征。
    *
    * @param name 列名，不能为 空 或空白
    * @return 数值特征定义
    * @throws NullPointerException     当列名为 空 时
    * @throws IllegalArgumentException 当列名为空白字符串时
     */
    public static FeatureColumn numeric(String name) {
        return new FeatureColumn(name, FeatureType.NUMERIC, "");
    }

    /**
    * 构建带描述的数值特征（描述仅供业务展示，不进入 Weka 属性域）。
    *
    * @param name        列名，不能为 空 或空白
    * @param description 列描述，可为 空（按空字符串处理）
    * @return 数值特征定义
    * @throws NullPointerException     当列名为 空 时
    * @throws IllegalArgumentException 当列名为空白字符串时
     */
    public static FeatureColumn numeric(String name, String description) {
        return new FeatureColumn(name, FeatureType.NUMERIC, description);
    }

    /**
    * 构建无描述的类别特征。
    *
    * @param name 列名，不能为 空 或空白
    * @return 类别特征定义
    * @throws NullPointerException     当列名为 空 时
    * @throws IllegalArgumentException 当列名为空白字符串时
     */
    public static FeatureColumn categorical(String name) {
        return new FeatureColumn(name, FeatureType.CATEGORICAL, "");
    }

    /**
    * 构建带描述的类别特征（描述仅供业务展示，不进入 Weka 属性域）。
    *
    * @param name        列名，不能为 空 或空白
    * @param description 列描述，可为 空（按空字符串处理）
    * @return 类别特征定义
    * @throws NullPointerException     当列名为 空 时
    * @throws IllegalArgumentException 当列名为空白字符串时
     */
    public static FeatureColumn categorical(String name, String description) {
        return new FeatureColumn(name, FeatureType.CATEGORICAL, description);
    }

    @Override
    public String toString() {
        return name + "(" + type + ")";
    }
}
