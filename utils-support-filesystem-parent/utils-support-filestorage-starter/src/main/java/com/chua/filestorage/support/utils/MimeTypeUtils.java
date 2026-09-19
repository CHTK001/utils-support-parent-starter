package com.chua.filestorage.support.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MIME 类型与文件能力工具类。
 *
 * <p>提供以下能力：</p>
 * <ul>
 *   <li>根据扩展名获取 MIME 类型</li>
 *   <li>判断是否支持浏览器原生预览</li>
 *   <li>判断是否可转换为 PDF</li>
 *   <li>判断是否支持前端插件预览</li>
 *   <li>判断是否支持范围请求（Range）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MimeTypeUtils {

    /** 浏览器原生可预览的 MIME 类型集合（部分） */
    private static final Set<String> BROWSER_PREVIEW_MIMES;
    /** 可转换为 PDF 的扩展名集合 */
    private static final Set<String> CONVERTABLE_TO_PDF_EXTENSIONS;
    /** 前端插件可预览的扩展名集合（如 办公室、CAD 等） */
    private static final Set<String> PLUGIN_PREVIEW_EXTENSIONS;
    /** 支持 范围 断点续传的 MIME 类型 */
    private static final Set<String> RANGE_SUPPORTED_MIMES;
    /** 扩展名到 MIME 的映射 */
    private static final Map<String, String> EXTENSION_TO_MIME;

    static {
        // 扩展名 -> MIME
        Map<String, String> map = new HashMap<>();
        // 图片
        map.put("png", "image/png");
        map.put("jpg", "image/jpeg");
        map.put("jpeg", "image/jpeg");
        map.put("gif", "image/gif");
        map.put("webp", "image/webp");
        map.put("bmp", "image/bmp");
        map.put("svg", "image/svg+xml");
        map.put("ico", "image/x-icon");
        // 音频
        map.put("mp3", "audio/mpeg");
        map.put("wav", "audio/wav");
        map.put("ogg", "audio/ogg");
        map.put("aac", "audio/aac");
        // 视频
        map.put("mp4", "video/mp4");
        map.put("webm", "video/webm");
        map.put("ogg", "video/ogg");
        map.put("mov", "video/quicktime");
        // PDF
        map.put("pdf", "application/pdf");
        // 文本
        map.put("txt", "text/plain");
        map.put("md", "text/markdown");
        map.put("html", "text/html");
        map.put("htm", "text/html");
        map.put("css", "text/css");
        map.put("js", "application/javascript");
        map.put("json", "application/json");
        map.put("xml", "application/xml");
        map.put("csv", "text/csv");
        // Office (可转换)
        map.put("doc", "application/msword");
        map.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        map.put("xls", "application/vnd.ms-excel");
        map.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        map.put("ppt", "application/vnd.ms-powerpoint");
        map.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        map.put("odt", "application/vnd.oasis.opendocument.text");
        map.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        map.put("odp", "application/vnd.oasis.opendocument.presentation");
        // 3D 模型
        map.put("glb", "model/gltf-binary");
        map.put("gltf", "model/gltf+json");
        map.put("obj", "model/obj");
        map.put("stl", "model/stl");
        map.put("dxf", "image/vnd.dxf");
        // Visio
        map.put("vsdx", "application/vnd.ms-visio.drawing");
        map.put("vsd", "application/vnd.visio");
        map.put("vdx", "application/vnd.ms-visio.vdx");
        // 其他常见类型
        map.put("zip", "application/zip");
        map.put("rar", "application/x-rar-compressed");
        map.put("gz", "application/gzip");
        map.put("7z", "application/x-7z-compressed");
        EXTENSION_TO_MIME = Collections.unmodifiableMap(map);

        // 浏览器原生可预览的 MIME（图片、音频、视频、PDF、文本类）
        Set<String> browser = new HashSet<>();
        browser.add("image/png");
        browser.add("image/jpeg");
        browser.add("image/gif");
        browser.add("image/webp");
        browser.add("image/bmp");
        browser.add("image/svg+xml");
        browser.add("image/x-icon");
        browser.add("audio/mpeg");
        browser.add("audio/wav");
        browser.add("audio/ogg");
        browser.add("audio/aac");
        browser.add("video/mp4");
        browser.add("video/webm");
        browser.add("video/ogg");
        browser.add("video/quicktime");
        browser.add("application/pdf");
        browser.add("text/plain");
        browser.add("text/markdown");
        browser.add("text/html");
        browser.add("text/css");
        browser.add("application/javascript");
        browser.add("application/json");
        browser.add("application/xml");
        browser.add("text/csv");
        BROWSER_PREVIEW_MIMES = Collections.unmodifiableSet(browser);

        // 可转为 PDF 的扩展名
        Set<String> convertable = new HashSet<>();
        convertable.add("doc");
        convertable.add("docx");
        convertable.add("xls");
        convertable.add("xlsx");
        convertable.add("ppt");
        convertable.add("pptx");
        convertable.add("odt");
        convertable.add("ods");
        convertable.add("odp");
        // 纯文本、HTML 也可以转为 PDF
        convertable.add("txt");
        convertable.add("md");
        convertable.add("html");
        convertable.add("htm");
        CONVERTABLE_TO_PDF_EXTENSIONS = Collections.unmodifiableSet(convertable);

 // 前端插件可预览（办公室 等）
        Set<String> plugin = new HashSet<>();
        plugin.add("doc");
        plugin.add("docx");
        plugin.add("xls");
        plugin.add("xlsx");
        plugin.add("ppt");
        plugin.add("pptx");
        plugin.add("odt");
        plugin.add("ods");
        plugin.add("odp");
        plugin.add("glb");
        plugin.add("gltf");
        plugin.add("obj");
        plugin.add("stl");
        plugin.add("dxf");
        plugin.add("vsdx");
        plugin.add("vsd");
        plugin.add("vdx");
        plugin.add("zip");
        plugin.add("rar");
        plugin.add("7z");
        plugin.add("tar");
        plugin.add("gz");
        plugin.add("tgz");
        plugin.add("bz2");
        plugin.add("xz");
        PLUGIN_PREVIEW_EXTENSIONS = Collections.unmodifiableSet(plugin);

 // 支持 范围 的 MIME（通常是流媒体或大文件）
        Set<String> range = new HashSet<>();
        range.add("video/mp4");
        range.add("video/webm");
        range.add("video/ogg");
        range.add("audio/mpeg");
        range.add("audio/wav");
        range.add("application/pdf");
        range.add("image/png");
        range.add("image/jpeg");
        range.add("image/gif");
        range.add("image/webp");
        range.add("image/bmp");
        range.add("image/svg+xml");
        range.add("application/zip");
        range.add("application/octet-stream");
        RANGE_SUPPORTED_MIMES = Collections.unmodifiableSet(range);
    }

    /** 创建 mime类型工具 实例 */
    private MimeTypeUtils() {
        throw new AssertionError("No com.chua.filestorage.support.utils.MimeTypeUtils instances for you!");
    }

    /**
    * 根据扩展名获取 MIME 类型（小写）。
    *
    * @param extension 扩展名（不带点，如 "pdf"）
    * @return MIME 类型，找不到返回 "application/octet-流"
    */
    public static String getMimeType(String extension) {
        if (extension == null) {
            return "application/octet-stream";
        }
        String mime = EXTENSION_TO_MIME.get(extension.toLowerCase(Locale.ENGLISH));
        return mime != null ? mime : "application/octet-stream";
    }

    /**
     * 从文件名提取扩展名并获取 MIME 类型。
     *
     * @param filename 文件名（如 "report.pdf"）
     * @return MIME 类型
     */
    public static String getMimeTypeFromFilename(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "application/octet-stream";
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1);
        return getMimeType(ext);
    }

    /**
     * 判断指定 MIME 是否支持浏览器原生预览。
     *
     * @param mime MIME 类型
     * @return true 表示支持
     */
    public static boolean isBrowserPreviewSupported(String mime) {
        return mime != null && BROWSER_PREVIEW_MIMES.contains(mime);
    }

    /**
     * 判断扩展名是否支持前端插件预览。
     *
     * @param extension 扩展名（不带点）
     * @return true 表示支持
     */
    public static boolean isPluginPreviewSupported(String extension) {
        return extension != null && PLUGIN_PREVIEW_EXTENSIONS.contains(extension.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 判断扩展名是否可转换为 PDF。
     *
     * @param extension 扩展名（不带点）
     * @return true 表示可转换
     */
    public static boolean isConvertableToPdf(String extension) {
        return extension != null && CONVERTABLE_TO_PDF_EXTENSIONS.contains(extension.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 判断 MIME 是否支持 范围 范围请求。
     *
     * @param mime MIME 类型
     * @return true 表示支持
     */
    public static boolean isRangeSupported(String mime) {
        return mime != null && RANGE_SUPPORTED_MIMES.contains(mime);
    }
}
