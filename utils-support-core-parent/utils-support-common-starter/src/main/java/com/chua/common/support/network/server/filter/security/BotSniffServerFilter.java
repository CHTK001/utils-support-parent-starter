// [P3C A20 豁免] A20/A30/A31: 含 HTTP 状态码/请求头名/协议文案等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter.security;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bot 嗅探过滤器（utils 服务端薄适配）。
 *
 * <p>本类只做「读请求 → 交给 {@link BotSniffGuard} → 写响应」的适配，判定规则全部在
 * 守卫里；Spring 侧 {@code BotSniffFilter} 走同一份守卫，避免两份实现。</p>
 *
 * <p>命中挑战时返回 {@code 428} + JS（{@code application/javascript}），并带
 * {@code X-Bot-Sniff-Challenge: 1} 标记；前端执行该 JS 后把指纹加密放进
 * {@value BotSniffGuard#DEFAULT_SOLVED_HEADER} 头重放同一请求，通过后响应头回写 ticket。</p>
 *
 * @author CH
 * @since 4.0.0.60
 */
@Spi("bot-sniff")
@SpiDescribe(value = "Bot 嗅探过滤器",
        desc = "未携带 ticket 的请求先做机器人信号判定，可疑请求返回 JS 挑战；解答通过后签发 ticket",
        optional = {
                @SpiParam(value = "enabled", defaultValue = "false",
                        desc = "是否启用嗅探（默认关闭）", type = "boolean"),
                @SpiParam(value = "secretKey", defaultValue = BotSniffGuard.DEFAULT_SECRET_KEY,
                        desc = "ticket 签名密钥（HMAC）", type = "string"),
                @SpiParam(value = "ttlSeconds", defaultValue = "1800",
                        desc = "ticket 有效期（秒）", type = "number"),
                @SpiParam(value = "ticketHeader", defaultValue = BotSniffGuard.DEFAULT_TICKET_HEADER,
                        desc = "ticket 请求头名", type = "string"),
                @SpiParam(value = "solvedHeader", defaultValue = BotSniffGuard.DEFAULT_SOLVED_HEADER,
                        desc = "挑战解答请求头名", type = "string"),
                @SpiParam(value = "includePaths", defaultValue = "/v2/**,/api/**",
                        desc = "需要嗅探的路径模式，逗号分隔（支持 * 与 **）", type = "text"),
                @SpiParam(value = "excludePaths", defaultValue = "",
                        desc = "豁免路径模式，逗号分隔；为空时用内置豁免（设置水合、登录、验证码）", type = "text")
        })
public class BotSniffServerFilter implements ServerFilter {

    /** 挑战响应标记头 */
    public static final String CHALLENGE_HEADER = "X-Bot-Sniff-Challenge";

    /** 挑战状态码（Precondition Required） */
    private static final int CHALLENGE_STATUS = 428;

    /** 拒绝状态码 */
    private static final int BLOCK_STATUS = 403;

    /** 判定引擎 */
    private final BotSniffGuard guard = new BotSniffGuard();

    /**
     * 无参构造：SPI 反射装配用。
     */
    public BotSniffServerFilter() {
    }

    /**
     * 按节点参数初始化。
     *
     * <p>服务器启动阶段会用空配置对已初始化的过滤器二次 init，因此这里「参数缺失保持现状」
     * （见 {@code AbstractServer.start()}），不能用默认值重置。</p>
     *
     * @param config 过滤器配置
     */
    @Override
    public void init(ServerFilterConfig config) {
        if (null == config) {
            return;
        }
        applyParams(config.getInitParameter("enabled"),
                config.getInitParameter("secretKey"),
                config.getInitParameter("ttlSeconds"),
                config.getInitParameter("ticketHeader"),
                config.getInitParameter("solvedHeader"),
                config.getInitParameter("includePaths"),
                config.getInitParameter("excludePaths"));
    }

    /**
     * 热更新参数（不重建实例、不重启服务器）。
     *
     * @param config 新的参数键值
     */
    @Override
    public void updateConfig(Map<String, Object> config) {
        if (null == config || config.isEmpty()) {
            return;
        }
        applyParams(toStringValue(config.get("enabled")),
                toStringValue(config.get("secretKey")),
                toStringValue(config.get("ttlSeconds")),
                toStringValue(config.get("ticketHeader")),
                toStringValue(config.get("solvedHeader")),
                toStringValue(config.get("includePaths")),
                toStringValue(config.get("excludePaths")));
    }

    /**
     * 执行嗅探。
     *
     * @param request  请求
     * @param response 响应
     * @param chain    过滤器链
     * @throws Exception 处理异常
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        if (!guard.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String method = null == request.getMethod() ? "GET" : request.getMethod().name();
        BotSniffGuard.SniffResult result = guard.evaluate(method, request.getPath(), headersOf(request));
        if (result.passed()) {
            if (StringUtils.isNotBlank(result.ticket())) {
                response.setHeader(guard.getTicketHeader(), result.ticket());
            }
            chain.doFilter(request, response);
            return;
        }
        if (result.challenged()) {
            response.setStatus(CHALLENGE_STATUS);
            response.setContentType("application/javascript; charset=utf-8");
            response.setHeader(CHALLENGE_HEADER, "1");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader(guard.getSolvedHeader() + "-Ttl", String.valueOf(guard.getChallengeTtlSeconds()));
            response.setBody(result.challenge());
            response.end();
            return;
        }
        response.setStatus(BLOCK_STATUS);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader(CHALLENGE_HEADER, "blocked");
        response.setBody("{\"code\":403,\"success\":false,\"message\":\""
                + escape(result.reason()) + "\"}");
        response.end();
    }

    /**
     * 提取请求头为普通 Map。
     *
     * @param request 请求
     * @return 请求头 Map，缺失时返回空 Map
     */
    private static Map<String, String> headersOf(ServerRequest request) {
        HttpHeader headers = request.getHeaders();
        return null == headers ? Map.of() : headers.toMap();
    }

    /**
     * 应用字符串参数（null 表示保持现状）。
     *
     * @param enabled       是否启用
     * @param secretKey     共享密钥
     * @param ttlSeconds    ticket 有效期（秒）
     * @param ticketHeader  ticket 头名
     * @param solvedHeader  解答头名
     * @param includePaths  需要嗅探的路径模式
     * @param excludePaths  豁免路径模式
     */
    private void applyParams(String enabled, String secretKey, String ttlSeconds,
                             String ticketHeader, String solvedHeader,
                             String includePaths, String excludePaths) {
        Boolean enabledFlag = null == enabled ? null : Boolean.valueOf(enabled.trim());
        Long ttl = null;
        if (StringUtils.isNotBlank(ttlSeconds)) {
            try {
                ttl = Long.parseLong(ttlSeconds.trim());
            } catch (NumberFormatException e) {
                ttl = null;
            }
        }
        guard.configure(enabledFlag, secretKey, ttl, ticketHeader, solvedHeader,
                splitPatterns(includePaths), splitPatterns(excludePaths));
    }

    /**
     * 拆分逗号分隔的路径模式；空白或未传返回 null（保持现状）。
     *
     * <p>显式传空串表示「清空该侧模式」，因此返回空列表而不是 null。</p>
     *
     * @param text 原始文本
     * @return 模式列表；未传返回 null
     */
    private static List<String> splitPatterns(String text) {
        if (null == text) {
            return null;
        }
        List<String> patterns = new ArrayList<>();
        for (String item : text.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }

    /**
     * 提取参数的字符串值，null 保持为 null（与「参数缺失」同义）。
     *
     * @param value 原始值
     * @return 字符串值
     */
    private static String toStringValue(Object value) {
        return null == value ? null : String.valueOf(value);
    }

    /**
     * 转义 JSON 字符串中的引号与反斜杠。
     *
     * @param text 原始文本
     * @return 转义结果
     */
    private static String escape(String text) {
        if (null == text) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 嗅探必须早于业务过滤器，且晚于 mock/日志类最前置过滤器。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 100;
    }
}
