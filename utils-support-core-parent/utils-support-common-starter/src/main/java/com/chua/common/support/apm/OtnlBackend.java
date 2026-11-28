package com.chua.common.support.apm;

/**
 * APM 后端连接参数：由宿主（数据源台账 / 配置页）解析后交给引擎。
 *
 * <p>{@link OtnlEngine} 是 SPI（无参构造）实例化的，拿不到宿主的
 * {@code @ConfigurationProperties}；需要每请求切后端（管理页多数据源）时，
 * 宿主用 {@link OtnlEngine#configure(OtnlBackend)} 把当前目标推给引擎即可。</p>
 *
 * <p>命名上与 {@code com.chua.common.support.apm.model.OtnlEndpoint}（端点实体）区分：
 * 后者是链路里的一个端点实体，本类描述的是「观测后端服务」。</p>
 *
 * @param url       后端地址（如 SkyWalking OAP 的 {@code http://host:12800}）
 * @param username  账号，可为空
 * @param password  口令，可为空
 * @param timeoutMs 超时（毫秒），{@code <=0} 表示不覆盖
 * @param enabled   是否启用该后端
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlBackend(String url, String username, String password, long timeoutMs, boolean enabled) {

    /**
     * 规整空值。
     */
    public OtnlBackend {
        url = url == null ? "" : url.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }
}
