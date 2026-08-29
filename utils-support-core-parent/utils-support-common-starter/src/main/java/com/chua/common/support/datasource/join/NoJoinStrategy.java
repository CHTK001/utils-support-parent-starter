package com.chua.common.support.datasource.join;

import com.chua.common.support.spi.annotations.Spi;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 无 JOIN 策略（默认，单表模式）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("none")
public class NoJoinStrategy implements JoinStrategy {

    @Override
    public String name() { return "none"; }

    @Override
    public boolean enabled() { return false; }

    @Override
    public List<Map<String, Object>> execute(JoinContext ctx) {
        throw new UnsupportedOperationException(
                "JOIN not enabled. Set join.strategy=inner or join.strategy=outer in config.");
    }
}
