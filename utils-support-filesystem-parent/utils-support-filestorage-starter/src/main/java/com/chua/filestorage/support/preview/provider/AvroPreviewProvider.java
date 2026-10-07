package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Avro 数据文件 (AVRO) 预览提供器。
 * <p>SPI 类型：{@code preview-avro}。基于 {@code org.apache.avro:avro} 纯 Java 读取，
 * 不依赖 Hadoop：从容器头部读取 schema 与前 N 条记录，展示字段与数据表格。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-avro")
public class AvroPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("avro", "avdl", "avsc"); // 支持exts
    private static final int MAX_ROWS = 100; // 最大rows
    private static final long MAX_FILE_SIZE = 64L * 1024 * 1024; // 最大文件大小

    @Override
    public boolean supports(String ext, String mime) {
        if (ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH))) {
            return true;
        }
        return mime != null && mime.toLowerCase(Locale.ENGLISH).contains("avro");
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件过大（超过 64 MB），暂不支持预览")).build();
        }
        String e = ext == null ? "" : ext.toLowerCase(Locale.ENGLISH);
        if ("avsc".equals(e) || "avdl".equals(e)) {
            // Avro IDL / schema 是纯文本，直接按文本展示
            String text = new String(content, StandardCharsets.UTF_8);
            return PreviewResult.builder().htmlContent(buildSchemaHtml(content.length, text)).build();
        }
        try {
            return PreviewResult.builder().htmlContent(previewAvro(content)).build();
        } catch (Exception ex) {
            return PreviewResult.builder()
                    .htmlContent(unavailableHtml("Avro 解析失败: " + escape(ex.getMessage()))).build();
        }
    }

    /**
     * 用 Avro {@code DataFileReader} 读取容器，取 schema 字段与前 N 行记录。
     *
     * @param content 文件字节
     * @return 完整 HTML
     * @throws IOException 解析失败时抛出
     */
    private String previewAvro(byte[] content) throws IOException {
        org.apache.avro.file.DataFileReader<GenericRecord> reader = new org.apache.avro.file.DataFileReader<>(
                new org.apache.avro.file.SeekableByteArrayInput(content),
                new GenericDatumReader<GenericRecord>());
        try {
            Schema schema = reader.getSchema();
            List<String> fields = Wraps.list();
            if (schema != null && schema.getType() == Schema.Type.RECORD) {
                for (Schema.Field f : schema.getFields()) {
                    fields.add(f.name());
                }
            }
            // 容器 header 里的 metadata 常带行数（由 flume/spark 写入时未必有）
            long totalRows = -1L;
            String count = reader.getMetaString("avro.row.count");
            if (count != null) {
                try {
                    totalRows = Long.parseLong(count.trim());
                } catch (NumberFormatException ignore) {
                    totalRows = -1L;
                }
            }
            List<List<String>> rows = Wraps.list();
            long scanned = 0L;
            while (reader.hasNext() && rows.size() < MAX_ROWS) {
                GenericRecord rec = reader.next();
                rows.add(toRow(rec, fields));
                scanned++;
            }
            if (totalRows < 0) {
                // 没有元数据行数时，只统计已扫描到的量（避免整文件扫描拖垮大文件）
                totalRows = scanned;
            }
            return buildHtml(content.length, fields, rows, totalRows, reader.getMetaString("avro.schema") != null);
        } finally {
            reader.close();
        }
    }

    /**
     * 记录转字符串行（与字段顺序对齐；嵌套结构序列化为 JSON 风格文本）。
     *
     * @param rec    记录
     * @param fields 字段名
     * @return 行值
     */
    private List<String> toRow(GenericRecord rec, List<String> fields) {
        List<String> row = Wraps.list(fields.size());
        for (String name : fields) {
            Object v = rec == null ? null : rec.get(name);
            row.add(valueToString(v));
        }
        return row;
    }

    /**
     * 任意 Avro 值转可读文本。
     *
     * @param v 值
     * @return 文本；null 返回空串
     */
    private String valueToString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof ByteBuffer bb) {
            ByteBuffer dup = bb.duplicate();
            byte[] bytes = new byte[dup.remaining()];
            dup.get(bytes);
            return "<" + bytes.length + " bytes>";
        }
        if (v instanceof GenericRecord rec) {
            StringBuilder sb = new StringBuilder("{");
            for (Schema.Field f : rec.getSchema().getFields()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(f.name()).append("=").append(valueToString(rec.get(f.name())));
            }
            return sb.append("}").toString();
        }
        if (v instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : it) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(valueToString(o));
            }
            return sb.append("]").toString();
        }
        if (v instanceof java.util.Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append("=").append(valueToString(e.getValue()));
            }
            return sb.append("}").toString();
        }
        return String.valueOf(v);
    }

    /**
     * 构建预览 HTML。
     *
     * @param size      文件字节
     * @param fields    字段名列表
     * @param rows      数据行
     * @param totalRows 总行数（未知时为已扫描行数）
     * @param hasSchema 是否读到 schema
     * @return 完整 HTML
     */
    private String buildHtml(long size, List<String> fields, List<List<String>> rows, long totalRows, boolean hasSchema) {
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
                .append("<div class=\"header\"><h1>Avro 数据预览</h1>")
                .append("<div class=\"meta\">文件大小: ").append(readableSize(size))
                .append(" · 字段: ").append(fields.size())
                .append(" · 行: ").append(totalRows >= 0 ? totalRows : "?")
                .append(hasSchema ? "" : " · <em>未读到 schema</em>")
                .append("</div></div>");
        if (fields.isEmpty()) {
            sb.append(unavailableHtml("未能读取 Avro schema"));
            return sb.toString();
        }
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
     * Avro IDL / schema 文本页。
     *
     * @param size 文件字节
     * @param text 文本内容
     * @return 完整 HTML
     */
    private String buildSchemaHtml(long size, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#1e1e1e;color:#d4d4d4;font-family:'Cascadia Code','Fira Code',Consolas,monospace;font-size:13px;line-height:1.5}")
                .append(".badge{position:sticky;top:0;display:inline-block;padding:4px 12px;font-size:12px;background:#007acc;color:#fff;border-radius:0 0 6px 0;font-family:sans-serif}")
                .append("pre{margin:0;padding:16px;overflow:auto}")
                .append("</style></head><body>")
                .append("<div class=\"badge\">avro-schema · ").append(readableSize(size)).append("</div>")
                .append("<pre>").append(escape(text)).append("</pre></body></html>");
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
