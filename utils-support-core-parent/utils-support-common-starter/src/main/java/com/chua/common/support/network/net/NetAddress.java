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

    private static final long serialVersionUID = 1L;

    /** 协议 */
    private String protocol;
    /**
    * 主机名
    */
    private String host;
    /**
    * 端口号
    */
    private Integer port;
    /** 地址 */
    private String address;
    /**
    * 路径
    */
    private String path;
    /** Query */
    private String query;
    /** Fragment */
    private String fragment;
    /**
    * 用户名
    */
    private String username;
    /**
    * 密码
    */
    private String password;
    /**
    * 数据库名（R2DBC 专用，如 h2:mem://dbname 中的 dbname）
    */
    private String database;
    /**
    * 是否为 R2DBC URL
    */
    private boolean r2dbc;

    /** 创建 NetAddress 实例 */
    public NetAddress() {}

    /**
    * 创建 NetAddress 实例
    * @param url url
    */
    private NetAddress(String url) {
        parse(url);
    }

    /** 解析 */
    private void parse(String url) {
        if (StringUtils.isNullOrEmpty(url)) { return; }
        try {
            String s = url.trim();
            // R2DBC URL: r2dbc:h2:mem://dbname 或 r2dbc:mysql://host:port/db
            if (s.startsWith("r2dbc:")) {
                parseR2dbcUrl(s);
                return;
            }
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

    /**
    * 解析 R2DBC URL。
    * 格式：r2dbc:{driver}:[mem|file|...]://[{host}[:port]][/database]
    * 例如：r2dbc:h2:mem://testdb、r2dbc:mysql://localhost:3306/mydb
    */
    private void parseR2dbcUrl(String url) {
        this.r2dbc = true;
        // 去掉 r2dbc: 前缀
        String afterPrefix = url.substring("r2dbc:".length());
        // 分割 driver 和 rest
        int firstColon = afterPrefix.indexOf(':');
        if (firstColon < 0) { this.address = url; return; }
        this.protocol = afterPrefix.substring(0, firstColon); // "h2" 或 "mysql"
        String rest = afterPrefix.substring(firstColon + 1); // ":mem://testdb"
        if (rest.startsWith(":")) {
            rest = rest.substring(1); // "mem://testdb"
        }
        // 找 ://
        int schemeEnd = rest.indexOf("://");
        if (schemeEnd < 0) { this.address = url; return; }
        String authority = rest.substring(0, schemeEnd); // "mem" 或 "localhost:3306"
        String pathPart = rest.substring(schemeEnd + 3); // "testdb" 或 "/mydb"

        // 判断是否有 host：有 / 或 : 表示有 host（MySQL 风格）
        // 无 / 且无 : 表示无 host（H2 mem/file 风格）
        if (authority.contains("/") || authority.contains(":")) {
            // 有 host
            int slashIdx = authority.indexOf('/');
            this.host = slashIdx >= 0 ? authority.substring(0, slashIdx) : authority;
            if (this.host != null && this.host.contains(":")) {
                String[] hp = this.host.split(":");
                this.host = hp[0];
                try { this.port = Integer.parseInt(hp[1]); } catch (NumberFormatException ignored) {}
            }
            this.path = slashIdx >= 0 ? authority.substring(slashIdx) : "";
        } else {
            // 无 host（H2 mem/file 模式），整个 authority 是 sub-protocol
            // database 从 pathPart 提取
            this.path = pathPart.isEmpty() ? "" : "/" + pathPart;
            this.database = pathPart;
            this.address = "";
            return;
        }
        // 解析 path 中的 database
        if (!pathPart.isEmpty()) {
            this.path = pathPart.startsWith("/") ? pathPart : "/" + pathPart;
            String db = this.path.substring(1);
            int qIdx = db.indexOf('?');
            this.database = qIdx > 0 ? db.substring(0, qIdx) : db;
        }
        this.address = this.host + (this.port != null && this.port > 0 ? ":" + this.port : "");
    }

    /** Of */
    public static NetAddress of(String url) {
        return new NetAddress(url);
    }

    /** Of */
    public static NetAddress of(String host, int port) {
        return new NetAddress(host + ":" + port);
    }

    /** ToInetSocketAddress */
    public InetSocketAddress toInetSocketAddress() {
        if (host != null && port != null) { return new InetSocketAddress(host, port); }
        return null;
    }

    /** 获取Port */
    public int getPort(int defaultPort) {
        return port != null && port > 0 ? port : defaultPort;
    }

    /** 获取Host */
    public String getHost() {
        return host;
    }

    /** 获取Host（带默认值） */
    public String getHost(String defaultHost) {
        return StringUtils.isNullOrEmpty(host) || "127.0.0.1".equals(host)
                ? (defaultHost != null ? defaultHost : "127.0.0.1")
                : host;
    }

    /** 获取Protocol */
    public String getProtocol(String defaultProtocol) {
        return StringUtils.isNullOrEmpty(protocol) ? defaultProtocol : protocol;
    }

    /** 获取DefaultPort */
    private static int getDefaultPort(String protocol) {
        if (StringUtils.isNullOrEmpty(protocol)) { return -1; }
        return switch (protocol.toLowerCase()) {
            case "http" -> 80;
            case "https" -> 443;
            case "ftp" -> 21;
            case "ssh" -> 22;
            case "mysql" -> 3306;
            case "postgresql" -> 5432;
            case "mariadb" -> 3306;
            case "mssql" -> 1433;
            case "oracle" -> 1521;
            case "h2" -> -1;
            case "redis" -> 6379;
            case "mongodb" -> 27017;
            case "kafka" -> 9092;
            case "zookeeper" -> 2181;
            case "etcd" -> 2379;
            default -> -1;
        };
    }

    @Override
    /** ToString */
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
