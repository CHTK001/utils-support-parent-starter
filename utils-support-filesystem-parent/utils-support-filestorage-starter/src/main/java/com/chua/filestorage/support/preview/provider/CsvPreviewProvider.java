package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * CSV 预览提供者：将 CSV 转为 HTML 表格。
 *
 * @author CH
 * @since 2024/12/28
 */
@Spi("preview-csv")
public class CsvPreviewProvider implements FileStoragePreviewProvider {

    @Override
    public boolean supports(String extension, String mimeType) {
        return "csv".equalsIgnoreCase(extension) || "text/csv".equals(mimeType);
    }

    @Override
    public PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException {
        String csv = new String(content, StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        StringBuilder table = new StringBuilder("<table>\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String tag = i == 0 ? "th" : "td";
            table.append("<tr>");
            for (String cell : line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1)) {
                String val = cell.trim();
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                table.append("<").append(tag).append(">")
                        .append(escapeHtml(val))
                        .append("</").append(tag).append(">");
            }
            table.append("</tr>\n");
        }
        table.append("</table>");

        String css = "table{border-collapse:collapse;width:100%;font-family:monospace;font-size:14px}"
                + "th,td{border:1px solid #ccc;padding:6px 10px;text-align:left;white-space:nowrap}"
                + "th{background:#f5f5f5;font-weight:600;position:sticky;top:0}"
                + "tr:hover{background:#fafafa}"
                + "body{margin:0;padding:16px}";
        return PreviewResult.builder()
                .htmlContent(table.toString())
                .embeddedCss(css)
                .build();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
