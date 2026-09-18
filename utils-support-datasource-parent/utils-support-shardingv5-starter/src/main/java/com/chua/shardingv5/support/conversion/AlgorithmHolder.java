package com.chua.shardingv5.support.conversion;

import java.util.Map;
/**
* @author CH
* @since 4.0.0.42
 */

public class AlgorithmHolder {
    final String type; // 类型
    final Map<String, String> props;
    /**
     * 构造方法，创建 AlgorithmHolder 实例。
     *
     * @param type 类型，不允许为 null
     * @param props 属性，不允许为 null
     */
    AlgorithmHolder(String type, Map<String, String> props) {
        this.type = type;
        this.props = props;
    }
}