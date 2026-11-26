// [P3C A20 豁免] A20/A30/A31: 含 JS 模板/协议常量/HTTP 状态码等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter.security;

import com.chua.common.support.lang.algorithm.crypto.Codec;
import com.chua.common.support.utils.DetectorUtils;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bot 嗅探守卫（核心判定引擎，全仓唯一实现）。
 *
 * <p>职责：提供「嗅探（bot 挑战）」的判定能力，且不依赖 Spring / Servlet。
 * utils 的 {@link BotSniffServerFilter} 与 spring-common 的 servlet 过滤器都只是
 * 薄适配层，判定规则全部集中在本类，避免同一能力出现两份实现（S0）。</p>
 *
 * <h2>两段式协议</h2>
 * <ol>
 *   <li><b>第一段（简单判定）</b>：请求既无 ticket 也无挑战解答时，先用
 *       {@link DetectorUtils#analyzeBotSignals} 做一次「身份类不可伪造信号」判定
 *       （缺 UA / bot UA / 无头 UA / bot TLS 指纹）。命中 → {@link Decision#BLOCK}；
 *       否则下发 {@link Decision#CHALLENGE}，响应体携带一段内嵌一次性随机数的 JS。
 *       <p><b>口径</b>：「缺少标准请求头」不计入第一段拦截——它只说明参数不全
 *       （HTTP 客户端、老浏览器、WebView 都可能缺），正是要靠挑战把浏览器指纹采集回来
 *       再做二次判定的场景，不能在这里直接拒。</p></li>
 *   <li><b>第二段（挑战解答）</b>：前端执行该 JS 采集浏览器指纹，用与 codec 相同的
 *       AES 线协议加密后放进解答头，重放<b>同一条业务请求</b>。本类解密后交给
 *       {@link DetectorUtils#analyzeRequest}（含客户端探针维度）二次判定：
 *       综合判定为 BOT，或客户端自报 {@code navigator.webdriver / CDP} 为真
 *       （真实浏览器永不为真）→ {@link Decision#BLOCK}；否则签发 ticket 并放行。</li>
 * </ol>
 *
 * <p>后续请求携带 ticket（{@code 随机数.过期毫秒.HMAC}，无状态校验）直接放行。</p>
 *
 * <h2>线协议约定（与前端 {@code packages/core/src/botSniff.ts} 对齐）</h2>
 * <ul>
 *   <li>ticket 头默认 {@value #DEFAULT_TICKET_HEADER}，值为 {@code nonce.expireAt.hmac}。</li>
 *   <li>解答头默认 {@value #DEFAULT_SOLVED_HEADER}，值为 {@code 随机数.密文hex}：密文以本次挑战的
 *       一次性随机数作为 codec 密钥（{@code AES-CBC(key=SHA256(随机数), iv=digest 前 16 字节)}），
 *       明文是挑战 JS 返回的 JSON：{@code {"n":随机数,"t":时间戳,"d":{浏览器指纹}}}。
 *       随机数一经消费即失效，重放无法通过，因此无需把共享密钥下发到浏览器。</li>
 *   <li>挑战 JS 是一个<b>表达式</b>（IIFE），求值结果即上述 JSON 字符串。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.60
 */
public class BotSniffGuard {

    /** 默认 ticket 请求头 */
    public static final String DEFAULT_TICKET_HEADER = "X-Bot-Sniff-Ticket";

    /** 默认挑战解答请求头 */
    public static final String DEFAULT_SOLVED_HEADER = "X-Bot-Sniff-Solved";

    /** 默认共享密钥（仅用于 ticket 的 HMAC 签名；解答密文改用每次挑战的一次性随机数作密钥） */
    public static final String DEFAULT_SECRET_KEY = "0123456789abcdef";

    /** 默认挑战有效期（毫秒） */
    public static final long DEFAULT_CHALLENGE_TTL_MILLIS = 5 * 60 * 1000L;

    /** 默认 ticket 有效期（毫秒） */
    public static final long DEFAULT_TICKET_TTL_MILLIS = 30 * 60 * 1000L;

    /** 挑战随机数缓存上限 */
    private static final int NONCE_CACHE_MAX = 20000;

    /** HMAC 算法 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** ticket 分段分隔符 */
    private static final char TICKET_SEPARATOR = '.';

    /** ticket 段数 */
    private static final int TICKET_SEGMENTS = 3;

    /** 挑战解答分段分隔符（随机数.密文hex） */
    private static final char SOLVED_SEPARATOR = '.';

    /** 挑战解答段数 */
    private static final int SOLVED_SEGMENTS = 2;

    /** JSON 解析器（线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 随机数发生器 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 十六进制编解码 */
    private static final HexFormat HEX = HexFormat.of();

    /** 判定结论 */
    public enum Decision {
        /** 放行 */
        PASS,
        /** 需要前端执行挑战后重放 */
        CHALLENGE,
        /** 判定为机器人，直接拒绝 */
        BLOCK
    }

    /**
     * 判定结果。
     *
     * @param decision  结论
     * @param reason    判定原因（可落日志）
     * @param challenge 挑战 JS（仅 {@link Decision#CHALLENGE} 非空）
     * @param ticket    新签发的 ticket（仅解答通过时非空，适配层负责回写响应头）
     * @param verdict   {@link DetectorUtils} 给出的判定档位名
     * @param score     启发式得分
     */
    public record SniffResult(Decision decision, String reason, String challenge,
                              String ticket, String verdict, int score) {

        /**
         * 构造放行结果。
         *
         * @param reason  原因
         * @param verdict 判定档位
         * @return 放行结果
         */
        public static SniffResult pass(String reason, String verdict) {
            return new SniffResult(Decision.PASS, reason, null, null, verdict, 0);
        }

        /**
         * 构造放行结果（携带新签发的 ticket）。
         *
         * @param reason  原因
         * @param ticket  ticket
         * @param verdict 判定档位
         * @param score   得分
         * @return 放行结果
         */
        public static SniffResult passWithTicket(String reason, String ticket, String verdict, int score) {
            return new SniffResult(Decision.PASS, reason, null, ticket, verdict, score);
        }

        /**
         * 构造挑战结果。
         *
         * @param challenge 挑战 JS
         * @return 挑战结果
         */
        public static SniffResult challenge(String challenge) {
            return new SniffResult(Decision.CHALLENGE, "需要执行挑战", challenge, null, "UNKNOWN", 0);
        }

        /**
         * 构造拒绝结果。
         *
         * @param reason  原因
         * @param verdict 判定档位
         * @param score   得分
         * @return 拒绝结果
         */
        public static SniffResult block(String reason, String verdict, int score) {
            return new SniffResult(Decision.BLOCK, reason, null, null, verdict, score);
        }

        /**
         * 是否放行。
         *
         * @return 放行返回 true
         */
        public boolean passed() {
            return Decision.PASS == decision;
        }

        /**
         * 是否需要前端执行挑战。
         *
         * @return 需要挑战返回 true
         */
        public boolean challenged() {
            return Decision.CHALLENGE == decision;
        }

        /**
         * 是否被直接拒绝。
         *
         * @return 拒绝返回 true
         */
        public boolean blocked() {
            return Decision.BLOCK == decision;
        }
    }

    /** 是否启用 */
    private volatile boolean enabled;
    /** ticket 请求头 */
    private volatile String ticketHeader = DEFAULT_TICKET_HEADER;
    /** 解答请求头 */
    private volatile String solvedHeader = DEFAULT_SOLVED_HEADER;
    /** 共享密钥 */
    private volatile String secretKey = DEFAULT_SECRET_KEY;
    /** 挑战有效期 */
    private volatile long challengeTtlMillis = DEFAULT_CHALLENGE_TTL_MILLIS;
    /** ticket 有效期 */
    private volatile long ticketTtlMillis = DEFAULT_TICKET_TTL_MILLIS;
    /** 需要嗅探的路径模式 */
    private volatile List<String> includePatterns = List.of("/v2/**", "/api/**");
    /** 豁免嗅探的路径模式 */
    private volatile List<String> excludePatterns = defaultExcludePatterns();

    /** 挑战随机数 → 过期时间 */
    private final Map<String, Long> challengeNonces = new ConcurrentHashMap<>();

    /**
     * 默认豁免路径：设置水合通道（前端靠它读取开关本身）与登录/登出。
     *
     * @return 豁免模式
     */
    private static List<String> defaultExcludePatterns() {
        List<String> patterns = new ArrayList<>();
        patterns.add("/v2/setting/**");
        patterns.add("/v2/user/login");
        patterns.add("/v2/user/logout");
        patterns.add("/v2/captcha/**");
        return Collections.unmodifiableList(patterns);
    }

    /**
     * 应用配置（幂等：null 保持现状，空串按各自语义回落）。
     *
     * <p>与框架「两段式 init」对齐：首次以描述符参数 init，随后 {@code AbstractServer.start()}
     * 会用空参数<b>再 init 一次</b>，因此这里必须「参数存在才覆盖」，不能用默认值重置。</p>
     *
     * @param enabledFlag  是否启用；null 保持现状
     * @param key          共享密钥；null 保持现状，空白回落默认密钥
     * @param ttlSeconds   ticket 有效期（秒）；null 或非正数保持现状
     * @param ticketHeaderName ticket 头名；null/空白保持现状
     * @param solvedHeaderName 解答头名；null/空白保持现状
     * @param include      需要嗅探的路径模式；null 保持现状
     * @param exclude      豁免路径模式；null 保持现状
     */
    public synchronized void configure(Boolean enabledFlag, String key, Long ttlSeconds,
                                       String ticketHeaderName, String solvedHeaderName,
                                       List<String> include, List<String> exclude) {
        if (null != enabledFlag) {
            this.enabled = enabledFlag;
        }
        if (null != key) {
            String next = key.trim().isEmpty() ? DEFAULT_SECRET_KEY : key.trim();
            if (!next.equals(this.secretKey)) {
                this.secretKey = next;
            }
        }
        if (null != ttlSeconds && ttlSeconds > 0) {
            this.ticketTtlMillis = ttlSeconds * 1000L;
        }
        if (StringUtils.isNotBlank(ticketHeaderName)) {
            this.ticketHeader = ticketHeaderName.trim();
        }
        if (StringUtils.isNotBlank(solvedHeaderName)) {
            this.solvedHeader = solvedHeaderName.trim();
        }
        if (null != include) {
            this.includePatterns = List.copyOf(include);
        }
        if (null != exclude) {
            this.excludePatterns = List.copyOf(exclude);
        }
    }

    /**
     * 是否启用。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * ticket 请求头名。
     *
     * @return 头名
     */
    public String getTicketHeader() {
        return ticketHeader;
    }

    /**
     * 解答请求头名。
     *
     * @return 头名
     */
    public String getSolvedHeader() {
        return solvedHeader;
    }

    /**
     * 挑战有效期（秒），供适配层写入响应头让前端缓存。
     *
     * @return 秒
     */
    public long getChallengeTtlSeconds() {
        return challengeTtlMillis / 1000L;
    }

    /**
     * 执行一次嗅探判定。
     *
     * @param method  请求方法（OPTIONS 预检直接放行）
     * @param path    请求路径（不含查询串）
     * @param headers 请求头（大小写不敏感，调用方可直接传原始值）
     * @return 判定结果，永不为 null
     */
    public SniffResult evaluate(String method, String path, Map<String, String> headers) {
        if (!enabled) {
            return SniffResult.pass("嗅探未启用", "HUMAN");
        }
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return SniffResult.pass("CORS 预检", "HUMAN");
        }
        if (!matchesAny(includePatterns, path) || matchesAny(excludePatterns, path)) {
            return SniffResult.pass("路径豁免", "HUMAN");
        }

        Map<String, String> safeHeaders = caseInsensitive(headers);

        String ticket = headerValue(safeHeaders, ticketHeader);
        if (validateTicket(ticket)) {
            return SniffResult.pass("ticket 校验通过", "HUMAN");
        }

        String solved = headerValue(safeHeaders, solvedHeader);
        if (StringUtils.isNotBlank(solved)) {
            return solve(solved, path, safeHeaders);
        }

        DetectorUtils.BotAnalysis analysis = DetectorUtils.analyzeBotSignals(safeHeaders, null);
        DetectorUtils.BotAnalysisDetails analysisDetails = analysis.details();
        // 第一段只对「身份类」不可伪造信号直接拒绝：缺 UA / bot UA / 无头 UA / bot TLS 指纹。
        // 「缺少现代浏览器标准请求头」只是参数不全（HTTP 客户端、老版本浏览器、WebView 都可能缺），
        // 恰恰是应当下发挑战、把浏览器指纹采集回来做二次判定的场景，不能直接拒。
        boolean identityBot = "missing".equals(analysisDetails.uaType())
                || analysisDetails.uaBotMatch()
                || analysisDetails.uaHeadlessMatch()
                || analysisDetails.tlsBotMatch();
        if (identityBot) {
            return SniffResult.block("第一段嗅探命中机器人身份信号: " + analysis.verdict().label(),
                    analysis.verdict().name(), analysisDetails.botScore());
        }
        if (DetectorUtils.BotVerdict.BOT == analysis.verdict()) {
            return SniffResult.block("第一段嗅探综合判定为机器人: " + analysis.verdict().label(),
                    analysis.verdict().name(), analysisDetails.botScore());
        }
        return SniffResult.challenge(renderChallenge(issueNonce()));
    }

    /**
     * 解密并校验挑战解答。
     *
     * <p>解答头格式为 {@code 随机数.密文hex}：随机数先取出当作本次解密的 codec 密钥，
     * 再从一次性缓存中消费掉它（消费即失效，重放必失败）。</p>
     *
     * @param solved  加密解答（{@code 随机数.密文hex}）
     * @param path    请求路径
     * @param headers 请求头
     * @return 判定结果
     */
    private SniffResult solve(String solved, String path, Map<String, String> headers) {
        String[] parts = solved.split("\\" + SOLVED_SEPARATOR, SOLVED_SEGMENTS);
        if (parts.length != SOLVED_SEGMENTS || StringUtils.isBlank(parts[1])) {
            return SniffResult.block("挑战解答格式无效", "BOT", 0);
        }
        String nonce = parts[0];
        if (!consumeNonce(nonce)) {
            return SniffResult.block("挑战随机数无效或已过期", "BOT", 0);
        }
        Map<String, Object> payload;
        try {
            payload = readPayload(parts[1], nonce, path);
        } catch (Exception e) {
            return SniffResult.block("挑战解答无效: " + e.getMessage(), "BOT", 0);
        }
        DetectorUtils.ClientProbeData probe = toProbe(asMap(payload.get("d")));
        DetectorUtils.RequestAnalysis analysis = DetectorUtils.analyzeRequest(headers, null, path, probe);
        // 第二段的两条拦截依据：
        // 1. DetectorUtils 综合判定为 BOT（分数 ≥ 50）；
        // 2. 客户端自报 navigator.webdriver / CDP 为真——真实浏览器永远不会为真，
        //    自动化框架不 patch 就会自证，patch 掉又会同时丢失 plugins/chrome 运行时等特征。
        //    单看分数不够：webdriver=true 只计 30 分，落在 SUSPICIOUS 档，会放过 Playwright。
        boolean selfReportedAutomation = Boolean.TRUE.equals(probe.webdriver());
        if (selfReportedAutomation || DetectorUtils.BotVerdict.BOT == analysis.verdict()) {
            String reason = selfReportedAutomation
                    ? "第二段嗅探命中自动化浏览器标志（navigator.webdriver / CDP）"
                    : "第二段嗅探命中自动化浏览器特征: " + analysis.verdict().label();
            return SniffResult.block(reason, analysis.verdict().name(), analysis.confidence());
        }
        return SniffResult.passWithTicket("挑战通过", issueTicket(nonce),
                analysis.verdict().name(), analysis.confidence());
    }

    /**
     * 解密解答载荷，并校验其声明的随机数与路径。
     *
     * <p>密钥就是本次挑战的一次性随机数（前端从挑战 JS 的返回值里取得），
     * 因此不需要把共享密钥下发到浏览器；防重放完全依赖随机数一次性消费。</p>
     *
     * @param cipherHex 十六进制密文
     * @param nonce     本次挑战的一次性随机数
     * @param path      请求路径
     * @return 载荷
     * @throws Exception 解密/解析失败
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String cipherHex, String nonce, String path) throws Exception {
        String plain = Codec.build("aes", nonce).decodeHex(cipherHex);
        if (StringUtils.isBlank(plain)) {
            throw new IllegalArgumentException("解答为空");
        }
        Map<String, Object> payload = MAPPER.readValue(plain, Map.class);
        if (!nonce.equals(asString(payload.get("n")))) {
            throw new IllegalArgumentException("解答随机数不匹配");
        }
        String payloadPath = asString(payload.get("p"));
        if (StringUtils.isNotBlank(payloadPath) && !payloadPath.equals(path)) {
            throw new IllegalArgumentException("解答路径不匹配");
        }
        return payload;
    }

    /**
     * 生成挑战 JS（内嵌一次性随机数），求值结果是探针 JSON 字符串。
     *
     * @param nonce 随机数
     * @return 可直接求值的 JS 表达式
     */
    private String renderChallenge(String nonce) {
        return CHALLENGE_JS_PREFIX + nonce + CHALLENGE_JS_SUFFIX;
    }

    /**
     * 把客户端探针 JSON 转为 {@link DetectorUtils.ClientProbeData}。
     *
     * <p>CDP 检出被并入 {@code webdriver}：两者都表示「浏览器被自动化协议接管」。</p>
     *
     * @param probe 探针原始 map
     * @return 探针数据
     */
    private DetectorUtils.ClientProbeData toProbe(Map<String, Object> probe) {
        if (probe.isEmpty()) {
            return DetectorUtils.ClientProbeData.empty();
        }
        boolean webdriver = asBool(probe.get("webdriver")) || asBool(probe.get("cdp"));
        return new DetectorUtils.ClientProbeData(
                webdriver,
                asInt(probe.get("plugins")),
                asString(probe.get("languages")),
                asString(probe.get("timezone")),
                asString(probe.get("platform")),
                asInt(probe.get("hardwareConcurrency")),
                asDouble(probe.get("devicePixelRatio"), 1.0),
                asInt(probe.get("screenWidth")),
                asInt(probe.get("screenHeight")),
                asBool(probe.get("hasChromeRuntime")),
                asString(probe.get("webGlVendor")),
                asString(probe.get("webGlRenderer")),
                asString(probe.get("canvasHash"))
        );
    }

    /**
     * 分发一个一次性随机数。
     *
     * @return 随机数
     */
    private String issueNonce() {
        if (challengeNonces.size() >= NONCE_CACHE_MAX) {
            purgeExpiredNonces();
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String nonce = HEX.formatHex(bytes);
        challengeNonces.put(nonce, System.currentTimeMillis() + challengeTtlMillis);
        return nonce;
    }

    /**
     * 消费随机数（一次性，防重放）。
     *
     * @param nonce 随机数
     * @return 是否有效
     */
    private boolean consumeNonce(String nonce) {
        if (StringUtils.isBlank(nonce)) {
            return false;
        }
        Long expireAt = challengeNonces.remove(nonce);
        return null != expireAt && expireAt >= System.currentTimeMillis();
    }

    /**
     * 清理过期随机数，避免缓存无界增长。
     */
    private void purgeExpiredNonces() {
        long now = System.currentTimeMillis();
        challengeNonces.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    /**
     * 签发 ticket：{@code 随机数.过期毫秒.HMAC(随机数|过期毫秒)}。
     *
     * @param nonce 随机数
     * @return ticket
     */
    private String issueTicket(String nonce) {
        long expireAt = System.currentTimeMillis() + ticketTtlMillis;
        return nonce + TICKET_SEPARATOR + expireAt + TICKET_SEPARATOR + hmac(nonce + '|' + expireAt);
    }

    /**
     * 校验 ticket（无状态：分段结构 + 过期时间 + HMAC 常量时间比较）。
     *
     * @param ticket ticket
     * @return 是否有效
     */
    public boolean validateTicket(String ticket) {
        if (StringUtils.isBlank(ticket)) {
            return false;
        }
        String[] parts = ticket.split("\\" + TICKET_SEPARATOR);
        if (parts.length != TICKET_SEGMENTS) {
            return false;
        }
        String nonce = parts[0];
        if (StringUtils.isBlank(nonce) || StringUtils.isBlank(parts[2])) {
            return false;
        }
        long expireAt;
        try {
            expireAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            return false;
        }
        return safeEquals(hmac(nonce + '|' + expireAt), parts[2]);
    }

    /**
     * 计算 HMAC-SHA256 的十六进制形式。
     *
     * @param material 原始材料
     * @return 十六进制签名
     */
    private String hmac(String material) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HEX.formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取请求头（大小写不敏感）。
     *
     * @param headers 请求头
     * @param name    头名
     * @return 头值，不存在返回 null
     */
    private static String headerValue(Map<String, String> headers, String name) {
        String direct = headers.get(name);
        if (null != direct) {
            return direct;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (null != entry.getKey() && entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 构造大小写不敏感的请求头副本（{@link DetectorUtils} 部分分支按原样键名取值）。
     *
     * @param headers 原始请求头
     * @return 大小写不敏感副本
     */
    private static Map<String, String> caseInsensitive(Map<String, String> headers) {
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (null != headers) {
            copy.putAll(headers);
        }
        return copy;
    }

    /**
     * 路径模式匹配：精确 / 单段通配（{@code *}）/ 任意深度（{@code **}）。
     *
     * @param patterns 模式列表
     * @param path     请求路径
     * @return 是否命中
     */
    static boolean matchesAny(List<String> patterns, String path) {
        if (null == patterns || patterns.isEmpty() || StringUtils.isBlank(path)) {
            return false;
        }
        for (String pattern : patterns) {
            if (match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单条模式匹配。
     *
     * @param pattern 模式
     * @param path    路径
     * @return 是否命中
     */
    static boolean match(String pattern, String path) {
        if (StringUtils.isBlank(pattern)) {
            return false;
        }
        if (pattern.equals(path)) {
            return true;
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix);
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return path.startsWith(prefix) && !path.substring(prefix.length()).contains("/");
        }
        return false;
    }

    /**
     * 把任意值转字符串。
     *
     * @param value 原值
     * @return 字符串，null 返回 null
     */
    private static String asString(Object value) {
        return null == value ? null : String.valueOf(value);
    }

    /**
     * 把任意值转布尔。
     *
     * @param value 原值
     * @return 布尔值
     */
    private static boolean asBool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return null != value && Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 把任意值转整数。
     *
     * @param value 原值
     * @return 整数，无法解析返回 0
     */
    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return null == value ? 0 : (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 把任意值转浮点。
     *
     * @param value        原值
     * @param defaultValue 默认值
     * @return 浮点数
     */
    private static double asDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return null == value ? defaultValue : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 把任意值转 Map。
     *
     * @param value 原值
     * @return Map，无法转换返回空 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new HashMap<>();
    }

    /**
     * 常量时间字符串比较。
     *
     * @param left  左值
     * @param right 右值
     * @return 是否相等
     */
    static boolean safeEquals(String left, String right) {
        return null != left && null != right
                && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    /** 挑战 JS 前缀（后接一次性随机数） */
    private static final String CHALLENGE_JS_PREFIX = "(function(){var n=\"";

    /** 挑战 JS 主体：采集指纹 + CDP 探测，返回 JSON 字符串 */
    private static final String CHALLENGE_JS_SUFFIX =
            "\";var d={};"
                    + "try{d.webdriver=navigator.webdriver===true}catch(e){d.webdriver=false}"
                    + "try{d.plugins=(navigator.plugins||[]).length}catch(e){d.plugins=0}"
                    + "try{d.languages=(navigator.languages||[navigator.language||\"\"]).join(\",\")}catch(e){d.languages=\"\"}"
                    + "try{d.timezone=(Intl.DateTimeFormat().resolvedOptions().timeZone||\"\")}catch(e){d.timezone=\"\"}"
                    + "try{d.platform=navigator.platform||\"\"}catch(e){d.platform=\"\"}"
                    + "try{d.hardwareConcurrency=navigator.hardwareConcurrency||0}catch(e){d.hardwareConcurrency=0}"
                    + "try{d.devicePixelRatio=window.devicePixelRatio||1}catch(e){d.devicePixelRatio=1}"
                    + "try{d.screenWidth=screen.width||0}catch(e){d.screenWidth=0}"
                    + "try{d.screenHeight=screen.height||0}catch(e){d.screenHeight=0}"
                    + "try{d.hasChromeRuntime=typeof window.chrome===\"object\"&&window.chrome!==null}catch(e){d.hasChromeRuntime=false}"
                    + "try{var c=document.createElement(\"canvas\");"
                    + "var g=c.getContext(\"webgl\")||c.getContext(\"experimental-webgl\");"
                    + "var x=g&&g.getExtension(\"WEBGL_debug_renderer_info\");"
                    + "d.webGlVendor=x?String(g.getParameter(x.UNMASKED_VENDOR_WEBGL)||\"\"):\"\";"
                    + "d.webGlRenderer=x?String(g.getParameter(x.UNMASKED_RENDERER_WEBGL)||\"\"):\"\";"
                    + "c.width=120;c.height=30;var ctx=c.getContext(\"2d\");if(ctx){ctx.textBaseline=\"top\";"
                    + "ctx.font=\"14px Arial\";ctx.fillStyle=\"#f60\";ctx.fillRect(0,0,120,30);"
                    + "ctx.fillStyle=\"#069\";ctx.fillText(\"bot-sniff\",4,8);"
                    + "d.canvasHash=c.toDataURL().slice(-32)}else{d.canvasHash=\"\"}}"
                    + "catch(e){d.webGlVendor=\"\";d.webGlRenderer=\"\";d.canvasHash=\"\"}"
                    + "d.cdp=(function(){try{"
                    + "if(window.callPhantom||window._phantom||window.__nightmare||window.domAutomation"
                    + "||window.__webdriver_evaluate||window.__selenium_evaluate)return true;"
                    + "for(var k in window){if(k.indexOf(\"$cdc_\")===0||k.indexOf(\"$wdc_\")===0)return true}"
                    + "}catch(e){}return false})();"
                    + "return JSON.stringify({n:n,t:Date.now(),d:d})})()";
}
