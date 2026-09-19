package com.chua.datasource.support.url;

import java.util.regex.Pattern;

/**
 * JDBC 连接串片段校验工具。
 * <p>
 * 主机名与库名会被直接拼进 {@code jdbc:...//host:port/db?params} 形式的连接串。
 * 若不加校验，调用方可以在其中塞入 {@code ?}、{@code &}、{@code ;}、{@code =} 等分隔符，
 * 从而追加驱动属性（例如 MySQL Connector/J 的 {@code autoDeserialize=true}、
 * {@code queryInterceptors=...}、{@code allowLoadLocalInfile=true}），
 * 把一次普通的连库变成反序列化或本地文件读取入口。这里在边界处一次性拒绝这类输入。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class JdbcUrl {

    /**
     * 合法主机名：DNS 名称、IPv4，或方括号包裹的 IPv6 字面量
     */
    private static final Pattern HOST_PATTERN =
            Pattern.compile("(?:\\[[0-9A-Fa-f:.]{1,45}]{1}|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?)");

    /**
     * 合法库名：单个标识符，允许 {@code _}、{@code $}、{@code -}，或用 {@code .} 限定一层 schema
     */
    private static final Pattern DATABASE_PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$\\-]{0,127}(?:\\.[A-Za-z_][A-Za-z0-9_$\\-]{0,127})?");

    /**
     * 工具类不允许实例化
     */
    private JdbcUrl() {
    }

    /**
     * 校验主机地址。
     *
     * @param host 主机地址，不允许为空
     * @return 原样返回通过校验的主机地址
     * @throws IllegalArgumentException 主机为空或含有连接串分隔符
     */
    public static String checkHost(String host) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("JDBC host must not be blank");
        }
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new IllegalArgumentException("Illegal JDBC host: " + host);
        }
        return host;
    }

    /**
     * 校验数据库名。
     *
     * @param database 数据库名，允许为空串（表示不指定库）
     * @return 原样返回通过校验的库名
     * @throws IllegalArgumentException 库名含有连接串分隔符
     */
    public static String checkDatabase(String database) {
        if (database == null) {
            throw new IllegalArgumentException("JDBC database must not be null");
        }
        if (database.isEmpty()) {
            return database;
        }
        if (!DATABASE_PATTERN.matcher(database).matches()) {
            throw new IllegalArgumentException("Illegal JDBC database: " + database);
        }
        return database;
    }

    /**
     * 校验端口号。端口是整数，不构成注入面，仅做范围保护。
     *
     * @param port 端口号，0 表示使用驱动默认端口
     * @return 原样返回通过校验的端口
     * @throws IllegalArgumentException 端口越界
     */
    public static int checkPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Illegal JDBC port: " + port);
        }
        return port;
    }
}
