package com.chua.common.support.network.server.filter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 名单 + 限流过滤器的纯逻辑测试（不起任何服务）。
 *
 * <p>验证点：黑/白名单判定、空名单不拦截、令牌桶在 qps=1/burst=1 时第二次即拒绝。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class FirewallServerFilterTest {

    /**
     * 构造一个只有参数表的配置桩。
     *
     * @param params 参数
     * @return 配置桩
     */
    private static ServerFilterConfig config(Map<String, String> params) {
        return new ServerFilterConfig() {
            @Override
            public String getFilterName() {
                return "test";
            }

            @Override
            public String getInitParameter(String name) {
                return params.get(name);
            }

            @Override
            public Map<String, String> getInitParameters() {
                return params;
            }

            @Override
            public com.chua.common.support.network.server.ServerSetting getServerSetting() {
                return null;
            }
        };
    }

    /**
     * 黑名单命中应拒绝，未命中放行。
     */
    @Test
    void blacklistBlocksListedIp() {
        FirewallServerFilter filter = new FirewallServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("mode", "blacklist");
        params.put("ips", "10.0.0.1, 10.0.0.2");
        filter.init(config(params));

        Assertions.assertFalse(filter.allows("10.0.0.1"));
        Assertions.assertFalse(filter.allows("10.0.0.2"));
        Assertions.assertTrue(filter.allows("10.0.0.3"));
    }

    /**
     * 白名单只放行列表内 IP。
     */
    @Test
    void whitelistOnlyAllowsListedIp() {
        FirewallServerFilter filter = new FirewallServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("mode", "whitelist");
        params.put("ips", "192.168.1.5");
        filter.init(config(params));

        Assertions.assertTrue(filter.allows("192.168.1.5"));
        Assertions.assertFalse(filter.allows("192.168.1.6"));
    }

    /**
     * 名单为空时不拦截。
     */
    @Test
    void emptyListAllowsEverything() {
        FirewallServerFilter filter = new FirewallServerFilter();
        filter.init(config(new HashMap<>()));

        Assertions.assertTrue(filter.allows("1.2.3.4"));
        Assertions.assertEquals(0, filter.supportProtocols().length, "空协议数组=全部协议");
    }

    /**
     * qps=1 / burst=1：第二次立刻被限流。
     */
    @Test
    void rateLimitRejectsSecondCall() {
        FirewallServerFilter filter = new FirewallServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("mode", "blacklist");
        params.put("ips", "");
        params.put("qps", "1");
        params.put("burst", "1");
        filter.init(config(params));

        Assertions.assertTrue(filter.allows("10.0.0.9"), "首个请求应放行");
        Assertions.assertFalse(filter.allows("10.0.0.9"), "同一秒内第二个请求应被限流");
    }

    /**
     * IpServerFilter 的名单语义（与防火墙共用同一判定逻辑）。
     */
    @Test
    void ipFilterKeepsSameSemantics() {
        IpServerFilter filter = new IpServerFilter();
        Map<String, String> params = new HashMap<>();
        params.put("mode", "blacklist");
        params.put("ips", "127.0.0.1");
        filter.init(config(params));

        Assertions.assertFalse(List.of(filter.allows("127.0.0.1")).get(0));
        Assertions.assertTrue(filter.allows("127.0.0.2"));
        Assertions.assertEquals(0, filter.supportProtocols().length);
    }
}
