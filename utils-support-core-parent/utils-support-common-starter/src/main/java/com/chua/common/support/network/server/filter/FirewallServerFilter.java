// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.chua.common.support.utils.StringUtils;

/**
 * 防火墙过滤器：IP 黑白名单 + 每 IP 限流。
 *
 * <p>把原来分散的「IP 拦截」和「限流」合成一个可配置插件：先判名单，再按 IP 做令牌桶。
 * HTTP 走过滤器链（403 / 429），tcp / proxy 走 {@link #allows(String)} 判定后由调用方断开或拒绝。</p>
 *
 * <p>参数：{@code mode}（blacklist/whitelist）、{@code ips}、{@code qps}（0 表示不限）、
 * {@code burst}（桶容量，默认取 qps）。</p>
 *
 * @author CH
 * @since 2026/11/13
 */
@Spi("firewall")
@SpiDescribe(value = "防火墙过滤器",
        desc = "IP 黑白名单 + 每 IP 限流；名单为空时不拦截，qps=0 表示不限流",
        optional = {
                @SpiParam(value = "mode", defaultValue = "blacklist",
                        desc = "blacklist=拒绝列表内 IP，whitelist=只允许列表内 IP", type = "string"),
                @SpiParam(value = "ips", defaultValue = "",
                        desc = "逗号或空格分隔的 IP 列表，如 10.0.0.1,10.0.0.2", type = "string"),
                @SpiParam(value = "qps", defaultValue = "0",
                        desc = "每 IP 每秒允许的请求数，0 表示不限流", type = "number"),
                @SpiParam(value = "burst", defaultValue = "0",
                        desc = "突发容量（桶大小），0 表示与 qps 相同", type = "number")
        })
public class FirewallServerFilter implements ServerFilter {

    /**
     * 白名单
     */
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();
    /**
     * 黑名单
     */
    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();
    /**
     * 每 IP 令牌桶
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    /**
     * 是否白名单模式
     */
    private boolean whitelistMode = false;
    /**
     * 每 IP 每秒配额，0 表示不限
     */
    private double qps = 0;
    /**
     * 突发容量
     */
    private int burst = 0;

    /**
     * 无参构造：SPI 反射装配用。
     */
    public FirewallServerFilter() {
    }

    /**
     * 按配置初始化。
     *
     * <p>参数缺失时保留当前值：服务器启动阶段会以空配置对已初始化的过滤器二次 init
     * （{@code AbstractServer.start()} → {@code initFilters(SimpleServerFilterConfig)}），
     * 条件赋值保证节点级参数（qps/burst 等）不被空配置抹除。</p>
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (config == null) {
            return;
        }
        applyParams(config.getInitParameter("mode"), config.getInitParameter("ips"),
                config.getInitParameter("qps"), config.getInitParameter("burst"));
    }

    /**
     * 热更新参数（不重建过滤器实例、不重启服务器）。
     *
     * @param config 新的参数键值
     */
    @Override
    public void updateConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        applyParams(toStringValue(config.get("mode")), toStringValue(config.get("ips")),
                toStringValue(config.get("qps")), toStringValue(config.get("burst")));
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
     * 应用参数：除 {@code ips} 显式传空串表示清空名单外，其余参数非空才覆盖，
     * 避免空配置二次初始化时重置已生效的参数。
     *
     * @param mode  模式（blacklist/whitelist），可为 null
     * @param ips   IP 列表，可为 null（null 表示保持原名单）
     * @param qps   每秒配额，可为 null
     * @param burst 突发容量，可为 null
     */
    private void applyParams(String mode, String ips, String qps, String burst) {
        if (StringUtils.isNotBlank(mode)) {
            whitelistMode = "whitelist".equalsIgnoreCase(mode.trim());
        }
        if (ips != null) {
            whitelist.clear();
            blacklist.clear();
            if (StringUtils.isNotBlank(ips)) {
                for (String raw : ips.split("[,;\\s]+")) {
                    String ip = raw.trim();
                    if (ip.isEmpty()) {
                        continue;
                    }
                    if (whitelistMode) {
                        whitelist.add(ip);
                    } else {
                        blacklist.add(ip);
                    }
                }
            }
        }
        if (StringUtils.isNotBlank(qps)) {
            this.qps = parseDouble(qps, 0);
        }
        if (StringUtils.isNotBlank(burst)) {
            this.burst = (int) parseDouble(burst, 0);
        }
        if (this.burst <= 0 && this.qps > 0) {
            this.burst = Math.max(1, (int) Math.ceil(this.qps));
        }
        buckets.clear();
    }

    /**
     * 判断某个客户端 IP 这一刻是否放行（名单 + 限流）。
     *
     * @param clientIp 客户端 IP
     * @return true 表示放行
     */
    public boolean allows(String clientIp) {
        String ip = clientIp == null ? "" : clientIp;
        if (deniedByList(ip)) {
            return false;
        }
        return tryAcquire(ip);
    }

    /**
     * 仅做限流判定（名单判定由调用方先行完成），避免名单重复扫描。
     *
     * @param clientIp 客户端 IP，可为 null
     * @return true 表示未触发限流
     */
    private boolean tryAcquire(String clientIp) {
        String ip = clientIp == null ? "" : clientIp;
        if (qps <= 0) {
            return true;
        }
        Bucket bucket = buckets.computeIfAbsent(ip, key -> new Bucket(qps, burst));
        return bucket.tryAcquire();
    }

    /**
     * 是否被名单拒绝（用于区分 403 与 429）。
     *
     * @param clientIp 客户端 IP
     * @return true 表示被名单拒绝
     */
    public boolean deniedByList(String clientIp) {
        String ip = clientIp == null ? "" : clientIp;
        return whitelistMode ? (!whitelist.isEmpty() && !whitelist.contains(ip)) : blacklist.contains(ip);
    }

    /**
     * Filter order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 20;
    }

    /**
     * SupportProtocols：空数组表示所有协议。
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
     * Do过滤
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String ip = IpServerFilter.extractIp(request.getRemoteAddress());
        if (deniedByList(ip)) {
            response.setStatus(403);
            response.setBody("{\"error\":\"Forbidden\",\"ip\":\"" + ip + "\"}");
            response.end();
            return;
        }
        if (!tryAcquire(ip)) {
            response.setStatus(429);
            response.setBody("{\"error\":\"Too Many Requests\",\"ip\":\"" + ip + "\"}");
            response.end();
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 解析数字参数，非法值回落默认值。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private static double parseDouble(String value, double defaultValue) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 最简单的令牌桶：按纳秒时间差补充令牌，容量封顶 burst。
     */
    private static final class Bucket {
        /**
         * 每秒补充的令牌数
         */
        private final double rate;
        /**
         * 桶容量
         */
        private final double capacity;
        /**
         * 当前令牌数
         */
        private double tokens;
        /**
         * 上次补充时间（纳秒）
         */
        private long lastRefill;

        /**
         * 创建令牌桶。
         *
         * @param rate     每秒令牌
         * @param capacity 容量
         */
        Bucket(double rate, int capacity) {
            this.rate = rate;
            this.capacity = Math.max(1, capacity);
            this.tokens = this.capacity;
            this.lastRefill = System.nanoTime();
        }

        /**
         * 尝试取一个令牌。
         *
         * @return true 表示可以放行
         */
        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double gained = (now - lastRefill) / 1_000_000_000.0 * rate;
            if (gained > 0) {
                tokens = Math.min(capacity, tokens + gained);
                lastRefill = now;
            }
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
