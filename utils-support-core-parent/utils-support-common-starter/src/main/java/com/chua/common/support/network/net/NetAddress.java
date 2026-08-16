package com.chua.common.support.network.net;

import com.chua.common.support.utils.StringUtils;
import lombok.Data;
import lombok.experimental.Accessors;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Accessors(chain = true)
public class NetAddress implements Serializable {

    private String protocol;
    /**
     * 主机名
     */
    private String host;
    /**
     * 端口号
     */
    private Integer port;
    private String address;
    /**
     * 路径
     */
    private String path;
    private String query;
    private String fragment;
    /**
     * 用户名
     */
    private String username;
    /**
     * 密码
     */
    private String password;

    public NetAddress() {}

    private NetAddress(String url) {
        parse(url);
    }

    private void parse(String url) {
        if (StringUtils.isNullOrEmpty(url)) { return; }
        try {
            String s = url.trim();
            if (!s.contains("://") && s.contains(":")) {
                String[] parts = s.split(":");
                this.host = parts[0];
                if (parts.length > 1) {
                    String remainder = parts[1];
                    int slashIdx = remainder.indexOf('/');
                    if (slashIdx > 0) {
                        this.port = Integer.parseInt(remainder.substring(0, slashIdx));
                        this.path = remainder.substring(slashIdx);
                    } else {
                        int qIdx = remainder.indexOf('?');
                        if (qIdx > 0) {
                            this.port = Integer.parseInt(remainder.substring(0, qIdx));
                            this.query = remainder.substring(qIdx + 1);
                        } else {
                            this.port = Integer.parseInt(remainder);
                        }
                    }
                }
                this.address = this.host + ":" + this.port;
                if (this.path == null) {
                    this.path = "";
                }
                return;
            }
            URI uri = new URI(s);
            this.protocol = uri.getScheme();
            this.host = uri.getHost();
            this.port = uri.getPort() == -1 ? getDefaultPort(protocol) : uri.getPort();
            this.address = this.host + (this.port > 0 ? ":" + this.port : "");
            this.path = uri.getPath();
            this.query = uri.getQuery();
            this.fragment = uri.getFragment();
            String userInfo = uri.getUserInfo();
            if (StringUtils.isNotEmpty(userInfo)) {
                String[] up = userInfo.split(":", 2);
                this.username = up[0];
                if (up.length > 1) {
                    this.password = up[1];
                }
            }
        } catch (Exception e) {
            this.address = url;
            this.host = url;
        }
    }

    public static NetAddress of(String url) {
        return new NetAddress(url);
    }

    public static NetAddress of(String host, int port) {
        return new NetAddress(host + ":" + port);
    }

    public InetSocketAddress toInetSocketAddress() {
        if (host != null && port != null) { return new InetSocketAddress(host, port); }
        return null;
    }

    public int getPort(int defaultPort) {
        return port != null && port > 0 ? port : defaultPort;
    }

    public String getHost(String defaultHost) {
        return StringUtils.isNullOrEmpty(host) || "127.0.0.1".equals(host)
                ? (defaultHost != null ? defaultHost : "127.0.0.1")
                : host;
    }

    public String getProtocol(String defaultProtocol) {
        return StringUtils.isNullOrEmpty(protocol) ? defaultProtocol : protocol;
    }

    private static int getDefaultPort(String protocol) {
        if (StringUtils.isNullOrEmpty(protocol)) { return -1; }
        return switch (protocol.toLowerCase()) {
            case "http" -> 80;
            case "https" -> 443;
            case "ftp" -> 21;
            case "ssh" -> 22;
            case "mysql" -> 3306;
            case "redis" -> 6379;
            case "mongodb" -> 27017;
            case "kafka" -> 9092;
            case "zookeeper" -> 2181;
            case "etcd" -> 2379;
            default -> -1;
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotEmpty(protocol)) {
            sb.append(protocol).append("://");
        }
        if (StringUtils.isNotEmpty(username)) {
            sb.append(username);
            if (StringUtils.isNotEmpty(password)) {
                sb.append(":").append(password);
            }
            sb.append("@");
        }
        if (StringUtils.isNotEmpty(host)) {
            sb.append(host);
            if (port != null && port > 0) {
                sb.append(":").append(port);
            }
        }
        if (StringUtils.isNotEmpty(path)) {
            sb.append(path);
        }
        if (StringUtils.isNotEmpty(query)) {
            sb.append("?").append(query);
        }
        if (StringUtils.isNotEmpty(fragment)) {
            sb.append("#").append(fragment);
        }
        return sb.toString();
    }
}
