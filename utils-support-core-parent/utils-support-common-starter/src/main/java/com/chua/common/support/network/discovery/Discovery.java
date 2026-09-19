package com.chua.common.support.network.discovery;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.net.NetAddress;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.UrlUtils;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_EMPTY;

/**
 * 服务发现信息模型，用于描述网络服务的元数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class Discovery implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 服务唯一标识符
     */
    private String id;

    /**
     * 服务器唯一标识符
     */
    private String serverId;

    /**
     * 业务分组标识(scatterId):同一服务路径下按业务隔离,
     * 仅相同 scatterId 的节点纳入同一负载均衡池,避免不同业务节点互相污染
     */
    private String scatterId;

    /**
     * 通信协议 (默认: http)
     */
    @Builder.Default
    /**
     * 协议
    */
    private String protocol = "http";

    /**
     * 请求超时时间 (毫秒)
     */
    private int timeout;

    /**
     * 服务权重，用于负载均衡
     */
    private double weight;

    /**
     * 主机地址 (默认: 127.0.0.1)
     */
    @Builder.Default
    /**
     * 主机名
     */
    private String host = "127.0.0.1";

    /**
     * 服务端口号
     */
    private int port;

    /**
     * URI 规格化路径
     */
    private String uriSpec;

    /**
     * 扩展元数据 (默认: 空Map)
     */
    @Builder.Default
    private Map<String, String> metadata = Collections.emptyMap();

    /**
     * 运行环境 (默认: prod)
     */
    @Builder.Default
    /**
     * ENV
    */
    private String env = "prod";

    /**
     * 设置URI规格，仅当当前值为null时进行赋值
     *
     * @param uriSpec URI规格字符串
     */
    public void setUriSpec(String uriSpec) {
        if (null == this.uriSpec) {
            this.uriSpec = uriSpec;
        }
    }

    /**
     * 获取协议，如果为null则返回默认值"http"
     *
     * @return 协议字符串
     */
    public String getProtocol() {
        if (protocol != null) {
            return protocol;
        } else {
            return "http";
        }
    }

    /**
     * 将当前对象序列化为JSON字符串
     *
     * @return JSON格式的字符串
     */
    public String toFullString() {
        return Json.toJson(this);
    }

    /**
     * 根据提供的URL创建完整的服务访问地址
     *
     * @param url 相对或绝对URL
     * @return 完整的URL字符串
     */
    public String createUrl(String url) {
        NetAddress normalUrl = NetAddress.of(url);
        String address = normalUrl.getAddress();
        String query = normalUrl.getQuery();
        String newUrl;

        if (host.startsWith("http")) {
            newUrl = host;
        } else {
            newUrl = protocol + "://" + host + ":" + port;
        }

        if (newUrl.endsWith("/")) {
            String s = url.replaceFirst(StringUtils.endWithMove(uriSpec, "/"), "");
            if (StringUtils.isNullOrEmpty(s)) {
                return createQueryUrl(newUrl, query, SYMBOL_EMPTY);
            }
            return createQueryUrl(UrlUtils.normalize(newUrl, s), query, SYMBOL_EMPTY);
        }
        return createQueryUrl(UrlUtils.normalize(newUrl, url), query, SYMBOL_EMPTY);
    }

    /**
     * 构建包含查询参数的最终URL
     *
     * @param newUrl 基础URL
     * @param query  查询参数字符串
     * @param path   路径部分
     * @return 处理后的URL字符串
     */
    private String createQueryUrl(String newUrl, String query, String path) {
        if (StringUtils.isNotBlank(query)) {
            String pathPart;
            if (StringUtils.isEmpty(path)) {
                pathPart = "";
            } else {
                pathPart = StringUtils.startWithAppend(path, "/");
            }
            return UrlUtils.normalize(newUrl, pathPart) + "?" + query;
        }

        String s;
        if (StringUtils.isEmpty(path)) {
            s = "";
        } else {
            s = StringUtils.startWithAppend(path, "/");
        }

        if (StringUtils.isEmpty(s)) {
            return UrlUtils.normalize(newUrl);
        }
        return UrlUtils.normalize(newUrl, s);
    }

    /**
     * 判断是否为HTTP或HTTPS协议
     *
     * @return true表示是HTTP/HTTPS协议
     */
    public boolean isHttp() {
        if ("http".equalsIgnoreCase(protocol)) {
            return true;
        }
        if ("https".equalsIgnoreCase(protocol)) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为WebSocket协议
     *
     * @return true表示是WebSocket协议
     */
    public boolean isWebsocket() {
        if ("ws".equalsIgnoreCase(protocol)) {
            return true;
        }
        if ("wss".equalsIgnoreCase(protocol)) {
            return true;
        }
        return false;
    }

    /**
     * 匹配运行环境
     *
     * @param targetEnv 目标环境标识
     * @return true表示环境匹配
     */
    public boolean matchEnv(String targetEnv) {
        if (targetEnv == null || targetEnv.isEmpty()) {
            return true;
        }
        return targetEnv.equalsIgnoreCase(this.env);
    }
}
