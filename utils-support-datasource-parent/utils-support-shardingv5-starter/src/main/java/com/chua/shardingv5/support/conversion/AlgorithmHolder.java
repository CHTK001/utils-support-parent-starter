package com.chua.shardingv5.support.conversion;

import java.util.Map;
/**
 * 分片算法配置持有者（算法类型与属性表）。
 *
 * @author CH
 * @since 4.0.0.42
 */

public class AlgorithmHolder {
    final String type; // 类型
    final Map<String, String> props;
    /**
     * 构造方法，创建 AlgorithmHolder 实例。
     * @param Map<String 参数
     * @param props 属性，不允许为 null
     * @param type 类型，不允许为 null
     * @return 返回值
    */
    AlgorithmHolder(String type, Map<String, String> props) {
        this.type = type;
        this.props = props;
    }
}