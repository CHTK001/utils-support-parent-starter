package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQLite 数据库 (SQLITE / DB / SQLITE3) 预览提供器。
 * <p>SPI 类型：{@code preview-sqlite}。以只读方式连接数据库，
 * 展示表清单与各表前 100 行数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-sqlite")
public class SqlitePreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("sqlite", "sqlite3", "db");
    private static final int MAX_ROWS = 100;
    private static final int MAX_TABLES = 50;
    private static final long MAX_FILE_SIZE = 512L * 1024 * 1024;

    static {
        if (ReflectUtils.forName("org.sqlite.JDBC") == null) {
            throw new IllegalStateException("sqlite-jdbc driver not found");
        }
    }

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(emptyHtml("数据库文件为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(emptyHtml("数据库文件过大（超过 512 MB），暂不支持预览")).build();
        }
        String html = previewDatabase(content);
        return PreviewResult.builder().htmlContent(html).build();
    }

    /**
     * 执行数据库预览。
     *
     * @param content 数据库文件字节
     * @return 预览 HTML
     * @throws IOException 数据库不可读时抛出
     */
    private String previewDatabase(byte[] content) throws IOException {
        Path tmp = Files.createTempFile("preview-sqlite-", ".db");
        try {
            Files.write(tmp, content);
            try (Connection conn = openConnection(tmp)) {
                List<String> tables = listTables(conn);
                StringBuilder sb = new StringBuilder();
                sb.append("<div class=\"header\"><h1>SQLite 数据库预览</h1>");
                sb.append("<div class=\"meta\">数据表: ").append(tables.size()).append("</div></div>");
                sb.append("<div class=\"tables\">");
                if (tables.isEmpty()) {
                    sb.append("<div class=\"empty\">数据库无用户表</div>");
                } else {
                    for (String table : tables) {
                        renderTable(sb, conn, table);
                    }
                }
                sb.append("</div>");
                return wrapHtml(sb.toString());
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read sqlite database", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 打开只读数据库连接。
     *
     * @param path 数据库文件路径
     * @return 数据库连接
     * @throws SQLException 连接失败时抛出
     */
    private Connection openConnection(Path path) throws SQLException {
        String url = "jdbc:sqlite:file:" + path.toAbsolutePath() + "?mode=ro";
        Connection conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        return conn;
    }

    /**
     * 列出数据库全部用户表。
     *
     * @param conn 数据库连接
     * @return 表名列表
     * @throws SQLException 查询失败时抛出
     */
    private List<String> listTables(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT name FROM sqlite_master WHERE type IN ('table','view') "
                + "AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
                if (tables.size() >= MAX_TABLES) {
                    break;
                }
            }
        }
        return tables;
    }

    /**
     * 渲染单张表的前 100 行。
     *
     * @param sb    输出缓冲区
     * @param conn  数据库连接
     * @param table 表名
     */
    private void renderTable(StringBuilder sb, Connection conn, String table) {
        sb.append("<div class=\"table-block\"><div class=\"table-head\">")
                .append(escape(table)).append("</div>");
        String quote = '"' + table.replace("\"", "\"\"") + '"';
        String sql = "SELECT * FROM " + quote + " LIMIT " + MAX_ROWS;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            sb.append("<table><thead><tr>");
            for (int i = 1; i <= colCount; i++) {
                sb.append("<th>").append(escape(meta.getColumnLabel(i))).append("</th>");
            }
            sb.append("</tr></thead><tbody>");
            int rowCount = 0;
            while (rs.next()) {
                sb.append("<tr>");
                for (int i = 1; i <= colCount; i++) {
                    String value = rs.getString(i);
                    sb.append("<td>").append(value == null ? "<em>NULL</em>" : escape(value)).append("</td>");
                }
                sb.append("</tr>");
                rowCount++;
            }
            sb.append("</tbody></table>");
            if (rowCount >= MAX_ROWS) {
                sb.append("<div class=\"table-more\">仅显示前 ").append(MAX_ROWS).append(" 行</div>");
            }
        } catch (SQLException e) {
            sb.append("<div class=\"table-error\">读取失败: ").append(escape(e.getMessage())).append("</div>");
        }
        sb.append("</div>");
    }

    /**
     * 构建完整预览页面。
     *
     * @param body 页面主体片段
     * @return 完整 HTML
     */
    private String wrapHtml(String body) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}"
                + ".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}"
                + "h1{margin:0 0 8px;font-size:20px;font-weight:600}"
                + ".meta{color:#6b7280;font-size:13px}"
                + ".tables{max-width:960px;margin:0 auto}"
                + ".table-block{background:#fff;border:1px solid #e5e7eb;border-radius:10px;margin-bottom:16px;overflow-x:auto}"
                + ".table-head{padding:10px 16px;font-weight:600;border-bottom:1px solid #f0f0f0;background:#f9fafb}"
                + "table{border-collapse:collapse;width:100%;font-size:13px}"
                + "th,td{border:1px solid #eee;padding:6px 12px;text-align:left;white-space:nowrap}"
                + "th{background:#f3f4f6;position:sticky;top:0}"
                + "tr:nth-child(even) td{background:#fafafa}"
                + "td em{color:#9ca3af}"
                + ".table-more{color:#9ca3af;font-size:12px;padding:8px 16px}"
                + ".table-error{color:#dc2626;font-size:13px;padding:12px 16px}"
                + ".empty{color:#6b7280;text-align:center;padding:40px}"
                + "</style></head><body>" + body + "</body></html>";
    }

    /**
     * 构建空结果页面。
     *
     * @param message 提示信息
     * @return 完整 HTML
     */
    private String emptyHtml(String message) {
        return wrapHtml("<div class=\"header\"><h1>SQLite 数据库预览</h1></div>"
                + "<div class=\"empty\">" + escape(message) + "</div>");
    }

    /**
     * HTML 转义。
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    private String escape(String text) {
        return StringUtils.escapeHtml(text);
    }
}