package com.chua.cad.support.provider;

import com.chua.cad.support.dwg.DwgHeaderInfo;
import com.chua.cad.support.dwg.DwgHeaderParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DWG 预览提供器：<b>仅头部信息，不渲染图形</b>。
 *
 * <p><b>为什么 DWG 没有图形</b>：DWG 是 Autodesk 私有二进制格式，图元存放在
 * object map 与 handle 表中，结构随版本变化且未完整公开，Java 生态没有能可靠
 * 解析图元几何的库。真渲染需要外部转换器（LibreDWG / ODA File Converter /
 * Aspose.CAD），三者分别受 GPL 与商业授权约束，不适合在基础模块里默认引入。</p>
 *
 * <p>本提供器能做到的：读出 AutoCAD 版本、维护版本、发行号、文件体积，
 * 明确告诉用户「这是什么版本的图纸」，并提示可另存为 DXF 获得完整预览。
 * 这比展示一张「请用 AutoCAD 打开」的空白卡有实质信息量。</p>
 *
 * <p>本 SPI 只接管 DWG；DGN / STEP / IGES 等仍由原有的
 * {@code CadPreviewProvider} 处理，避免行为变更面扩大。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-dwg")
public class DwgPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 本提供器接管的扩展名（小写）。只取 DWG，其余交回原 CadPreviewProvider
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of("dwg");

    /**
     * 建议另存的格式（DXF 为纯文本，本模块可完整渲染）
     */
    private static final String SUGGESTED_FORMAT = "DXF";

    /**
     * 解析器实例（无状态，可复用）
     */
    private final DwgHeaderParser parser = DwgHeaderParser.create();

    /**
     * 判断是否支持 DWG 预览
     *
     * @param ext  文件扩展名
     * @param mime MIME 类型（忽略）
     * @return true 表示支持
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 解析 DWG 头并渲染信息卡
     *
     * @param content DWG 字节
     * @param ext     文件扩展名
     * @param mime    MIME 类型（忽略）
     * @return 预览结果（完整 HTML 文档）
     * @throws IOException 解析失败
     */
    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        DwgHeaderInfo info = parser.parse(content);
        List<String> rows = buildRows(info);
        return PreviewResult.builder()
                .htmlContent(buildHtml(info, rows))
                .build();
    }

    /**
     * 构建信息卡的表格行（键值对成对）
     *
     * @param info 头部信息
     * @return 行列表，每行两个元素（标签、值）
     */
    private List<String> buildRows(DwgHeaderInfo info) {
        return List.of(
                "格式", "AutoCAD DWG（." + (info.acadRelease().isEmpty() ? "?" : info.acadRelease()) + "）",
                "版本", info.versionLabel(),
                "版本代码", info.versionCode().isEmpty() ? "未识别" : info.versionCode(),
                "维护版本", info.maintenanceVersion() < 0 ? "-" : String.valueOf(info.maintenanceVersion()),
                "文件大小", humanSize(info.fileSize()));
    }

    /**
     * 构建完整 HTML 文档
     *
     * @param info 头部信息
     * @param rows 表格行
     * @return HTML
     */
    private String buildHtml(DwgHeaderInfo info, List<String> rows) {
        StringBuilder table = new StringBuilder();
        for (int i = 0; i + 1 < rows.size(); i += 2) {
            table.append("<tr><td>").append(escape(rows.get(i)))
                    .append("</td><td>").append(escape(rows.get(i + 1))).append("</td></tr>");
        }
        // currentColor 承接前端主题：深浅色下都清晰，无需服务端知道主题值
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "body{margin:0;padding:20px;background:transparent;color:inherit;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                + ".card{max-width:520px;margin:0 auto}"
                + "h1{margin:0 0 4px;font-size:17px;font-weight:600}"
                + ".sub{margin:0 0 18px;font-size:13px;opacity:.65}"
                + "table{width:100%;border-collapse:collapse;font-size:13px}"
                + "td{padding:9px 10px;border-bottom:1px solid currentColor}"
                + "td:first-child{width:96px;opacity:.6;white-space:nowrap}"
                + "td:nth-child(2){border-bottom:1px solid currentColor}"
                + "tr:last-child td{border-bottom:none}"
                + ".note{margin-top:18px;padding:12px 14px;border:1px solid currentColor;"
                + "border-radius:8px;font-size:12px;line-height:1.6;opacity:.8}"
                + "</style></head><body><div class=\"card\">"
                + "<h1>" + escape(info.versionLabel()) + "</h1>"
                + "<p class=\"sub\">DWG 为 Autodesk 私有格式，浏览器无法直接解析其图元几何</p>"
                + "<table>" + table + "</table>"
                + "<div class=\"note\">"
                + "如需在浏览器中查看图形，可在 CAD 软件中将本图<b>另存为 "
                + SUGGESTED_FORMAT + "</b> 后重新上传；DXF 为开放文本格式，"
                + "本系统可完整渲染其线、圆弧、多段线与标注。"
                + "</div></div></body></html>";
    }

    /**
     * HTML 转义
     *
     * @param s 原始文本
     * @return 转义后文本
     */
    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 人类可读的文件大小
     *
     * @param bytes 字节数
     * @return 格式化文本
     */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
