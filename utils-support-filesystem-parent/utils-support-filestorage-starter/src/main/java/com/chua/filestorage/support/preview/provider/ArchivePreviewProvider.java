package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 压缩包 / 压缩文档预览提供器，支持 zip、rar、tar、gz、bz2、xz、7z、zst 等格式。
 * <p>SPI 类型：{@code preview-archive}。输出 HTML 表格，列出条目名、大小、修改时间。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi("preview-archive")
public class ArchivePreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的压缩包扩展名（小写）
     */
    private static final Set<String> SUPPORTED = Set.of(
            "zip", "rar", "tar", "gz", "tgz", "tar.gz", "bz2", "tbz2", "tar.bz2",
            "xz", "txz", "tar.xz", "7z", "zst", "tzst", "tar.zst", "lz4", "tar.lz4", "lzma", "tar.lzma");

    /**
     * 纯压缩流扩展名（不视为容器，无条目概念）
     */
    private static final Set<String> COMPRESSOR_ONLY = Set.of("gz", "bz2", "xz", "zst", "lz4", "lzma");

    /**
     * @param ext  文件扩展名
     * @param mime MIME 类型（当前忽略）
     * @return true 表示支持预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        if (ext == null) {
            return false;
        }
        String e = ext.toLowerCase(Locale.ENGLISH);
        return SUPPORTED.contains(e);
    }

    @Override
    /** Preview */
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String e = ext.toLowerCase(Locale.ENGLISH);
        List<EntryInfo> entries = new ArrayList<>();
        long totalSize = 0;
        int dirCount = 0;

        if ("7z".equals(e)) {
            Path tmp = Files.createTempFile("archive-7z-", ".7z");
            try {
                Files.write(tmp, content);
                try (SevenZFile sz = SevenZFile.builder().setPath(tmp).get()) {
                    org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
                    while ((entry = sz.getNextEntry()) != null) {
                        if (entry.isDirectory()) { dirCount++; continue; }
                        entries.add(new EntryInfo(
                                entry.getName(), entry.getSize(), entry.getLastModifiedDate(), false));
                        totalSize += entry.getSize();
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } else {
            boolean isCompressorOnly = COMPRESSOR_ONLY.contains(e);
            if (isCompressorOnly) {
                String name = "content." + e;
                entries.add(new EntryInfo(name, content.length, null, true));
                totalSize = content.length;
            } else {
                try {
                    try (InputStream in = new ByteArrayInputStream(content);
                         InputStream buffered = new java.io.BufferedInputStream(wrapDecompressor(in, e));
                         ArchiveInputStream ais = new ArchiveStreamFactory()
                                 .createArchiveInputStream(buffered)) {
                        ArchiveEntry entry;
                        while ((entry = ais.getNextEntry()) != null) {
                            if (entry.isDirectory()) { dirCount++; continue; }
                            entries.add(new EntryInfo(
                                    entry.getName(), entry.getSize(), entry.getLastModifiedDate(), false));
                            totalSize += entry.getSize();
                        }
                    }
                } catch (Exception ex) {
                    throw new IOException("Failed to read archive", ex);
                }
            }
        }

        return PreviewResult.builder()
                .htmlContent(buildHtml(ext, entries, dirCount, totalSize))
                .embeddedCss("body{margin:0;background:#f8f9fa;color:#1a1a2e;" +
                        "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}" +
                        ".header{padding:16px 24px;background:#fff;border-bottom:1px solid #e5e7eb}" +
                        ".header h2{margin:0;font-size:16px;font-weight:600}" +
                        ".header .meta{font-size:13px;color:#6b7280;margin-top:4px}" +
                        "table{width:100%;border-collapse:collapse}" +
                        "th{text-align:left;padding:8px 16px;font-size:12px;font-weight:600;" +
                        "color:#6b7280;text-transform:uppercase;letter-spacing:0.05em;" +
                        "border-bottom:1px solid #e5e7eb;background:#f9fafb}" +
                        "td{padding:6px 16px;font-size:13px;border-bottom:1px solid #f3f4f6}" +
                        "tr:hover td{background:#f3f4f6}" +
                        ".name{font-family:'Cascadia Code',Consolas,monospace;font-size:12px}" +
                        ".size{text-align:right;font-variant-numeric:tabular-nums}" +
                        ".ext{display:inline-block;padding:1px 6px;border-radius:3px;" +
                        "font-size:11px;font-weight:500;background:#e5e7eb;color:#374151;margin-right:6px}")
                .build();
    }

    /** WrapDecompressor */
    private static InputStream wrapDecompressor(InputStream in, String ext) throws IOException {
        String compType = switch (ext) {
            case "tgz", "tar.gz" -> "gz";
            case "tbz2", "tar.bz2" -> "bzip2";
            case "txz", "tar.xz" -> "xz";
            case "tzst", "tar.zst" -> "zstd";
            default -> null;
        };
        if (compType != null) {
            try {
                return new CompressorStreamFactory().createCompressorInputStream(compType, in);
            } catch (Exception e) {
                throw new IOException("Failed to create decompressor: " + compType, e);
            }
        }
        return in;
    }

    /** 构建Html */
    private String buildHtml(String ext, List<EntryInfo> entries, int dirs, long totalSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"header\"><h2>").append(ext.toUpperCase(Locale.ENGLISH))
                .append(" 存档预览</h2>")
                .append("<div class=\"meta\">共 ").append(entries.size())
                .append(" 个文件");
        if (dirs > 0) {
            sb.append("，总计 ").append(formatSize(totalSize)).append("</div></div>");
        }
        sb.append("<table><thead><tr><th>文件名</th><th>大小</th><th>修改日期</th></tr></thead><tbody>");
        for (EntryInfo e : entries) {
            sb.append("<tr><td class=\"name\">");
            String eExt = extFromName(e.name);
            if (eExt != null) {
                sb.append("<span class=\"ext\">").append(escapeHtml(eExt)).append("</span>");
            }
            sb.append(escapeHtml(e.name)).append("</td>");
            sb.append("<td class=\"size\">").append(e.compressOnly ? "-" : formatSize(e.size)).append("</td>");
            sb.append("<td>").append(e.date != null ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(e.date) : "-")
                    .append("</td></tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** ExtFromName */
    private static String extFromName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ENGLISH) : null;
    }

    /** 格式化获取大小 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** EscapeHtml */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** EntryInfo */
    private record EntryInfo(String name, long size, Date date, boolean compressOnly) {}
}
