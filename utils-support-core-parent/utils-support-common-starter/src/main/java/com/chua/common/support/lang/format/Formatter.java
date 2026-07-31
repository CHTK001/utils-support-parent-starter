package com.chua.common.support.lang.format;

/**
 * 格式化器接口，用于定义字符串格式化的标准行为。
 * 实现类应提供将输入源字符串转换为特定格式的逻辑。
 *
 * @author CH
 */
public interface Formatter {
    /**
     * 格式化给定的源字符串。
     * 此方法将执行特定的格式化规则并返回处理后的结果。
     *
     * @param source 待格式化的原始字符串
     * @return 格式化后的字符串
     */
    String format(String source);
}