package com.chua.shardingv5.support.conversion;

import java.util.Map;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class AlgorithmHolder {
    final String type; final Map<String, String> props;
    AlgorithmHolder(String type, Map<String, String> props) {
        this.type = type; this.props = props;
    }
}