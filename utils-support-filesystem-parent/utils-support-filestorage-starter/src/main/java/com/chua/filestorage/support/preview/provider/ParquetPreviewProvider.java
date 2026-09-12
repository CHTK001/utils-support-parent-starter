package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parquet 列式数据文件 (PARQUET) 预览提供器。
 * <p>SPI 类型：{@code preview-parquet}。通过 Apache Parquet 读取列式数据，
   * 展示 模式 与前 100 行数据。</p>
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
        Path tmp = Files.createTempFile("preview-parquet-", ".parquet");
        try {
            Files.write(tmp, content);
            String html = previewParquet(tmp.toFile());
            return PreviewResult.builder().htmlContent(html).build();
        } catch (IOException e) {
            return PreviewResult.builder().htmlContent(unavailableHtml("Parquet 解析失败: " + escape(e.getMessage()))).build();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 解析 Parquet 文件并构建预览 HTML。
     *
     * @param file Parquet 文件
     * @return 完整 HTML
     * @throws IOException 解析失败时抛出
     */
    private String previewParquet(File file) throws IOException {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", "file:///");
        conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());

        List<String> fields;
        List<List<String>> rows = new ArrayList<>();
        long totalRows;
        org.apache.hadoop.fs.Path hPath = new org.apache.hadoop.fs.Path(file.toURI());

        try (ParquetFileReader reader = ParquetFileReader.open(conf, hPath)) {
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            fields = new ArrayList<>();
            for (Type t : schema.getFields()) {
                fields.add(t.getName());
            }
        }

        Configuration readerConf = new Configuration(conf);
        try (ParquetReader<Group> reader = ParquetReader.builder(new GroupReadSupport(), hPath)
                .withConf(readerConf)
                .build()) {
            Group row;
            while ((row = reader.read()) != null && rows.size() < MAX_ROWS) {
                List<String> values = new ArrayList<>();
                for (String field : fields) {
                    values.add(readValue(row, field));
                }
                rows.add(values);
            }
        }
        totalRows = rowCount(hPath, conf);
        return buildHtml(file.length(), fields, rows, totalRows);
    }

    /**
     * 统计 Parquet 文件总行数。
     *
     * @param path Hadoop 路径
     * @param conf Hadoop 配置
     * @return 总行数；统计失败时返回 -1
     */
    private long rowCount(org.apache.hadoop.fs.Path path, Configuration conf) {
        try (ParquetFileReader reader = ParquetFileReader.open(conf, path)) {
            return reader.getRecordCount();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 读取指定字段值。
     *
     * @param row 当前行
     * @param field 字段名
     * @return 字段文本；无值时返回空串
     */
    private String readValue(Group row, String field) {
        try {
            int idx = row.getType().getFieldIndex(field);
            if (row.getFieldRepetitionCount(idx) == 0) {
                return "";
            }
            return row.getValueToString(idx, 0);
        } catch (Exception e) {
            return "";
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