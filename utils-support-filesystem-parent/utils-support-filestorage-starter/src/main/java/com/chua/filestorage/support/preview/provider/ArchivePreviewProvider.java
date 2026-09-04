package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.DateUtils;
import com.chua.common.support.utils.FileUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 压缩包 / 压缩文档预览提供器，支持 zip、rar、tar、gz、bz2、xz、7z、zst 等格式。
 * <p>SPI 类型：{@code preview-archive}。输出树形 HTML 结构，按目录层级展示压缩包条目，并列出大小、修改时间。</p>
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
            "xz", "txz", "tar.xz", "7z", "zst", "tzst", "tar.zst", "lz4", "tar.lz4", "lzma", "tar.lzma",
            "jar", "war", "ear", "apk", "ipa", "zipx");

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

    /**
     * 生成压缩包预览页面。
     *
     * @param content 压缩包字节内容
     * @param ext     文件扩展名
     * @param mime    MIME 类型（当前忽略）
     * @return 预览结果，包含树形 HTML 与内联样式
     * @throws IOException 读取压缩包失败
     */
    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content == null || ext == null) {
            throw new IllegalArgumentException("content and ext must not be null");
        }
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
                        if (entry.isDirectory()) {
                            dirCount++;
                            continue;
                        }
                        Date date = safeLastModifiedDate(entry);
                        entries.add(new EntryInfo(entry.getName(), entry.getSize(), date, false));
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
                            if (entry.isDirectory()) {
                                dirCount++;
                                continue;
                            }
                            entries.add(new EntryInfo(
                                    entry.getName(), entry.getSize(), entry.getLastModifiedDate(), false));
                            totalSize += Math.max(0, entry.getSize());
                        }
                    }
                } catch (Exception ex) {
                    String comp = compressorOf(e);
                    if (comp != null) {
                        entries.clear();
                        dirCount = 0;
                        entries.add(new EntryInfo("content." + ext, content.length, null, true));
                        totalSize = content.length;
                    } else {
                        throw new IOException("Failed to read archive", ex);
                    }
                }
            }
        }

        return PreviewResult.builder()
                .htmlContent(buildHtml(ext, entries, dirCount, totalSize))
                .embeddedJs(buildDrillJs())
                .embeddedCss("body{margin:0;background:#f8f9fa;color:#1a1a2e;" +
                        "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}" +
                        ".header{padding:16px 24px;background:#fff;border-bottom:1px solid #e5e7eb}" +
                        ".header h2{margin:0;font-size:16px;font-weight:600}" +
                        ".header .meta{font-size:13px;color:#6b7280;margin-top:4px}" +
                        ".tree{padding:8px 16px}" +
                        ".dir,.file{padding:5px 8px;font-size:13px;border-radius:4px;cursor:default;" +
                        "font-family:'Cascadia Code',Consolas,monospace}" +
                        ".dir{color:#2563eb;font-weight:600}" +
                        ".file{color:#374151;padding-left:24px;cursor:pointer}" +
                        ".file:hover{background:#f0f7ff}" +
                        ".icon{margin-right:6px}" +
                        ".meta-right{float:right;color:#9ca3af;font-size:12px;font-family:sans-serif}")
                .build();
    }

    /**
     * 构建压缩包下钻脚本：点击文件条目跳转至内层预览。
     *
     * @return 内联 JS 代码
     */
    private String buildDrillJs() {
        return "window.addEventListener('DOMContentLoaded',function(){"
                + "var files=document.querySelectorAll('.file[data-path]');"
                + "for(var i=0;i<files.length;i++){(function(el){"
                + "el.addEventListener('click',function(){"
                + "var p=el.getAttribute('data-path');if(!p)return;"
                + "var base=location.pathname.replace(/\\/+$/,'');"
                + "var sep=base.indexOf('?')>=0?'&':'?';"
                + "location.href=base+sep+'preview&inner='+encodeURIComponent(p);"
                + "});})(files[i]);}"
                + "});";
    }

    /**
     * 从压缩包中提取指定路径的文件内容。
     *
     * @param content 压缩包字节
     * @param ext     压缩包扩展名（小写）
     * @param path    目标条目完整路径
     * @return 条目内容字节；条目不存在或解析失败时返回 null
     */
    public static byte[] extractFile(byte[] content, String ext, String path) {
        if (content == null || ext == null || path == null) {
            return null;
        }
        String e = ext.toLowerCase(Locale.ENGLISH);
        if ("7z".equals(e)) {
            return extractFrom7z(content, path);
        }
        try (InputStream in = new ByteArrayInputStream(content);
             InputStream buffered = new java.io.BufferedInputStream(wrapDecompressor(in, e));
             ArchiveInputStream ais = new ArchiveStreamFactory().createArchiveInputStream(buffered)) {
            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (path.equals(entry.getName())) {
                    return ais.readAllBytes();
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 从 7z 压缩包中提取指定路径的文件内容。
     *
     * @param content 压缩包字节
     * @param path    目标条目完整路径
     * @return 条目内容字节；条目不存在时返回 null
     */
    private static byte[] extractFrom7z(byte[] content, String path) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("archive-extract-", ".7z");
            Files.write(tmp, content);
            try (SevenZFile sz = SevenZFile.builder().setPath(tmp).get()) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = sz.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (path.equals(entry.getName())) {
                        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                            int len;
                            while ((len = sz.read(buffer)) > 0) {
                                baos.write(buffer, 0, len);
                            }
                            return baos.toByteArray();
                        }
                    }
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 安全获取 7z 条目的最后修改时间。
     * 部分 7z 条目未记录时间戳，调用方直接取值会抛出 {@link UnsupportedOperationException}。
     *
     * @param entry 7z 归档条目
     * @return 修改时间；无时间戳时返回 null
     */
    private static Date safeLastModifiedDate(org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry) {
        try {
            return entry.getLastModifiedDate();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * 按扩展名将纯压缩流包装为解压流（如 tar.gz 解 gzip、tar.xz 解 xz）。
     * 非复合扩展名原样返回。
     *
     * @param in  原始输入流
     * @param ext 文件扩展名
     * @return 解压后的输入流
     * @throws IOException 创建解压器失败
     */
    private static InputStream wrapDecompressor(InputStream in, String ext) throws IOException {
        String compType = compressorOf(ext);
        if (compType != null) {
            try {
                return new CompressorStreamFactory().createCompressorInputStream(compType, in);
            } catch (Exception e) {
                throw new IOException("Failed to create decompressor: " + compType, e);
            }
        }
        return in;
    }

    /**
     * 返回扩展名对应的解压器类型；无对应解压器时返回 null。
     *
     * @param ext 文件扩展名
     * @return 解压器类型（如 gz、bzip2、xz、zstd、lz4、lzma）；不支持时返回 null
     */
    private static String compressorOf(String ext) {
        return switch (ext) {
            case "tgz", "tar.gz" -> "gz";
            case "tbz2", "tar.bz2" -> "bzip2";
            case "txz", "tar.xz" -> "xz";
            case "tzst", "tar.zst" -> "zstd";
            case "tar.lz4" -> "lz4";
            case "tar.lzma" -> "lzma";
            default -> null;
        };
    }

    /**
     * 构建树形 HTML：目录按字典序展示，目录下文件缩进排列，根目录文件紧随其后。
     *
     * @param ext       文件扩展名（用于标题展示）
     * @param entries   归档条目列表
     * @param dirs      目录数量
     * @param totalSize 文件总大小
     * @return 树形 HTML 片段
     */
    private String buildHtml(String ext, List<EntryInfo> entries, int dirs, long totalSize) {
        java.util.Map<String, List<EntryInfo>> dirMap = new java.util.TreeMap<>();
        List<EntryInfo> rootFiles = new ArrayList<>();
        for (EntryInfo e : entries) {
            int slash = e.name.lastIndexOf('/');
            if (slash > 0) {
                String dir = e.name.substring(0, slash);
                dirMap.computeIfAbsent(dir, k -> new ArrayList<>()).add(e);
            } else {
                rootFiles.add(e);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"header\"><h2>").append(ext.toUpperCase(Locale.ENGLISH))
                .append(" 存档预览</h2>")
                .append("<div class=\"meta\">共 ").append(entries.size()).append(" 个文件");
        if (dirs > 0) {
            sb.append("，").append(dirs).append(" 个目录");
        }
        sb.append("，总计 ").append(FileUtils.readableFileSize(totalSize)).append("</div></div>");
        sb.append("<div class=\"tree\">");
        for (java.util.Map.Entry<String, List<EntryInfo>> de : dirMap.entrySet()) {
            sb.append("<div class=\"dir\" data-path=\"").append(StringUtils.escapeHtml(de.getKey())).append("\"><span class=\"icon\">📁</span>")
                    .append(StringUtils.escapeHtml(de.getKey())).append("</div>");
            for (EntryInfo fe : de.getValue()) {
                String fname = fe.name.substring(de.getKey().length() + 1);
                renderFile(sb, fname, fe);
            }
        }
        for (EntryInfo fe : rootFiles) {
            renderFile(sb, fe.name, fe);
        }
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * 渲染单个文件行（文件图标、名称、大小与修改时间）。
     *
     * @param sb   输出缓冲区
     * @param name 文件显示名
     * @param fe   文件条目信息
     */
    private void renderFile(StringBuilder sb, String name, EntryInfo fe) {
        String eExt = extFromName(name);
        sb.append("<div class=\"file\" data-path=\"").append(StringUtils.escapeAttr(fe.name)).append("\"><span class=\"icon\">");
        sb.append(eExt != null ? StringUtils.escapeHtml(eExt) : "📄");
        sb.append("</span>").append(StringUtils.escapeHtml(name));
        sb.append("<span class=\"meta-right\">");
        sb.append(fe.compressOnly ? "-" : FileUtils.readableFileSize(fe.size));
        if (fe.date != null) {
            String formatted = DateUtils.format(fe.date, "yyyy-MM-dd HH:mm");
            sb.append(" &middot; ").append(formatted != null ? formatted : "-");
        }
        sb.append("</span></div>");
    }

    /**
     * 从文件名提取小写扩展名（不含点号）。
     *
     * @param name 文件名
     * @return 扩展名；无扩展名时返回 null
     */
    private static String extFromName(String name) {
        String ext = FileUtils.getExtension(name);
        return ext == null || ext.isEmpty() ? null : ext.toLowerCase(Locale.ENGLISH);
    }

    /**
     * 压缩包内条目信息。
     *
     * @param name         条目完整路径
     * @param size         条目大小
     * @param date         最后修改时间（可为空）
     * @param compressOnly 是否为纯压缩流条目（无文件信息）
     */
    private record EntryInfo(String name, long size, Date date, boolean compressOnly) {}
}
