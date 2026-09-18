package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
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
 * Parquet 列式数据文件 (PARQUET) 预览提供器。
 * <p>SPI 类型：{@code preview-parquet}。通过 DuckDB JDBC 原生读取 Parquet 文件，
 * 无需 Hadoop 依赖，展示列名与前 100 行数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-parquet")
public class ParquetPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("parquet", "pq"); // 支持exts
    private static final int MAX_ROWS = 100; // 最大rows
    private static final long MAX_FILE_SIZE = 512L * 1024 * 1024; // 最大文件大小

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件过大（超过 512 MB），暂不支持预览")).build();
        }
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("preview-parquet-", ".parquet");
        try {
            java.nio.file.Files.write(tmp, content);
            String path = tmp.toAbsolutePath().toString().replace('\\', '/');
            String html = previewParquet(path, content.length);
            return PreviewResult.builder().htmlContent(html).build();
        } catch (Exception e) {
            return PreviewResult.builder().htmlContent(unavailableHtml("Parquet 解析失败: " + escape(e.getMessage()))).build();
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    /**
    * 使用 DuckDB JDBC 解析 Parquet 文件并构建预览 HTML。
    *
    * @param path      临时 Parquet 文件路径（正斜杠）
    * @param fileSize  文件字节数
    * @return 完整 HTML
    * @throws SQLException 解析失败时抛出
    */
    private String previewParquet(String path, long fileSize) throws SQLException {
        String sqlPath = "'" + path + "'";
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement stmt = conn.createStatement()) {
            // 读取列名
            List<String> fields = new ArrayList<>();
            ResultSet schemaRs = stmt.executeQuery("DESCRIBE SELECT * FROM " + sqlPath);
            while (schemaRs.next()) {
                fields.add(schemaRs.getString("column_name"));
            }
            // 总行数（Parquet 文件走 footer metadata，开销极小）
            long totalRows;
            try (ResultSet countRs = stmt.executeQuery("SELECT count(*) FROM " + sqlPath)) {
                totalRows = countRs.next() ? countRs.getLong(1) : 0;
            }
            // 读取前 N 行
            List<List<String>> rows = new ArrayList<>();
            try (ResultSet dataRs = stmt.executeQuery("SELECT * FROM " + sqlPath + " LIMIT " + MAX_ROWS)) {
                ResultSetMetaData meta = dataRs.getMetaData();
                int cols = meta.getColumnCount();
                while (dataRs.next() && rows.size() < MAX_ROWS) {
                    List<String> row = new ArrayList<>(cols);
                    for (int i = 1; i <= cols; i++) {
                        Object val = dataRs.getObject(i);
                        row.add(val == null ? "" : String.valueOf(val));
                    }
                    rows.add(row);
                }
            }
            return buildHtml(fileSize, fields, rows, totalRows);
        }
    }

    /**
    * 构建预览 HTML。
    *
    * @param size      文件字节
    * @param fields    字段名列表
    * @param rows      数据行
    * @param totalRows 总行数
    * @return 完整 HTML
    */
    private String buildHtml(long size, List<String> fields, List<List<String>> rows, long totalRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}")
                .append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}")
                .append("h1{margin:0 0 8px;font-size:20px;font-weight:600}")
                .append(".meta{color:#6b7280;font-size:13px}")
                .append("table{background:#fff;border:1px solid #e5e7eb;border-collapse:collapse;width:100%;font-size:13px}")
                .append("th,td{border:1px solid #eee;padding:6px 12px;text-align:left;white-space:nowrap}")
                .append("th{background:#f3f4f6;position:sticky;top:0}")
                .append("tr:nth-child(even) td{background:#fafafa}")
                .append("td em{color:#9ca3af}")
                .append("</style></head><body>")
                .append("<div class=\"header\"><h1>Parquet 数据预览</h1>")
                .append("<div class=\"meta\">文件大小: ").append(readableSize(size))
                .append(" · 列: ").append(fields.size())
                .append(" · 行: ").append(totalRows >= 0 ? totalRows : "?")
                .append("</div></div>");
        sb.append("<table><thead><tr>");
        for (String field : fields) {
            sb.append("<th>").append(escape(field)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (List<String> row : rows) {
            sb.append("<tr>");
            for (String v : row) {
                sb.append("<td>").append(v.isEmpty() ? "<em>NULL</em>" : escape(v)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (rows.size() >= MAX_ROWS) {
            sb.append("<div class=\"meta\" style=\"margin-top:8px\">仅显示前 ").append(MAX_ROWS).append(" 行</div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
    * 构建不可预览页面。
    *
    * @param message 提示信息
    * @return 完整 HTML
    */
    private String unavailableHtml(String message) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#666;display:flex;justify-content:center;align-items:center;min-height:100vh;font-family:sans-serif}"
                + ".box{text-align:center;padding:40px;border:2px dashed #ddd;border-radius:12px}"
                + "</style></head><body><div class=\"box\">" + escape(message) + "</div></body></html>";
    }

    /**
    * 可读文件大小。
    *
    * @param bytes 字节数
    * @return 可读大小文本
    */
    private String readableSize(long bytes) {
        return com.chua.common.support.utils.FileUtils.readableFileSize(bytes);
    }

    /**
    * HTML 转义。
    *
    * @param text 原始文本
    * @return 转义后文本
    */
    private String escape(String text) {
        return text == null ? "" : StringUtils.escapeHtml(text);
    }
}
