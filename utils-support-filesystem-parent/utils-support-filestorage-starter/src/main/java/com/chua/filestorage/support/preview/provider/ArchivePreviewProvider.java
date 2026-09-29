package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.DateUtils;
import com.chua.common.support.utils.FileUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 压缩包 / 压缩文档预览提供器，支持 压缩、rar、焦油、gz、bz2、xz、7z、zst 等格式。
 * <p>SPI 类型：{@code preview-archive}。输出树形 HTML 结构，按目录层级展示压缩包条目，并列出大小、修改时间。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
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
     * ZIP 容器家族（走中央目录读取，理由见 {@link #scanZip}）。
     *
     * <p>与 {@link #SUPPORTED} 的差别：这里只收「物理格式确实是 ZIP」的扩展名。
     * {@code jar/war/apk} 本质是 zip，故一并在内。</p>
     */
    private static final Set<String> ZIP_CONTAINER = Set.of("zip", "zipx", "jar", "war", "ear", "apk", "ipa");

    /**
     * 单条目解压结果最大字节数，防御 7z/压缩条目解压炸弹拖垮内存
     */
    private static final int MAX_EXTRACT_ENTRY_BYTES = 128 * 1024 * 1024;

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
        ArchiveScan scan = scan(content, ext);
        return PreviewResult.builder()
                .htmlContent(buildHtml(ext, scan))
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
     * 扫描压缩包，产出与展示形态无关的扁平条目清单。
     *
     * <p>这是压缩包结构解析的唯一入口，服务端 HTML 预览页与
     * {@code ?format=archive} JSON 接口共用同一份实现，避免两处对
     * 「7z 单独走 SevenZFile」「纯压缩流降级为单条目」「补全隐式目录」
     * 的处理逐渐分叉。</p>
     *
     * <p>返回值已做两件规范化：</p>
     * <ul>
     *   <li><b>补全隐式目录</b>：现实中大量压缩包只写文件条目、不写目录条目，
     *       此处按路径分隔符补出全部中间目录，调用方拿到的清单即可直接成树。</li>
     *   <li><b>去重</b>：同一路径重复出现时保留首次出现的记录。</li>
     * </ul>
     *
     * @param content 压缩包字节内容
     * @param ext     文件扩展名（大小写不敏感）
     * @return 扫描结果（含条目清单与统计）
     * @throws IOException 解析压缩包失败
     */
    public static ArchiveScan scan(byte[] content, String ext) throws IOException {
        if (content == null || ext == null) {
            throw new IllegalArgumentException("content and ext must not be null");
        }
        String e = ext.toLowerCase(Locale.ENGLISH);
        List<RawEntry> raw = new ArrayList<>();

        if ("7z".equals(e)) {
            Path tmp = Files.createTempFile("archive-7z-", ".7z");
            try {
                Files.write(tmp, content);
                try (SevenZFile sz = SevenZFile.builder().setPath(tmp).get()) {
                    org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
                    while ((entry = sz.getNextEntry()) != null) {
                        Date date = safeLastModifiedDate(entry);
                        raw.add(new RawEntry(normalizePath(entry.getName()),
                                entry.isDirectory(), entry.getSize(), -1L,
                                date == null ? 0L : date.getTime()));
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } else if (COMPRESSOR_ONLY.contains(e)) {
            // 纯压缩流（gzip / bzip2 / xz …）没有容器结构，无条目概念，
            // 按「解出 1 个同名文件」呈现，这是压缩流预览唯一说得通的形态。
            raw.add(new RawEntry(normalizePath("content." + e), false, content.length, content.length, 0L));
        } else if (isZipContainer(e)) {
            raw.addAll(scanZip(content));
        } else {
            try {
                try (InputStream in = new ByteArrayInputStream(content);
                     InputStream buffered = new java.io.BufferedInputStream(wrapDecompressor(in, e));
                     ArchiveInputStream ais = new ArchiveStreamFactory()
                             .createArchiveInputStream(buffered)) {
                    ArchiveEntry entry;
                    while ((entry = ais.getNextEntry()) != null) {
                        Date date = entry.getLastModifiedDate();
                        raw.add(new RawEntry(normalizePath(entry.getName()),
                                entry.isDirectory(), entry.getSize(), safeCompressedSize(entry),
                                date == null ? 0L : date.getTime()));
                    }
                }
            } catch (Exception ex) {
                String comp = compressorOf(e);
                if (comp == null) {
                    throw new IOException("Failed to read archive", ex);
                }
                // 双段复合后缀（如 tar.gz）若按容器解析失败，退回按纯压缩流处理
                raw.clear();
                raw.add(new RawEntry(normalizePath("content." + ext), false, content.length, content.length, 0L));
            }
        }
        return normalize(raw);
    }

    /**
     * 判断扩展名是否为 ZIP 容器家族。
     *
     * <p>ZIP 容器必须走 {@link #scanZip} 而非流式 {@link ArchiveInputStream}，
     * 原因见该方法注释。</p>
     *
     * @param ext 小写扩展名
     * @return true 表示是 ZIP 容器
     */
    private static boolean isZipContainer(String ext) {
        return ZIP_CONTAINER.contains(ext);
    }

    /**
     * 以中央目录（central directory）方式读取 ZIP 条目。
     *
     * <p><b>为什么 ZIP 不能走流式 {@link ArchiveInputStream}</b>：
     * {@code ZipArchiveInputStream} 逐条顺序解压本地文件头，只知道当前条目的
     * 压缩数据流在哪里，<b>不解析中央目录</b>，因此 {@code getSize()} /
     * {@code getCompressedSize()} 一律返回 {@code -1}。表现为「压缩包预览里
     * 所有文件大小都是 0 / 空」，且总大小、压缩率全部失真——而这恰恰是
     * 压缩包浏览器最需要展示的信息。</p>
     *
     * <p>{@link ZipFile} 读中央目录，元数据完整。内容已全量在内存里，
     * 用 {@link SeekableInMemoryByteChannel} 包装即可，不落临时文件。</p>
     *
     * <p>读失败（损坏的 ZIP）时退回流式读取，由调用方决定如何报错，
     * 避免中央目录缺失就直接放弃整个预览。</p>
     *
     * @param content ZIP 字节
     * @return 原始条目列表
     * @throws IOException 中央目录不可读且流式回退也失败
     */
    private static List<RawEntry> scanZip(byte[] content) throws IOException {
        List<RawEntry> raw = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(new SeekableInMemoryByteChannel(content))) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                Date date = entry.getLastModifiedDate();
                raw.add(new RawEntry(normalizePath(entry.getName()),
                        entry.isDirectory(), entry.getSize(), entry.getCompressedSize(),
                        date == null ? 0L : date.getTime()));
            }
            return raw;
        } catch (Exception ex) {
            log.debug("ZIP 中央目录读取失败，回退流式解析: {}", ex.getMessage());
        }
        // 回退：流式解析至少还能列出条目名，只是大小不可知
        try (ArchiveInputStream ais = new ArchiveStreamFactory()
                .createArchiveInputStream(new ByteArrayInputStream(content))) {
            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                Date date = entry.getLastModifiedDate();
                raw.add(new RawEntry(normalizePath(entry.getName()),
                        entry.isDirectory(), -1L, -1L, date == null ? 0L : date.getTime()));
            }
        } catch (Exception ex) {
            throw new IOException("Failed to read archive", ex);
        }
        return raw;
    }

    /**
     * 规范化原始条目：去重并补全隐式目录。
     *
     * @param raw 原始条目（顺序即压缩包内声明顺序）
     * @return 规范化后的扫描结果
     */
    private static ArchiveScan normalize(List<RawEntry> raw) {
        Map<String, ArchiveEntryItem> byPath = new LinkedHashMap<>();
        // 先落文件条目：文件路径即事实，隐式目录由文件推导，避免目录条目缺失导致树断裂
        for (RawEntry entry : raw) {
            if (!entry.directory()) {
                byPath.putIfAbsent(entry.path(), entry.toItem());
            }
        }
        // 再落显式目录条目与补全出来的中间目录
        for (RawEntry entry : raw) {
            if (entry.directory()) {
                byPath.putIfAbsent(entry.path(), entry.toItem());
            }
        }
        for (String path : new ArrayList<>(byPath.keySet())) {
            int slash = path.lastIndexOf('/');
            while (slash > 0) {
                String parent = path.substring(0, slash);
                byPath.putIfAbsent(parent, new ArchiveEntryItem(parent,
                        parent.substring(parent.lastIndexOf('/') + 1), true, 0L, 0L, 0L));
                slash = parent.lastIndexOf('/');
            }
        }
        List<ArchiveEntryItem> entries = new ArrayList<>(byPath.size());
        int fileCount = 0;
        int dirCount = 0;
        long totalSize = 0L;
        for (ArchiveEntryItem item : byPath.values()) {
            entries.add(item);
            if (item.directory()) {
                dirCount++;
            } else {
                fileCount++;
                totalSize += Math.max(0L, item.size());
            }
        }
        return new ArchiveScan(entries, fileCount, dirCount, totalSize);
    }

    /**
     * 规整条目路径：去掉前导斜杠与结尾斜杠，空路径视为无效（返回空串）。
     *
     * <p>不同归档实现对目录条目路径的写法不一致（{@code a/b/}、{@code /a/b}），
     * 不归一化会让同一目录在树里裂成两个节点。</p>
     *
     * @param path 原始路径
     * @return 规整后的路径；无效时返回空串
     */
    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 安全读取条目压缩后大小。
     *
     * <p>压缩后大小并非 {@link ArchiveEntry} 的通用能力——只有 {@link ZipArchiveEntry}
     * 这类记录了中央目录元数据的实现才提供。因此这里按类型探测，未记录时返回
     * {@code -1} 表示未知，调用方须按「未知」处理，不得当成 0 参与求和。</p>
     *
     * @param entry 归档条目
     * @return 压缩后大小；未记录时返回 -1
     */
    private static long safeCompressedSize(ArchiveEntry entry) {
        if (entry instanceof ZipArchiveEntry zipEntry) {
            try {
                return zipEntry.getCompressedSize();
            } catch (Exception e) {
                return -1L;
            }
        }
        return -1L;
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
     * @return 条目内容字节；条目不存在或解析失败时返回 空
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
                    return readEntryLimited(ais, MAX_EXTRACT_ENTRY_BYTES);
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
     * @return 条目内容字节；条目不存在时返回 空
     */
    private static byte[] extractFrom7z(byte[] content, String path) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("archive-extract-", ".7z");
            Files.write(tmp, content);
            try (SevenZFile sz = SevenZFile.builder().setPath(tmp).get()) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
                while ((entry = sz.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (path.equals(entry.getName())) {
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(MAX_EXTRACT_ENTRY_BYTES)) {
                            byte[] buffer = new byte[8192];
                            int total = 0;
                            int len;
                            while ((len = sz.read(buffer, 0, Math.min(buffer.length, MAX_EXTRACT_ENTRY_BYTES - total))) > 0) {
                                baos.write(buffer, 0, len);
                                total += len;
                                if (total >= MAX_EXTRACT_ENTRY_BYTES) {
                                    break;
                                }
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
     * @return 修改时间；无时间戳时返回 空
     */
    private static Date safeLastModifiedDate(org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry) {
        try {
            return entry.getLastModifiedDate();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * 带大小上限地读取压缩条目内容，超过上限时截断返回。
     *
     * @param in     压缩条目输入流
     * @param limit  最大字节数
     * @return 条目内容字节；超过上限时仅返回前 limit 字节
     */
    private static byte[] readEntryLimited(InputStream in, int limit) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(limit)) {
            byte[] buffer = new byte[8192];
            int len;
            int total = 0;
            while ((len = in.read(buffer, 0, Math.min(buffer.length, limit - total))) > 0) {
                baos.write(buffer, 0, len);
                total += len;
                if (total >= limit) {
                    break;
                }
            }
            return baos.toByteArray();
        }
    }

    /**
     * 按扩展名将纯压缩流包装为解压流（如 焦油.gz 解 gzip、焦油.xz 解 xz）。
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
     * 返回扩展名对应的解压器类型；无对应解压器时返回 空。
     *
     * @param ext 文件扩展名
     * @return 解压器类型（如 gz、bzip2、xz、zstd、lz4、lzma）；不支持时返回 空
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
     * @param ext  文件扩展名（用于标题展示）
     * @param scan 压缩包扫描结果
     * @return 树形 HTML 片段
     */
    private String buildHtml(String ext, ArchiveScan scan) {
        Map<String, List<ArchiveEntryItem>> dirMap = new java.util.TreeMap<>();
        List<ArchiveEntryItem> rootFiles = new ArrayList<>();
        for (ArchiveEntryItem e : scan.entries()) {
            if (e.directory()) {
                // 目录条目已由左侧目录层级承载，正文只列文件，避免同一目录既当标题又当条目渲染两次
                continue;
            }
            int slash = e.path().lastIndexOf('/');
            if (slash > 0) {
                String dir = e.path().substring(0, slash);
                dirMap.computeIfAbsent(dir, k -> new ArrayList<>()).add(e);
            } else {
                rootFiles.add(e);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"header\"><h2>").append(ext.toUpperCase(Locale.ENGLISH))
                .append(" 存档预览</h2>")
                .append("<div class=\"meta\">共 ").append(scan.fileCount()).append(" 个文件");
        if (scan.dirCount() > 0) {
            sb.append("，").append(scan.dirCount()).append(" 个目录");
        }
        sb.append("，总计 ").append(FileUtils.readableFileSize(scan.totalSize())).append("</div></div>");
        sb.append("<div class=\"tree\">");
        for (Map.Entry<String, List<ArchiveEntryItem>> de : dirMap.entrySet()) {
            sb.append("<div class=\"dir\" data-path=\"").append(StringUtils.escapeHtml(de.getKey())).append("\"><span class=\"icon\">📁</span>")
                    .append(StringUtils.escapeHtml(de.getKey())).append("</div>");
            for (ArchiveEntryItem fe : de.getValue()) {
                String fname = fe.path().substring(de.getKey().length() + 1);
                renderFile(sb, fname, fe);
            }
        }
        for (ArchiveEntryItem fe : rootFiles) {
            renderFile(sb, fe.path(), fe);
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
    private void renderFile(StringBuilder sb, String name, ArchiveEntryItem fe) {
        String eExt = extFromName(name);
        sb.append("<div class=\"file\" data-path=\"").append(StringUtils.escapeAttr(fe.path())).append("\"><span class=\"icon\">");
        sb.append(eExt != null ? StringUtils.escapeHtml(eExt) : "📄");
        sb.append("</span>").append(StringUtils.escapeHtml(name));
        sb.append("<span class=\"meta-right\">");
        sb.append(FileUtils.readableFileSize(fe.size()));
        if (fe.modified() > 0L) {
            String formatted = DateUtils.format(new Date(fe.modified()), "yyyy-MM-dd HH:mm");
            sb.append(" &middot; ").append(formatted != null ? formatted : "-");
        }
        sb.append("</span></div>");
    }

    /**
     * 从文件名提取小写扩展名（不含点号）。
     *
     * @param name 文件名
     * @return 扩展名；无扩展名时返回 空
     */
    private static String extFromName(String name) {
        String ext = FileUtils.getExtension(name);
        return ext == null || ext.isEmpty() ? null : ext.toLowerCase(Locale.ENGLISH);
    }

    /**
     * 压缩包条目（对外只读的扁平记录）。
     *
     * <p>{@code path} 为包内完整路径（不含压缩包自身文件名），前端据此构树；
     * {@code modified} 用 epoch 毫秒而非 {@link Date}，避免把可变对象暴露给调用方。</p>
     *
     * @param path           包内完整路径
     * @param name           末段名称
     * @param directory      是否为目录
     * @param size           解压后大小（目录为 0）
     * @param compressedSize 压缩后大小（未记录时为 -1，表示未知）
     * @param modified       最后修改时间（epoch 毫秒；无时间戳时为 0）
     */
    public record ArchiveEntryItem(String path, String name, boolean directory,
                                   long size, long compressedSize, long modified) {
    }

    /**
     * 压缩包扫描结果。
     *
     * @param entries   扁平条目清单（已去重并补全隐式目录）
     * @param fileCount 文件数
     * @param dirCount  目录数
     * @param totalSize 解压后总大小
     */
    public record ArchiveScan(List<ArchiveEntryItem> entries,
                              int fileCount, int dirCount, long totalSize) {
        /**
         * 紧凑构造器：条目清单做防御性拷贝，避免调用方持有内部引用。
         *
         * @param entries   扁平条目清单
         * @param fileCount 文件数
         * @param dirCount  目录数
         * @param totalSize 解压后总大小
         */
        public ArchiveScan {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /**
     * 归档实现产出的原始条目（补全隐式目录之前的形态）。
     *
     * @param path           包内完整路径
     * @param directory      是否为目录
     * @param size           解压后大小
     * @param compressedSize 压缩后大小（未知为 -1）
     * @param modified       最后修改时间（epoch 毫秒；无时间戳为 0）
     * @return item的结果
     */
    private record RawEntry(String path, boolean directory, long size,
                            long compressedSize, long modified) {
        /**
         * 转换为对外条目。
         *
         * @return 条目
         */
        ArchiveEntryItem toItem() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return new ArchiveEntryItem(path, name, directory, size, compressedSize, modified);
        }
    }
}
