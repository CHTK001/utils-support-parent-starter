package com.chua.hprof.support.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个类的明细：top 实例的字段值 + 静态字段。
 *
 * <p>用于报告"点开看详情"：每个 top 类可展开看到
 * 其保留最大的实例 id、该实例的关键字段值（哪个字段是大集合 / 大数组 /
 * 长字符串）、以及静态字段持有者。这是定位"到底是谁把东西存进去"
 * 的核心数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public class HprofClassDetail {

    /**
     * 类全名。
     */
    private final String className;

    /**
     * 该类的 top 实例明细。
     */
    private final List<InstanceDetail> instances;

    /**
     * 静态字段值。
     */
    private final List<FieldValueDetail> staticFields;

    /**
     * 构造类明细。
     *
     * @param className  类全名
     * @param instances  top 实例明细
     * @param staticFields 静态字段值
     */
    public HprofClassDetail(String className,
                            List<InstanceDetail> instances,
                            List<FieldValueDetail> staticFields) {
        this.className = className;
        this.instances = instances == null ? new ArrayList<>() : instances;
        this.staticFields = staticFields == null ? new ArrayList<>() : staticFields;
    }

    /**
     * 单个实例的字段值明细。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @Getter
    public static final class InstanceDetail {

        /**
         * 实例 id（堆内句柄）。
         */
        private final long instanceId;

        /**
         * 实例保留大小。
         */
        private final long retainedSize;

        /**
         * 实例浅层大小。
         */
        private final long shallowSize;

        /**
         * 字段值明细（仅取引用型 / 数组 / 字符串字段，跳过基本类型）。
         */
        private final List<FieldValueDetail> fieldValues;

        /**
         * 构造实例明细。
         *
         * @param instanceId   实例 id
         * @param retainedSize 保留大小
         * @param shallowSize  浅层大小
         * @param fieldValues  字段值
         */
        public InstanceDetail(long instanceId, long retainedSize, long shallowSize,
                             List<FieldValueDetail> fieldValues) {
            this.instanceId = instanceId;
            this.retainedSize = retainedSize;
            this.shallowSize = shallowSize;
            this.fieldValues = fieldValues == null ? new ArrayList<>() : fieldValues;
        }
    }

    /**
     * 字段值明细。
     *
     * <p>{@code valueText} 为人类可读表示：引用类型显示目标类名 + id，
     * 数组显示元素类型 + 长度，字符串显示截断文本。</p>
     *
     * @author CH
     * @since 4.0.0.42
     */
    @Getter
    public static final class FieldValueDetail {

        /**
         * 字段名。
         */
        private final String name;

        /**
         * 是否静态字段。
         */
        private final boolean isStatic;

        /**
         * 目标类型名（引用类型）或基本类型名。
         */
        private final String type;

        /**
         * 人类可读值：对象 -> 类名@id，数组 -> 类型[长度]，字符串 -> "..."（截断）。
         */
        private final String valueText;

        /**
         * 构造字段值明细。
         *
         * @param name      字段名
         * @param isStatic  是否静态
         * @param type      目标类型
         * @param valueText 可读值
         */
        public FieldValueDetail(String name, boolean isStatic, String type, String valueText) {
            this.name = name;
            this.isStatic = isStatic;
            this.type = type;
            this.valueText = valueText;
        }
    }
}
