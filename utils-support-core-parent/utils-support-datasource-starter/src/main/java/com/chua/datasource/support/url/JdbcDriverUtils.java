// [P3C A20 豁免] A20/A30/A31: 驱动类名映射表内容为查找数据而非业务魔法值，豁免。
package com.chua.datasource.support.url;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC 驱动自动解析：按连接串前缀推断驱动类名。
 *
 * <p>调用方通常已经拿到完整 {@code jdbc:} 连接串，但未必填了驱动类名。
 * 本工具按子协议前缀给出常见驱动的全限定名，推断不出时返回 {@code null}，
 * 由调用方决定回落行为（例如交给 {@code DriverManager} 自动发现）。</p>
 *
 * <p>只做字符串映射，不加载类：驱动是否真的在 classpath 上由建连方负责
 * （classpath 内走 {@code DriverManager}，上传 jar 走各自的类加载器加载）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class JdbcDriverUtils {

    /**
     * 连接串前缀 → 驱动类全名（有序映射；前缀互不重叠，命中即返回）。
     */
    private static final Map<String, String> PREFIX_DRIVERS = new LinkedHashMap<>();

    static {
        // 关系型数据库
        PREFIX_DRIVERS.put("jdbc:mysql:", "com.mysql.cj.jdbc.Driver");
        PREFIX_DRIVERS.put("jdbc:mariadb:", "org.mariadb.jdbc.Driver");
        PREFIX_DRIVERS.put("jdbc:postgresql:", "org.postgresql.Driver");
        PREFIX_DRIVERS.put("jdbc:oracle:", "oracle.jdbc.OracleDriver");
        PREFIX_DRIVERS.put("jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        PREFIX_DRIVERS.put("jdbc:db2:", "com.ibm.db2.jcc.DB2Driver");
        PREFIX_DRIVERS.put("jdbc:dm:", "dm.jdbc.driver.DmDriver");
        PREFIX_DRIVERS.put("jdbc:oceanbase:", "com.oceanbase.jdbc.Driver");
        // 嵌入式 / 文件库
        PREFIX_DRIVERS.put("jdbc:h2:", "org.h2.Driver");
        PREFIX_DRIVERS.put("jdbc:sqlite:", "org.sqlite.JDBC");
        PREFIX_DRIVERS.put("jdbc:duckdb:", "org.duckdb.DuckDBDriver");
        PREFIX_DRIVERS.put("jdbc:derby:", "org.apache.derby.jdbc.EmbeddedDriver");
        PREFIX_DRIVERS.put("jdbc:hsqldb:", "org.hsqldb.jdbc.JDBCDriver");
        // 分析型 / 列存 / 湖仓
        PREFIX_DRIVERS.put("jdbc:clickhouse:", "com.clickhouse.jdbc.ClickHouseDriver");
        PREFIX_DRIVERS.put("jdbc:trino:", "io.trino.jdbc.TrinoDriver");
        PREFIX_DRIVERS.put("jdbc:presto:", "com.facebook.presto.jdbc.PrestoDriver");
        // 其它常见库
        PREFIX_DRIVERS.put("jdbc:informix:", "com.informix.jdbc.IfxDriver");
        PREFIX_DRIVERS.put("jdbc:sybase:", "com.sybase.jdbc4.jdbc.SybDriver");
        PREFIX_DRIVERS.put("jdbc:vertica:", "com.vertica.jdbc.Driver");
    }

    /**
     * 本地文件/嵌入式库前缀：没有网络连接，建连与探测时不应设置 TCP 超时类属性。
     */
    private static final String[] EMBEDDED_PREFIXES = {
            "jdbc:h2:", "jdbc:sqlite:", "jdbc:derby:", "jdbc:hsqldb:", "jdbc:duckdb:"
    };

    /**
     * 工具类不允许实例化。
     */
    private JdbcDriverUtils() {
    }

    /**
     * 按连接串前缀推断 JDBC 驱动类全名。
     *
     * @param jdbcUrl JDBC 连接串，可为 null
     * @return 驱动类全名；无法识别时返回 {@code null}
     */
    public static String resolveDriverClass(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : PREFIX_DRIVERS.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 取连接串的子协议名（{@code jdbc:<scheme>:...} 中的 {@code <scheme>}）。
     *
     * @param jdbcUrl JDBC 连接串，可为 null
     * @return 小写子协议名；不是 {@code jdbc:} 开头或子协议为空时返回 {@code null}
     */
    public static String scheme(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT).trim();
        if (!lower.startsWith("jdbc:")) {
            return null;
        }
        int from = "jdbc:".length();
        int end = lower.indexOf(':', from);
        String name = end < 0 ? lower.substring(from) : lower.substring(from, end);
        return name.isBlank() ? null : name;
    }

    /**
     * 是否为本地文件/嵌入式库（无网络连接）。
     *
     * @param jdbcUrl JDBC 连接串，可为 null
     * @return 嵌入式库返回 {@code true}
     */
    public static boolean isEmbedded(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return false;
        }
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        for (String prefix : EMBEDDED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
