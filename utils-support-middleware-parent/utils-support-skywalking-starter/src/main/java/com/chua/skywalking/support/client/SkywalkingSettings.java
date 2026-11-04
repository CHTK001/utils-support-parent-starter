package com.chua.skywalking.support.client;

/**
 * SkyWalking 连接参数的进程级设置。
 * <p>
 * OAP 地址属于「部署信息」而不是「业务数据」，但 {@link com.chua.common.support.apm.OtnlEngine}
 * 是通过 SPI（无参构造）实例化的，拿不到 Spring 的 {@code @ConfigurationProperties}。
 * 因此这里放一份静态设置：
 * </p>
 * <ul>
 *   <li>Spring 环境下由 {@code SkywalkingAutoConfiguration} 在启动时调用
 *       {@link #apply(String, String, String, long, boolean)} 覆盖；</li>
 *   <li>纯 SPI / 无 Spring 环境下回落到系统属性 {@code skywalking.url} 与
 *       环境变量 {@code SW_OAP_ADDRESS}（SkyWalking 官方 agent 用的同名变量）。</li>
 * </ul>
 * <p>
 * 字段全部 {@code volatile}，允许运行期热改（HTTP 请求间即时生效，不需重启）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class SkywalkingSettings {

    /**
     * OAP 默认地址（SkyWalking 默认 HTTP 端口 12800）
     */
    public static final String DEFAULT_URL = "http://127.0.0.1:12800";

    /**
     * 系统属性名：OAP 地址
     */
    public static final String PROP_URL = "skywalking.url";

    /**
     * 环境变量名：OAP 地址（与 SkyWalking agent 一致）
     */
    public static final String ENV_ADDRESS = "SW_OAP_ADDRESS";

    /**
     * 环境变量名：OAP 用户名
     */
    public static final String ENV_USERNAME = "SW_OAP_USERNAME";

    /**
     * 环境变量名：OAP 密码
     */
    public static final String ENV_PASSWORD = "SW_OAP_PASSWORD";

    /**
     * 默认超时（毫秒）
     */
    public static final long DEFAULT_TIMEOUT = 5_000L;

    private static volatile String url = resolveUrl();
    private static volatile String username = env(ENV_USERNAME);
    private static volatile String password = env(ENV_PASSWORD);
    private static volatile long timeout = DEFAULT_TIMEOUT;
    private static volatile boolean enabled = true;

    private SkywalkingSettings() {
    }

    /**
     * 批量写入设置
     *
     * @param newUrl       OAP 地址（空则不覆盖）
     * @param newUsername  用户名（空则不覆盖）
     * @param newPassword  密码（空则不覆盖）
     * @param newTimeout   超时毫秒（<=0 则不覆盖）
     * @param newEnabled   是否启用
     */
    public static void apply(String newUrl, String newUsername, String newPassword,
                             long newTimeout, boolean newEnabled) {
        if (newUrl != null && !newUrl.isBlank()) {
            url = normalize(newUrl);
        }
        if (newUsername != null) {
            username = newUsername;
        }
        if (newPassword != null) {
            password = newPassword;
        }
        if (newTimeout > 0) {
            timeout = newTimeout;
        }
        enabled = newEnabled;
    }

    /**
     * 当前 OAP 地址（不含 {@code /graphql} 后缀）
     *
     * @return 地址
     */
    public static String url() {
        return url;
    }

    /**
     * 当前用户名
     *
     * @return 用户名，未配置返回空串
     */
    public static String username() {
        return username;
    }

    /**
     * 当前密码
     *
     * @return 密码，未配置返回空串
     */
    public static String password() {
        return password;
    }

    /**
     * 当前超时毫秒
     *
     * @return 超时
     */
    public static long timeout() {
        return timeout;
    }

    /**
     * 是否启用
     *
     * @return 是否启用
     */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * 规范化地址：去掉尾部斜杠；若带 {@code /graphql} 后缀则剥离
     *
     * @param raw 原始地址
     * @return 规范化地址
     */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        String suffix = "/graphql";
        if (value.endsWith(suffix)) {
            value = value.substring(0, value.length() - suffix.length());
        }
        return value.isEmpty() ? DEFAULT_URL : value;
    }

    /**
     * 解析默认地址：系统属性 > 环境变量 > 默认值
     *
     * @return 地址
     */
    private static String resolveUrl() {
        String fromProperty = System.getProperty(PROP_URL);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return normalize(fromProperty);
        }
        String fromEnv = env(ENV_ADDRESS);
        if (!fromEnv.isBlank()) {
            // SkyWalking 习惯写成 host:port，这里补协议头
            return normalize(fromEnv.contains("://") ? fromEnv : "http://" + fromEnv);
        }
        return DEFAULT_URL;
    }

    /**
     * 读取环境变量（异常安全）
     *
     * @param name 变量名
     * @return 值，不存在返回空串
     */
    private static String env(String name) {
        try {
            return String.valueOf(System.getenv().getOrDefault(name, ""));
        } catch (Throwable ignore) {
            return "";
        }
    }
}
