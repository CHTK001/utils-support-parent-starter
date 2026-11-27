// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.CollectionUtils;

/**
 * 熔断过滤器。
 *
 * <p>在请求进入下游前做「熔断判定」：连续失败达到阈值后打开熔断器，后续请求不再
 * 转发、直接快速失败；窗口过后进入半开，试探性放行一个请求，成功则闭合、失败再打开。
 * 与现有限流、超时一起构成完整的高可用语义。</p>
 *
 * <p>参数：{@code failureThreshold}（连续失败次数，默认 5）、{@code openMs}（打开窗口毫秒，
 * 默认 10_000）、{@code ignoreStatuses}（逗号分隔的"不算失败"状态码，默认空）。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
@Spi("circuit-breaker")
@SpiDescribe(value = "熔断过滤器",
        desc = "连续失败达到阈值后快速失败（打开→半开→闭合），下游不可用时避免雪崩",
        optional = {
                @SpiParam(value = "failureThreshold", defaultValue = "5",
                        desc = "连续失败多少次后打开熔断器", type = "number"),
                @SpiParam(value = "openMs", defaultValue = "10000",
                        desc = "熔断打开窗口毫秒数", type = "number"),
                @SpiParam(value = "ignoreStatuses", defaultValue = "",
                        desc = "不算失败的响应状态码，逗号分隔，如 404,429", type = "string")
        })
public class CircuitBreakerServerFilter implements ServerFilter {

    /**
     * 连续失败阈值
     */
    private int failureThreshold = 5;
    /**
     * 打开窗口（毫秒）
     */
    private long openMs = 10_000;
    /**
     * 不算失败的状态码集合
     */
    private final java.util.Set<Integer> ignoreStatuses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 每个请求路径独立的熔断状态
     */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * 无参构造：SPI 反射装配用。
     */
    public CircuitBreakerServerFilter() {
    }

    /**
     * 按配置初始化。
     *
     * <p>参数缺失时保留当前值：服务器启动阶段会以空配置对已初始化的过滤器二次 init
     * （{@code AbstractServer.start()} → {@code initFilters(SimpleServerFilterConfig)}），
     * 条件赋值保证节点级参数（failureThreshold/openMs 等）不被空配置抹除。</p>
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        applyParams(config.getInitParameter("failureThreshold"), config.getInitParameter("openMs"),
                config.getInitParameter("ignoreStatuses"));
    }

    /**
     * 热更新参数（不重建过滤器实例、不重启服务器）。
     *
     * @param config 新的参数键值
     */
    @Override
    public void updateConfig(Map<String, Object> config) {
        if (CollectionUtils.isEmpty(config)) {
            return;
        }
        applyParams(toStringValue(config.get("failureThreshold")), toStringValue(config.get("openMs")),
                toStringValue(config.get("ignoreStatuses")));
    }

    /**
     * 提取参数的字符串值，null 保持为 null（与「参数缺失」同义）。
     *
     * @param value 原始值
     * @return 字符串值
     */
    private static String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 应用参数：非空才覆盖，避免空配置二次初始化时重置已生效的参数。
     *
     * @param threshold 连续失败阈值，可为 null
     * @param openMs    打开窗口毫秒，可为 null
     * @param statuses  忽略的状态码列表，可为 null（null 表示保持原集合）
     */
    private void applyParams(String threshold, String openMs, String statuses) {
        if (StringUtils.isNotBlank(threshold)) {
            failureThreshold = (int) parseLong(threshold, 5);
        }
        if (StringUtils.isNotBlank(openMs)) {
            this.openMs = parseLong(openMs, 10_000);
        }
        if (statuses != null) {
            ignoreStatuses.clear();
            if (StringUtils.isNotBlank(statuses)) {
                for (String raw : statuses.split(",")) {
                    String s = raw.trim();
                    if (!s.isEmpty()) {
                        try {
                            ignoreStatuses.add(Integer.parseInt(s));
                        } catch (NumberFormatException ignored) {
                            // 非法状态码忽略
                        }
                    }
                }
            }
        }
        states.clear();
    }

    /**
     * Do过滤：判定熔断 → 放行并记录结果 → 按结果推进状态。
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String key = request.getPath();
        State state = states.computeIfAbsent(key, k -> new State());
        if (!state.tryAcquire()) {
            response.setStatus(503);
            response.setBody("{\"error\":\"Circuit open\"}");
            response.end();
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            state.recordFailure();
            throw e;
        }
        if (!ignoreStatuses.contains(response.getStatus())) {
            if (response.getStatus() >= 500) {
                state.recordFailure();
            } else {
                state.recordSuccess();
            }
        }
    }

    /**
     * 解析数字参数。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private static long parseLong(String value, long defaultValue) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 30;
    }

    /**
     * SupportProtocols：空数组表示所有协议。
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
     * 单路径熔断状态机：闭合 → 打开 → 半开。
     */
    private final class State {
        /**
         * 当前连续失败数
         */
        private final AtomicInteger failures = new AtomicInteger();
        /**
         * 打开开始时间戳（毫秒）
         */
        private final AtomicLong openedAt = new AtomicLong();
        /**
         * 是否已打开
         */
        private volatile boolean open;
        /**
         * 半开窗口里是否已放过一个试探请求
         */
        private volatile boolean halfOpenProbed;

        /**
         * 尝试获取放行资格。
         *
         * @return 允许返回 true
         */
        boolean tryAcquire() {
            if (!open) {
                return true;
            }
            long opened = openedAt.get();
            if (System.currentTimeMillis() - opened >= openMs) {
                // 到窗口：半开，只放一个试探请求
                if (halfOpenProbed) {
                    return false;
                }
                halfOpenProbed = true;
                return true;
            }
            return false;
        }

        /**
         * 记录一次失败。
         */
        void recordFailure() {
            if (open) {
                // 半开试探失败：重新打开
                open = true;
                openedAt.set(System.currentTimeMillis());
                halfOpenProbed = false;
                return;
            }
            if (failures.incrementAndGet() >= failureThreshold) {
                open = true;
                openedAt.set(System.currentTimeMillis());
                halfOpenProbed = false;
                failures.set(0);
            }
        }

        /**
         * 记录一次成功。
         */
        void recordSuccess() {
            failures.set(0);
            if (open) {
                // 半开试探成功：闭合
                open = false;
                openedAt.set(0);
                halfOpenProbed = false;
            }
        }
    }
}