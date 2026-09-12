package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
   * 老版 Microsoft 办公室 (.DOC / .XLS / .PPT) 预览提供器。
 * <p>SPI 类型：{@code preview-poi-old}。使用 Apache POI 解析旧版二进制格式：
   * Word 抽取段落文本，Excel 渲染工作表表格，powerpoint 抽取每页文本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-poi-old")
public class OldOfficePreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("doc", "xls", "ppt"); // 支持exts
    private static final int MAX_ROWS = 200; // 最大rows
    private static final int MAX_SHEETS = 20; // 最大sheets
    private static final long MAX_FILE_SIZE = 64L * 1024 * 1024; // 最大文件大小

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
            return PreviewResult.builder().htmlContent(unavailableHtml("文件过大（超过 64 MB），暂不支持预览")).build();
        }
        String type = ext.toLowerCase(Locale.ENGLISH);
        switch (type) {
            case "doc":
                return previewDoc(content);
            case "xls":
                return previewXls(content);
            case "ppt":
                return previewPpt(content);
            default:
                return PreviewResult.builder().htmlContent(unavailableHtml("不支持的 Office 格式: " + ext)).build();
        }
    }

    /**
     * 预览 Word 文档，抽取正文段落。
     *
     * @param content 文档字节
     * @return 预览结果
     * @throws IOException 解析失败时抛出
     */
    private PreviewResult previewDoc(byte[] content) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append(header("Word 文档预览"));
        try (InputStream in = new ByteArrayInputStream(content);
             HWPFDocument doc = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(doc)) {
            String[] paragraphs = extractor.getParagraphText();
            body.append("<div class=\"doc\">");
            List<String> lines = new ArrayList<>();
            for (String paragraph : paragraphs) {
                String text = paragraph.trim();
                if (!text.isEmpty()) {
                    lines.add(escape(text));
                }
            }
            if (lines.isEmpty()) {
                lines.add("<em>（未提取到文本）</em>");
            }
            for (String line : lines) {
                body.append("<p>").append(line).append("</p>");
            }
            body.append("</div>");
        }
        return PreviewResult.builder().htmlContent(page(body.toString())).build();
    }

    /**
     * 预览 Excel 工作表，渲染各表前 200 行。
     *
     * @param content 工作簿字节
     * @return 预览结果
     * @throws IOException 解析失败时抛出
     */
    private PreviewResult previewXls(byte[] content) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append(header("Excel 工作簿预览"));
        try (InputStream in = new ByteArrayInputStream(content);
             HSSFWorkbook wb = new HSSFWorkbook(in)) {
            int sheetCount = Math.min(wb.getNumberOfSheets(), MAX_SHEETS);
            body.append("<div class=\"meta\">工作表: ").append(sheetCount).append("</div>");
            for (int i = 0; i < sheetCount; i++) {
                renderSheet(body, wb.getSheetAt(i));
            }
        } catch (org.apache.poi.OldFileFormatException e) {
            return PreviewResult.builder()
                    .htmlContent(page(unavailableHtml("Excel 97-2003 (.xls) 解析失败: " + escape(e.getMessage())))).build();
        }
        return PreviewResult.builder().htmlContent(page(body.toString())).build();
    }

    /**
     * 渲染单张工作表。
     *
     * @param body  输出缓冲区
     * @param sheet 工作表
     */
    private void renderSheet(StringBuilder body, HSSFSheet sheet) {
        String name = sheet.getSheetName();
        body.append("<div class=\"sheet\"><div class=\"sheet-head\">").append(escape(name)).append("</div>");
        int lastRow = Math.min(sheet.getLastRowNum() + 1, MAX_ROWS);
        body.append("<table><tbody>");
        for (int r = 0; r < lastRow; r++) {
            HSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            body.append("<tr>");
            for (short c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                if (c < 0) {
                    continue;
                }
                HSSFCell cell = row.getCell(c);
                String value = cell == null ? "" : formatCell(cell);
                body.append("<td>").append(value.isEmpty() ? "&nbsp;" : escape(value)).append("</td>");
            }
            body.append("</tr>");
        }
        body.append("</tbody></table>");
        if (sheet.getLastRowNum() + 1 > MAX_ROWS) {
            body.append("<div class=\"more\">仅显示前 ").append(MAX_ROWS).append(" 行</div>");
        }
        body.append("</div>");
    }

    /**
     * 格式化单元格值。
     *
     * @param cell 单元格
     * @return 单元格文本
     */
    private String formatCell(HSSFCell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    /**
      * 预览 powerpoint 演示文稿，抽取每页文本。
     *
     * @param content 演示文稿字节
     * @return 预览结果
     * @throws IOException 解析失败时抛出
     */
    private PreviewResult previewPpt(byte[] content) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append(header("PowerPoint 演示文稿预览"));
        try (InputStream in = new ByteArrayInputStream(content);
             HSLFSlideShow slideShow = new HSLFSlideShow(in)) {
            List<HSLFSlide> slides = slideShow.getSlides();
            body.append("<div class=\"meta\">幻灯片: ").append(slides.size()).append("</div>");
            int index = 1;
            for (HSLFSlide slide : slides) {
                body.append("<div class=\"slide\"><div class=\"slide-head\">第 ").append(index++).append(" 页</div><div class=\"slide-body\">");
                List<String> texts = new ArrayList<>();
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.trim().isEmpty()) {
                            texts.add(t.trim());
                        }
                    }
                }
                if (texts.isEmpty()) {
                    body.append("<em>（本页无文本）</em>");
                } else {
                    for (String t : texts) {
                        body.append("<p>").append(escape(t)).append("</p>");
                    }
                }
                body.append("</div></div>");
            }
        } catch (org.apache.poi.EncryptedDocumentException | java.io.EOFException e) {
            return PreviewResult.builder()
                    .htmlContent(page(unavailableHtml("PowerPoint (.ppt) 解析失败: " + escape(e.getMessage())))).build();
        }
        return PreviewResult.builder().htmlContent(page(body.toString())).build();
    }

    /**
     * 构建页面头部。
     *
     * @param title 标题
     * @return 头部 HTML
     */
    private String header(String title) {
        return "<div class=\"header\"><h1>" + escape(title) + "</h1></div>";
    }

    /**
     * 包装完整页面。
     *
     * @param body 页面主体
     * @return 完整 HTML
     */
    private String page(String body) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}"
                + ".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}"
                + "h1{margin:0;font-size:20px;font-weight:600}"
                + ".meta{color:#6b7280;font-size:13px;margin:12px 0}"
                + ".doc{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:24px;max-width:860px;line-height:1.7}"
                + ".doc p{margin:0 0 10px}"
                + ".sheet,.slide{background:#fff;border:1px solid #e5e7eb;border-radius:10px;margin-bottom:16px;overflow:hidden}"
                + ".sheet-head,.slide-head{padding:10px 16px;font-weight:600;border-bottom:1px solid #f0f0f0;background:#f9fafb}"
                + "table{border-collapse:collapse;width:100%;font-size:13px}"
                + "th,td{border:1px solid #eee;padding:6px 12px;text-align:left;white-space:nowrap}"
                + "tr:nth-child(even) td{background:#fafafa}"
                + ".slide-body{padding:16px}.slide-body p{margin:0 0 8px}"
                + ".more{color:#9ca3af;font-size:12px;padding:8px 16px}"
                + ".unavail{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:40px;text-align:center;color:#6b7280}"
                + "</style></head><body>" + body + "</body></html>";
    }

    /**
     * 构建不可预览提示。
     *
     * @param message 提示文本
     * @return 提示 HTML（不含外边页面）
     */
    private String unavailableHtml(String message) {
        return header("文件预览") + "<div class=\"unavail\">" + escape(message) + "</div>";
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