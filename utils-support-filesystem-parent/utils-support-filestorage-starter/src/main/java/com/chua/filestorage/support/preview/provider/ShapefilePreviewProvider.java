package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
* Shapefile (SHP) 地理数据预览提供器。
* <p>SPI 类型：{@code preview-shapefile}。解析 SHP 文件头部，提取几何类型和边界信息。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("preview-shapefile")
public class ShapefilePreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("shp", "shx", "dbf", "prj", "qix", "sbn", "sbx"); // 支持exts

    private static final String[] SHAPE_TYPES = {
        "Null Shape", "Point", "PolyLine", "Polygon", "MultiPoint",
        "PointZ", "PolyLineZ", "PolygonZ", "MultiPointZ", "PointM",
        "PolyLineM", "PolygonM", "MultiPointM", "MultiPatch"
    };

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String lowerExt = ext.toLowerCase(Locale.ENGLISH);
        if (!lowerExt.equals("shp")) {
            // 只解析 .shp，其他文件直接返回基本信息
            String html = buildSimpleHtml(ext, content.length);
            return PreviewResult.builder().htmlContent(html).build();
        }

        ShpInfo info = parseShp(content);
        String html = buildHtml(info, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    /**
    * 解析shp。
    * @param data 数据
    * @return 解析shp的结果
    */
    private ShpInfo parseShp(byte[] data) {
        ShpInfo info = new ShpInfo();

        if (data.length < 100) {
            info.version = "文件太小";
            return info;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // 文件代码: 偏移 0-3，应为 9994
        int fileCode = buf.getInt();
        if (fileCode != 9994) {
            info.version = "无效的 SHP 文件";
            return info;
        }

        // 文件长度: 偏移 24-27 (16位字)
        buf.position(24);
        int fileLengthWords = buf.getInt();
        info.fileLength = fileLengthWords * 2;

        // 版本: 偏移 28-31，应为 1000
        buf.position(28);
        int version = buf.getInt();
        info.version = "SHP v" + version;

        // 几何类型: 偏移 32-35
        buf.position(32);
        int shapeType = buf.getInt();
        if (shapeType >= 0 && shapeType < SHAPE_TYPES.length) {
            info.shapeType = SHAPE_TYPES[shapeType];
        } else {
            info.shapeType = "Unknown (" + shapeType + ")";
        }

        // 边界框: 偏移 36-67
    /**
    * shp信息类。
    *
    * @author CH
    * @since 4.0.0
    * @param bytes bytes
    * @return human大小的结果
    */
        buf.position(36);
        info.xmin = buf.getDouble();
        info.ymin = buf.getDouble();
        info.xmax = buf.getDouble();
        info.ymax = buf.getDouble();

        return info;
    /**
    * 构建html。
    * @param info 信息
    * @param fileSize 文件大小
    * @return 构建html的结果
    */
    }

    /**
     * 构建Html。
     *
     * @param info 方法入参 info
     * @param fileSize 文件大小，不允许为 null
     * @return 结果字符串
     */
    private String buildHtml(ShpInfo info, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".info{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:700px;margin:0 auto}");
        sb.append(".field{display:flex;padding:10px 0;border-bottom:1px solid #f3f4f6}");
        sb.append(".field:last-child{border-bottom:none}");
        sb.append(".label{color:#6b7280;width:100px;font-size:13px}");
        sb.append(".value{font-weight:500;font-size:14px}");
        sb.append(".icon{font-size:48px;text-align:center;color:#6b7280;margin-bottom:20px}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>Shapefile 地理数据预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"info\">");
        sb.append("<div class=\"icon\">GIS</div>");
        sb.append("<div class=\"field\"><span class=\"label\">格式</span><span class=\"value\">ESRI Shapefile</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">版本</span><span class=\"value\">").append(escapeHtml(info.version)).append("</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">几何类型</span><span class=\"value\">").append(escapeHtml(info.shapeType)).append("</span></div>");

        if (info.xmin != 0 || info.ymin != 0) {
            sb.append("<div class=\"field\"><span class=\"label\">边界范围</span><span class=\"value\">");
            sb.append(String.format(Locale.ENGLISH, "%.6f, %.6f ~ %.6f, %.6f", info.xmin, info.ymin, info.xmax, info.ymax));
            sb.append("</span></div>");
        }

        sb.append("<div class=\"field\"><span class=\"label\">文件大小</span><span class=\"value\">").append(humanSize(fileSize)).append("</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">说明</span><span class=\"value\">SHP 是 ESRI 的地理信息系统 (GIS) 矢量数据格式。请使用 QGIS 或 ArcGIS 打开。</span></div>");
        sb.append("</div></body></html>");

        return sb.toString();
    /**
    * 构建简单html。
    * @param ext ext
    * @param fileSize 文件大小
    * @return 构建简单html的结果
    */
    }

    /**
     * 构建SimpleHtml。
     *
     * @param ext 方法入参 ext
     * @param fileSize 文件大小，不允许为 null
     * @return 结果字符串
     */
    private String buildSimpleHtml(String ext, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".info{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:700px;margin:0 auto;text-align:center}");
        sb.append(".icon{font-size:48px;margin-bottom:16px}");
        sb.append(".text{color:#374151;font-size:14px}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>Shapefile 关联文件</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"info\">");
        sb.append("<div class=\"icon\">GIS</div>");
        sb.append("<div class=\"text\">这是 Shapefile 的关联文件 (. scarcity)。请提供 .shp 主文件以查看地理数据。</div>");
        sb.append("</div></body></html>");

        return sb.toString();
    /**
    * escapehtml。
    * @param text 文本
    * @return escapeHtml的结果
    * @author CH
    * @since 4.0.0
    * @param bytes bytes
    */
    }

    /**
     * escapeHtml。
     *
     * @param text 文本，不允许为 null
     * @return 结果字符串
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * human大小。
     *
     * @param bytes 字节数组，不允许为 null
     * @return 结果字符串
     */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static class ShpInfo {
        String version = "未知"; // 版本
        String shapeType = "Unknown"; // shape类型
        long fileLength = 0; // 文件长度
        double xmin = 0, ymin = 0, xmax = 0, ymax = 0; // xmin
    }
}
