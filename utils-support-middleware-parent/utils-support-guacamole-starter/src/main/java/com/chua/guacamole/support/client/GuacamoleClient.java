package com.chua.guacamole.support.client;

import com.chua.common.support.value.ExpireValue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guacamole 远控链式客户端（短链模式）。
 *
 * <p>封装 Apache Guacamole 的部署参数模型：guacd 协议代理地址（默认本机
 * {@code 127.0.0.1:4822}）与 Guacamole Web 前端地址（默认本机
 * {@code 127.0.0.1:8080/guacamole}）。敏感连接参数（主机、账号、密码）不写入 URL，
 * 而是由客户端内部以短链形式维持：{@link #issue()} 操作链登记一组远控参数，
 * 产出一个随机短 token；{@link #url(String)} 仅携带该短 token 生成会话 URL；
 * {@link #resolve(String)} 反向映射回完整连接参数。</p>
 *
 * <p>所有短 token 的有效期由 {@link ExpireValue} 统一维护：到期后取值自动清除，
 * 调用方需重新 {@code issue()} 获取新 token；也可 {@link #refreshTokens()} 手动续期。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * GuacamoleClient client = GuacamoleClient.builder()
 *     .guacdHost("127.0.0.1").guacdPort(4822)
 *     .tokenTtl(Duration.ofMinutes(30))
 *     .build();
 *
 * // 登记 RDP 连接参数，签发短 token
 * String token = client.rdp()
 *     .host("192.168.1.10")
 *     .port(3389)
 *     .username("admin")
 *     .password("secret")
 *     .param("rdp-disable-copy", "true")
 *     .issue();
 *
 * // 生成仅含短 token 的会话 URL（无敏感参数）
 * String url = client.url(token);
 *
 * // 反向解析出完整连接参数
 * Optional<GuacamoleClient.RemoteSpec> spec = client.resolve(token);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://guacamole.apache.org/doc/guides/user.html">Apache Guacamole 官方文档</a>
 */
@Slf4j
public class GuacamoleClient {

    /** 默认 guacd 服务器地址（本机） */
    private static final String DEFAULT_GUACD_HOST = "127.0.0.1";
    /** 默认 guacd 服务器端口 */
    private static final int DEFAULT_GUACD_PORT = 4822;
    /** 默认 Web 前端地址（本机） */
    private static final String DEFAULT_WEB_HOST = "127.0.0.1";
    /** 默认 Web 前端端口 */
    private static final int DEFAULT_WEB_PORT = 8080;
    /** 默认 Web 上下文路径 */
    private static final String DEFAULT_CONTEXT_PATH = "/guacamole";
    /** 默认短 token 有效期（1 小时） */
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(1);
    /** 短链 fragment 参数名 */
    private static final String TOKEN_FRAGMENT_PARAM = "token";
    /** token 随机字节数（16 位十六进制字符） */
    private static final int TOKEN_BYTES = 8;
    /** token 冲突重试上限 */
    private static final int TOKEN_MAX_RETRY = 3;
    /** 随机数生成器（线程安全） */
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 十六进制格式化器 */
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    /** guacd 服务器地址 */
    @Getter
    private String guacdHost;
    /** guacd 服务器端口 */
    @Getter
    private int guacdPort;
    /** Web 前端地址 */
    @Getter
    private String webHost;
    /** Web 前端端口 */
    @Getter
    private int webPort;
    /** Web 上下文路径 */
    @Getter
    private String contextPath;
    /** 短 token 有效期 */
    @Getter
    private final Duration tokenTtl;
    /** 短链登记表：token 到连接参数，每个 token 的有效期由 ExpireValue 维护 */
    private final Map<String, ExpireValue<RemoteSpec>> tokens = new ConcurrentHashMap<>(16);

    /**
     * 创建 GuacamoleClient 实例
     * @param b b
     */
    private GuacamoleClient(Builder b) {
        this.guacdHost = b.guacdHost;
        this.guacdPort = b.guacdPort;
        this.webHost = b.webHost;
        this.webPort = b.webPort;
        this.contextPath = b.contextPath;
        this.tokenTtl = b.tokenTtl;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建客户端（guacd 与 Web 前端均默认本机）。
     *
     * @return GuacamoleClient 实例
     */
    public static GuacamoleClient create() {
        return builder().build();
    }

    /**
     * 创建客户端。
     *
     * @param guacdHost guacd 服务器地址，不能为空
     * @param guacdPort guacd 服务器端口
     * @return GuacamoleClient 实例
     */
    public static GuacamoleClient create(String guacdHost, int guacdPort) {
        return builder().guacdHost(guacdHost).guacdPort(guacdPort).build();
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 链式设置 ====================

    /**
     * 设置 guacd 服务器地址。
     *
     * @param host guacd 服务器地址，不能为 null
     * @return 当前实例（链式调用）
     */
    public GuacamoleClient guacdHost(String host) {
        this.guacdHost = Objects.requireNonNull(host, "guacdHost 不能为 null");
        return this;
    }

    /**
     * 设置 guacd 服务器端口。
     *
     * @param port guacd 服务器端口，范围 1-65535
     * @return 当前实例（链式调用）
     * @throws GuacamoleClientException 端口越界时
     */
    public GuacamoleClient guacdPort(int port) {
        checkPort(port);
        this.guacdPort = port;
        return this;
    }

    /**
     * 设置 Web 前端地址。
     *
     * @param host Web 前端地址，不能为 null
     * @return 当前实例（链式调用）
     */
    public GuacamoleClient webHost(String host) {
        this.webHost = Objects.requireNonNull(host, "webHost 不能为 null");
        return this;
    }

    /**
     * 设置 Web 前端端口。
     *
     * @param port Web 前端端口，范围 1-65535
     * @return 当前实例（链式调用）
     * @throws GuacamoleClientException 端口越界时
     */
    public GuacamoleClient webPort(int port) {
        checkPort(port);
        this.webPort = port;
        return this;
    }

    /**
     * 设置 Web 上下文路径。
     *
     * @param path 上下文路径（如 /guacamole），允许 null（null 时按默认路径处理）
     * @return 当前实例（链式调用）
     */
    public GuacamoleClient contextPath(String path) {
        this.contextPath = path;
        return this;
    }

    /**
     * 返回 guacd 协议代理地址。
     *
     * @return 形如 {@code host:port}
     */
    public String guacdAddress() {
        return guacdHost + ":" + guacdPort;
    }

    /**
     * 返回 Guacamole Web 前端入口 URL（不含短链 fragment）。
     *
     * @return 形如 {@code http://host:port/guacamole/}
     */
    public String webUrl() {
        String path = contextPath == null ? DEFAULT_CONTEXT_PATH : contextPath;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return "http://" + webHost + ":" + webPort + path;
    }

    // ==================== 短链操作 ====================

    /**
     * 进入远控连接参数登记链。
     *
     * @return 登记操作链
     */
    public IssueOperation issue() {
        return new IssueOperation(this);
    }

    /**
     * 进入 RDP 连接参数登记链（协议已预置 RDP）。
     *
     * @return 登记操作链
     */
    public IssueOperation rdp() {
        return issue().protocol(RemoteProtocol.RDP);
    }

    /**
     * 进入 VNC 连接参数登记链（协议已预置 VNC）。
     *
     * @return 登记操作链
     */
    public IssueOperation vnc() {
        return issue().protocol(RemoteProtocol.VNC);
    }

    /**
     * 进入 SSH 连接参数登记链（协议已预置 SSH）。
     *
     * @return 登记操作链
     */
    public IssueOperation ssh() {
        return issue().protocol(RemoteProtocol.SSH);
    }

    /**
     * 生成指定短 token 的会话 URL。
     *
     * <p>URL 仅携带短 token（fragment 形式，浏览器不会将 fragment 发送到服务器），
     * 连接参数通过 {@link #resolve(String)} 反向解析，敏感信息不落地到 URL。</p>
     *
     * @param token 由 {@link IssueOperation#issue()} 签发的短 token，不能为 null 或空白
     * @return 形如 {@code http://host:port/guacamole/#token=xxxxxxxx}
     * @throws GuacamoleClientException token 为空白时
     */
    public String url(String token) {
        if (token == null || token.isBlank()) {
            throw new GuacamoleClientException("token 不能为空");
        }
        return webUrl() + "#" + TOKEN_FRAGMENT_PARAM + "=" + enc(token);
    }

    /**
     * 解析短 token 对应的远控连接参数。
     *
     * <p>取值经 {@link ExpireValue#getValue()} 自动处理过期：token 有效期内的值直接返回，
     * 已过期或未知 token 返回空。</p>
     *
     * @param token 短 token，允许 null（null/空白直接返回空）
     * @return 连接参数集；token 未知或已过期时为空
     */
    public Optional<RemoteSpec> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        ExpireValue<RemoteSpec> holder = tokens.get(token);
        if (holder == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(holder.getValue());
    }

    /**
     * 手动刷新全部短 token 的有效期（按 token 有效期时长续期）。
     *
     * <p>仅对尚未过期清除的 token 生效；已被清除的 token 无法复活，需重新 {@link #issue()}。</p>
     *
     * @return 实际续期的 token 数量
     */
    public int refreshTokens() {
        int count = 0;
        for (ExpireValue<RemoteSpec> holder : tokens.values()) {
            holder.refresh();
            count++;
        }
        log.info("Guacamole 短 token 手动续期: count={}", count);
        return count;
    }

    /**
     * 返回已登记的短 token 数量（含已过期未清除的）。
     *
     * @return token 数量
     */
    public int tokenCount() {
        return tokens.size();
    }

    // ==================== 内部登记 ====================

    /**
     * 登记一组连接参数并返回其短 token（供 {@link IssueOperation} 内部调用）。
     *
     * <p>token 有效期由 {@link ExpireValue} 维护；极小概率的随机 token 冲突时自动重试。</p>
     *
     * @param spec 连接参数集，不能为 null
     * @return 短 token
     */
    private String register(RemoteSpec spec) {
        Objects.requireNonNull(spec, "spec 不能为 null");
        for (int attempt = 0; attempt < TOKEN_MAX_RETRY; attempt++) {
            String token = newToken();
            ExpireValue<RemoteSpec> holder = ExpireValue.of(spec, tokenTtl);
            if (tokens.putIfAbsent(token, holder) == null) {
                return token;
            }
        }
        throw new GuacamoleClientException("短 token 连续冲突，签发失败");
    }

    /**
     * 生成随机短 token（16 位十六进制小写字符）。
     *
     * @return 短 token
     */
    private static String newToken() {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HEX_FORMAT.formatHex(bytes);
    }

    // ==================== Builder ====================

    /**
     * GuacamoleClient 构建器。
     *
     * <p>guacd 与 Web 前端均默认本机；短 token 有效期默认 1 小时。</p>
     */
    public static class Builder {
        /** guacd 服务器地址，默认本机 */
        private String guacdHost = DEFAULT_GUACD_HOST;
        /** guacd 服务器端口，默认 4822 */
        private int guacdPort = DEFAULT_GUACD_PORT;
        /** Web 前端地址，默认本机 */
        private String webHost = DEFAULT_WEB_HOST;
        /** Web 前端端口，默认 8080 */
        private int webPort = DEFAULT_WEB_PORT;
        /** Web 上下文路径，默认 /guacamole */
        private String contextPath = DEFAULT_CONTEXT_PATH;
        /** 短 token 有效期，默认 1 小时 */
        private Duration tokenTtl = DEFAULT_TOKEN_TTL;

        /**
         * 设置 guacd 服务器地址。
         *
         * @param host guacd 服务器地址，不允许 null 或空白
         * @return 当前构建器
         */
        public Builder guacdHost(String host) {
            this.guacdHost = host;
            return this;
        }

        /**
         * 设置 guacd 服务器端口。
         *
         * @param port guacd 服务器端口
         * @return 当前构建器
         */
        public Builder guacdPort(int port) {
            this.guacdPort = port;
            return this;
        }

        /**
         * 设置 Web 前端地址。
         *
         * @param host Web 前端地址，不允许 null 或空白
         * @return 当前构建器
         */
        public Builder webHost(String host) {
            this.webHost = host;
            return this;
        }

        /**
         * 设置 Web 前端端口。
         *
         * @param port Web 前端端口
         * @return 当前构建器
         */
        public Builder webPort(int port) {
            this.webPort = port;
            return this;
        }

        /**
         * 设置 Web 上下文路径。
         *
         * @param path 上下文路径（如 /guacamole）
         * @return 当前构建器
         */
        public Builder contextPath(String path) {
            this.contextPath = path;
            return this;
        }

        /**
         * 设置短 token 有效期。
         *
         * @param ttl 有效期，不能为 null 或负值
         * @return 当前构建器
         * @throws NullPointerException ttl 为 null 时
         * @throws GuacamoleClientException ttl 为负时
         */
        public Builder tokenTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "tokenTtl 不能为 null");
            if (ttl.isNegative()) {
                throw new GuacamoleClientException("tokenTtl 不能为负");
            }
            this.tokenTtl = ttl;
            return this;
        }

        /**
         * 构建 GuacamoleClient 实例。
         *
         * @return GuacamoleClient 实例
         * @throws GuacamoleClientException guacd 或 Web 前端地址为空白、端口越界时
         */
        public GuacamoleClient build() {
            if (guacdHost == null || guacdHost.isBlank()) {
                throw new GuacamoleClientException("guacd 服务器地址不能为空");
            }
            if (webHost == null || webHost.isBlank()) {
                throw new GuacamoleClientException("Web 前端地址不能为空");
            }
            checkPort(guacdPort);
            checkPort(webPort);
            return new GuacamoleClient(this);
        }
    }

    // ==================== 短链登记操作 ====================

    /**
     * 远控连接参数登记链。
     *
     * <p>链式设置协议、主机、端口、账号与 Guacamole 扩展参数，
     * {@link #issue()} 将参数集登记进客户端内部短链登记表并签发短 token。</p>
     */
    public static class IssueOperation {
        /** 客户端 */
        private final GuacamoleClient client;
        /** 协议，默认 RDP */
        private RemoteProtocol protocol = RemoteProtocol.RDP;
        /** 远控服务器地址 */
        private String host;
        /** 远控服务器端口，可为 null（null 时取协议默认端口） */
        private Integer port;
        /** 用户名，可为 null */
        private String username;
        /** 密码，可为 null */
        private String password;
        /** Guacamole 扩展参数 */
        private final Map<String, String> params = new LinkedHashMap<>(8);

        /**
         * 创建登记操作链。
         *
         * @param client 所属客户端，不能为 null
         */
        IssueOperation(GuacamoleClient client) {
            this.client = client;
        }

        /**
         * 设置协议。
         *
         * @param protocol 协议，不能为 null
         * @return 当前操作链
         */
        public IssueOperation protocol(RemoteProtocol protocol) {
            this.protocol = Objects.requireNonNull(protocol, "protocol 不能为 null");
            return this;
        }

        /**
         * 设置协议（按参数值）。
         *
         * @param protocol 协议参数值（rdp/vnc/ssh/telnet，忽略大小写）
         * @return 当前操作链
         * @throws GuacamoleClientException 不支持的协议值
         */
        public IssueOperation protocol(String protocol) {
            return protocol(RemoteProtocol.of(protocol));
        }

        /**
         * 设置远控服务器地址。
         *
         * @param host 远控服务器地址，{@link #issue()} 前必须非空
         * @return 当前操作链
         */
        public IssueOperation host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置远控服务器端口。
         *
         * @param port 远控服务器端口，范围 1-65535
         * @return 当前操作链
         * @throws GuacamoleClientException 端口越界时
         */
        public IssueOperation port(int port) {
            checkPort(port);
            this.port = port;
            return this;
        }

        /**
         * 设置用户名。
         *
         * @param username 用户名，可为 null
         * @return 当前操作链
         */
        public IssueOperation username(String username) {
            this.username = username;
            return this;
        }

        /**
         * 设置密码。
         *
         * @param password 密码，可为 null
         * @return 当前操作链
         */
        public IssueOperation password(String password) {
            this.password = password;
            return this;
        }

        /**
         * 追加单个 Guacamole 扩展参数（参数名与 guacd 配置属性一致）。
         *
         * @param key 参数名，null 时忽略
         * @param value 参数值，null 时忽略
         * @return 当前操作链
         */
        public IssueOperation param(String key, String value) {
            if (key != null && value != null) {
                params.put(key, value);
            }
            return this;
        }

        /**
         * 批量追加 Guacamole 扩展参数。
         *
         * @param params 参数集合，null 时忽略；其中的 null 键值对会被过滤
         * @return 当前操作链
         */
        public IssueOperation params(Map<String, String> params) {
            if (params != null) {
                params.forEach(this::param);
            }
            return this;
        }

        /**
         * 登记当前参数集并签发短 token。
         *
         * <p>参数集与有效期由客户端内部维护，token 本身不含任何连接信息。</p>
         *
         * @return 短 token
         * @throws GuacamoleClientException 远控服务器地址未设置时
         */
        public String issue() {
            if (host == null || host.isBlank()) {
                throw new GuacamoleClientException("远控服务器地址（host）未设置");
            }
            var spec = new RemoteSpec(protocol, host, port, username, password, params);
            var token = client.register(spec);
            log.info("Guacamole 短链签发: protocol={}, target={}:{}", protocol.getValue(), host, spec.effectivePort());
            return token;
        }
    }

    // ==================== 连接参数集 ====================

    /**
     * 远控连接参数集（不可变）。
     *
     * <p>由短 token 承载，经 {@link GuacamoleClient#resolve(String)} 传递；
     * 密码等敏感字段仅存在于客户端内存登记表，不落地到 URL。</p>
     *
     * @param protocol 协议，不能为 null
     * @param host 远控服务器地址，不能为 null
     * @param port 远控服务器端口，可为 null（null 时取协议默认端口）
     * @param username 用户名，可为 null
     * @param password 密码，可为 null
     * @param params Guacamole 扩展参数，可为 null（null 时规范化为空不可变 Map）
     * @author CH
     * @since 4.0.0.42
     */
    public record RemoteSpec(
            RemoteProtocol protocol,
            String host,
            Integer port,
            String username,
            String password,
            Map<String, String> params
    ) {
        /**
         * 规范构造器：校验必填字段并将扩展参数规范化为不可变 Map。
         */
        public RemoteSpec {
            Objects.requireNonNull(protocol, "protocol 不能为 null");
            Objects.requireNonNull(host, "host 不能为 null");
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        /**
         * 返回生效端口（未设置端口时取协议默认端口）。
         *
         * @return 端口号
         */
        public int effectivePort() {
            return port != null ? port : protocol.getDefaultPort();
        }
    }

    // ==================== 协议枚举 ====================

    /**
     * Guacamole 支持协议。
     *
     * <p>每个协议携带 guacd 参数值与默认端口号。</p>
     */
    public enum RemoteProtocol {
        /** RDP 远程桌面 */
        RDP("rdp", 3389),
        /** VNC 虚拟网络计算 */
        VNC("vnc", 5900),
        /** SSH 安全外壳协议 */
        SSH("ssh", 22),
        /** TELNET */
        TELNET("telnet", 23);

        /** guacd 参数值 */
        private final String value;
        /** 默认端口 */
        private final int defaultPort;

        /**
         * 创建协议枚举常量。
         *
         * @param value guacd 参数值
         * @param defaultPort 默认端口
         */
        RemoteProtocol(String value, int defaultPort) {
            this.value = value;
            this.defaultPort = defaultPort;
        }

        /**
         * 返回 guacd 参数值。
         *
         * @return 参数值
         */
        public String getValue() {
            return value;
        }

        /**
         * 返回默认端口。
         *
         * @return 默认端口
         */
        public int getDefaultPort() {
            return defaultPort;
        }

        /**
         * 按参数值解析协议。
         *
         * @param value 协议参数值（rdp/vnc/ssh/telnet，忽略大小写）
         * @return 协议
         * @throws GuacamoleClientException 不支持的协议值
         */
        public static RemoteProtocol of(String value) {
            for (RemoteProtocol protocol : values()) {
                if (protocol.value.equalsIgnoreCase(value)) {
                    return protocol;
                }
            }
            throw new GuacamoleClientException("不支持的 Guacamole 协议: " + value);
        }
    }

    // ==================== 异常类 ====================

    /**
     * Guacamole 客户端异常。
     */
    public static class GuacamoleClientException extends RuntimeException {
        /**
         * 创建 GuacamoleClientException 实例
         * @param message message
         */
        public GuacamoleClientException(String message) {
            super(message);
        }

        /**
         * 创建 GuacamoleClientException 实例
         * @param message message
         * @param Throwable Throwable
         */
        public GuacamoleClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 校验端口号范围（1-65535）。
     *
     * @param port 待校验端口号
     * @throws GuacamoleClientException 端口号越界时
     */
    private static void checkPort(int port) {
        if (port < 1 || port > 65535) {
            throw new GuacamoleClientException("端口越界（1-65535）: " + port);
        }
    }

    /**
     * URL 编码（供 fragment 参数使用）。
     *
     * @param value 原始值
     * @return 编码后的值
     */
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
