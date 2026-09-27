package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.PicturesManager;
import org.apache.poi.hwpf.converter.WordToHtmlConverter;
import org.apache.poi.hwpf.usermodel.PictureType;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 老版 Microsoft 办公室 (.DOC / .XLS / .PPT) 预览提供器。
 *
 * <p>SPI 类型：{@code preview-poi-old}。使用 Apache POI 解析旧版二进制格式：</p>
 * <ul>
 *   <li>Word —— 走 {@link WordToHtmlConverter} 转成 HTML，保留段落样式、标题层级、
 *       表格、超链接与内嵌图片（图片以 data URI 内联，无需额外请求）；
 *       转换器不可用时降级为纯文本抽取，保证任何情况下都能出内容。</li>
 *   <li>Excel —— 渲染工作表表格</li>
 *   <li>powerpoint —— 抽取每页文本</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-poi-old")
@Slf4j
public class OldOfficePreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("doc", "xls", "ppt"); // 支持exts
    private static final int MAX_ROWS = 200; // 最大rows
    private static final int MAX_SHEETS = 20; // 最大sheets
    private static final long MAX_FILE_SIZE = 64L * 1024 * 1024; // 最大文件大小
    /**
     * 单张图片内联上限（原始字节）；超过则不内联，避免 HTML 体积失控
     * （base64 相比原图约膨胀 33%）。
     */
    private static final int MAX_INLINE_IMAGE_BYTES = 512 * 1024;

    /**
     * 单个内嵌对象（附件）内联下载的上限；超过则只列出名称与大小，
     * 避免把整个附件 base64 进 HTML 造成页面膨胀。
     */
    private static final int MAX_INLINE_ATTACHMENT_BYTES = 2 * 1024 * 1024;

    /**
     * 是否内联 .doc 内嵌图片。
     *
     * <p><b>已开启</b>。此前一度关闭，是因为把
     * {@code PicturesManager.savePicture} 的返回值当成了"可拼接属性的 HTML 片段"，
     * 在 data URI 后面追加 {@code " width="..." height="..."}，
     * 导致该字符串整体成为 {@code src} 属性值（拼接的引号被序列化为
     * {@code &quot;}），data URI 非法、浏览器渲染成 16×16 破图。
     * 经反汇编确认 POI 的约定是「返回值即 src 取值」，改为只返回纯 data URI 后正常。</p>
     *
     * <p>配套约束见 {@link #detectWebImageMime}（仅内联浏览器可渲染格式）、
     * {@link #isCompleteImage}（丢弃不完整字节）与 {@link #MAX_INLINE_IMAGE_BYTES}
     * （单图体积上限，防止 HTML 膨胀）。</p>
     */
    private static final boolean INLINE_DOC_IMAGES = true;

    /**
     * OLE2 复合文档中存放内嵌对象（附件）的存储名。
     *
     * <p>与 {@code HWPFDocumentCore.STREAM_OBJECT_POOL} 取值一致，
     * 但该常量是 protected，跨类不可见，故此处重新声明。</p>
     */
    private static final String OBJECT_POOL_STREAM = "ObjectPool";


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
     * 预览 Word 文档，转换为保留样式的 HTML。
     *
     * <p>优先使用 {@link WordToHtmlConverter}（可保留标题层级、加粗斜体、对齐、
     * 列表、表格、超链接与内嵌图片）；若转换过程中出现异常，则降级为
     * {@code WordExtractor} 纯文本抽取，确保损坏或特殊文档仍能出内容。</p>
     *
     * @param content 文档字节
     * @return 预览结果
     */
    private PreviewResult previewDoc(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content);
             HWPFDocument doc = new HWPFDocument(in)) {
            String html = null;
            try {
                html = convertDocToHtml(doc);
            } catch (Exception e) {
                // 富文本转换失败不致命，降级为纯文本
                log.debug("Word 富文本转换失败，降级为纯文本: {}", e.getMessage());
            }
            if (html != null && !html.isBlank()) {
                StringBuilder out = new StringBuilder(header("Word 文档预览"))
                        .append("<div class=\"doc\">").append(html).append("</div>");
                appendEmbeddedObjectsSection(out, content);
                return PreviewResult.builder().htmlContent(page(out.toString())).build();
            }
            return PreviewResult.builder()
                    .htmlContent(page(header("Word 文档预览") + extractDocText(doc)))
                    .build();
        } catch (EncryptedDocumentException e) {
            return unavailable("Word 文档已加密，无法预览");
        } catch (IOException | RuntimeException e) {
            // 结构异常 / 非标准 .doc：降级为纯文本，尽量把内容拿出来
            String text = extractDocTextQuietly(content);
            if (text != null && !text.isBlank()) {
                return PreviewResult.builder()
                        .htmlContent(page(header("Word 文档预览（纯文本）")
                                + "<div class=\"warn\">文档结构异常，已降级为纯文本显示："
                                + escape(String.valueOf(e.getMessage())) + "</div>" + text))
                        .build();
            }
            return unavailable("Word 文档解析失败: " + String.valueOf(e.getMessage()));
        }
    }

    /**
     * 将 Word 文档转换为 HTML 正文片段。
     *
     * @param doc 已打开的 Word 文档
     * @return HTML 片段；转换失败时返回 空
     * @throws Exception 转换异常
     */
    private String convertDocToHtml(HWPFDocument doc) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document xmlDoc = dbf.newDocumentBuilder().newDocument();
        WordToHtmlConverter converter = new WordToHtmlConverter(xmlDoc);
        // 图片内联为 data URI，避免预览页再发起额外请求（预览沙箱可能禁止外链图片）
        converter.setPicturesManager(new DataUriPicturesManager());
        converter.processDocument(doc);
        // 只取 <body> 的「子节点」序列化，不能把 <body> 元素本身写出来：
        // 本方法产出的是片段，会被拼进外层页面，若带上 <body> 就会形成
        // <div class="doc"><body>…</body></div> 这样的嵌套 body（无效 HTML，
        // 且 POI 给 body 加的 class 会被浏览器丢弃）。
        org.w3c.dom.NodeList bodies = xmlDoc.getElementsByTagName("body");
        org.w3c.dom.Node source = bodies.getLength() > 0 ? bodies.item(0) : xmlDoc.getDocumentElement();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        org.w3c.dom.NodeList children = source.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            transformer.transform(new DOMSource(children.item(i)), new StreamResult(writer));
        }
        return writer.toString();
    }

    /**
     * 把图片以 data URI 形式写入 HTML，避免额外网络请求。
     *
     * <p>三点说明：</p>
     * <ul>
     *   <li>尺寸<b>不采信</b> POI 传入的 width/height：实测既不是 twip 也不是像素
     *       （同一张 240×160 的 PNG 被传成 2×2），单位随图片类型而异。
     *       改为直接解析图片文件头获取真实像素尺寸，结果确定且与浏览器一致。</li>
     *   <li>仅内联浏览器可渲染的格式（PNG / JPEG / GIF / BMP / WebP）。
     *       EMF、WMF、TIFF 等浏览器无法显示，内联只会得到破图，
     *       一律跳过，避免 EMF 这类大图把 HTML 撑到数 MB。</li>
     *   <li>体积上限 {@link #MAX_INLINE_IMAGE_BYTES}：data URI 相比原图约膨胀 33%。</li>
     * </ul>
     */
    private static final class DataUriPicturesManager implements PicturesManager {

        /**
         * 构造（图片字节由 POI 直接传入，无需 PicturesTable）
         */
        private DataUriPicturesManager() {
        }

        @Override
        public String savePicture(byte[] pictureData, PictureType pictureType,
                                 String suggestedName, float width, float height) {
            if (!INLINE_DOC_IMAGES) {
                return "";
            }
            if (pictureData == null || pictureData.length == 0
                    || pictureData.length > MAX_INLINE_IMAGE_BYTES) {
                return "";
            }
            String mime = detectWebImageMime(pictureData);
            if (mime == null) {
                // EMF / WMF / TIFF 等：浏览器无法渲染，跳过内联
                return "";
            }
            if (!isCompleteImage(pictureData, mime)) {
                log.debug("跳过不完整图片: mime={} bytes={}", mime, pictureData.length);
                return "";
            }
            // 返回值即 <img> 的 src 取值：只能返回纯 data URI，
            // 绝不可在其中拼接 width/height 等属性文本，否则 src 会变成非法 URI
            // （拼接的引号会被序列化成 &quot;，浏览器整张图都解不出来）。
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(pictureData);
        }
    }

    /**
     * 校验图片字节是否结构完整（以结束标记为准）。
     *
     * <p>POI 从传统 .doc 抽取的图片并不总是完整的，直接内联会得到破图。</p>
     *
     * @param data 图片字节
     * @param mime 图片 MIME
     * @return true 表示结构完整
     */
    private static boolean isCompleteImage(byte[] data, String mime) {
        switch (mime) {
            case "image/png":
                // PNG 必须以 IEND 块结尾：00 00 00 00 49 45 4E 44 AE 42 60 82
                return data.length >= 12
                        && data[data.length - 8] == 0x49 && data[data.length - 7] == 0x45
                        && data[data.length - 6] == 0x4E && data[data.length - 5] == 0x44;
            case "image/gif":
                return data[data.length - 1] == 0x3B;
            case "image/jpeg":
                return (data[data.length - 2] & 0xFF) == 0xFF && (data[data.length - 1] & 0xFF) == 0xD9;
            case "image/bmp":
            case "image/webp":
            default:
                // BMP/WebP 无可靠的结束标记，跳过校验
                return true;
        }
    }

    /**
     * 识别浏览器可渲染的图片 MIME；不可渲染时返回 {@code null}。
     *
     * @param data 图片字节
     * @return MIME；不可渲染返回 {@code null}
     */
    private static String detectWebImageMime(byte[] data) {
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "image/gif";
        }
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') {
            return "image/bmp";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * 以纯文本方式抽取 Word 段落。
     *
     * @param doc 已打开的 Word 文档
     * @return HTML 片段
     */
    private String extractDocText(HWPFDocument doc) {
        try (org.apache.poi.hwpf.extractor.WordExtractor extractor =
                     new org.apache.poi.hwpf.extractor.WordExtractor(doc)) {
            return paragraphsToHtml(extractor.getParagraphText());
        } catch (Exception e) {
            return "<div class=\"unavail\">无法提取文本: " + escape(String.valueOf(e.getMessage())) + "</div>";
        }
    }

    /**
     * 打开文档并以纯文本方式抽取，失败返回 空。
     *
     * @param content 文档字节
     * @return HTML 片段；失败返回 空
     */
    private String extractDocTextQuietly(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content);
             HWPFDocument doc = new HWPFDocument(in)) {
            return extractDocText(doc);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把段落数组转成 HTML。
     *
     * @param paragraphs 段落文本
     * @return HTML 片段
     */
    private String paragraphsToHtml(String[] paragraphs) {
        StringBuilder body = new StringBuilder("<div class=\"doc\">");
        List<String> lines = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph != null && !paragraph.trim().isEmpty()) {
                lines.add("<p>" + escape(paragraph.trim()) + "</p>");
            }
        }
        if (lines.isEmpty()) {
            lines.add("<em>（未提取到文本）</em>");
        }
        body.append(String.join("", lines));
        body.append("</div>");
        return body.toString();
    }

    /**
     * 构建不可预览结果。
     *
     * @param message 提示
     * @return 预览结果
     */
    private PreviewResult unavailable(String message) {
        return PreviewResult.builder().htmlContent(page(unavailableHtml(message))).build();
    }

    /**
     * 将图片字节转为可内联的 data URI。
     *
     * <p>统一承担三重把关，供 doc / xls / ppt 三条链路复用：</p>
     * <ul>
     *   <li>格式白名单：仅 PNG / JPEG / GIF / BMP / WebP（EMF、WMF、TIFF 等
     *       浏览器无法渲染，直接放弃）</li>
     *   <li>完整性校验：丢弃结构不完整的字节，避免内联出破图</li>
     *   <li>体积上限：超过 {@link #MAX_INLINE_IMAGE_BYTES} 的图片不内联</li>
     * </ul>
     *
     * @param data 图片字节
     * @return data URI；不可内联时返回 {@code null}
     */
    private static String inlineImage(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_INLINE_IMAGE_BYTES) {
            return null;
        }
        String mime = detectWebImageMime(data);
        if (mime == null || !isCompleteImage(data, mime)) {
            return null;
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    /**
     * 向页面追加「文档内嵌图片」区块。
     *
     * <p>用于 Excel / PowerPoint 预览：表格文本与每页文字之外，
     * 把工作簿 / 演示文稿里内嵌的图片一并展示，避免用户以为内容缺失。</p>
     *
     * @param body   输出缓冲区
     * @param images 图片字节列表
     * @param source 来源描述（如 "工作簿" / "演示文稿"）
     */
    private void appendImagesSection(StringBuilder body, List<byte[]> images, String source) {
        List<String> inlined = new ArrayList<>();
        int skipped = 0;
        for (byte[] data : images) {
            String uri = inlineImage(data);
            if (uri == null) {
                skipped++;
            } else {
                inlined.add(uri);
            }
        }
        if (inlined.isEmpty() && skipped == 0) {
            return;
        }
        body.append("<div class=\"sheet\"><div class=\"sheet-head\">")
                .append(escape(source))
                .append("内嵌图片 (").append(inlined.size()).append(" 张");
        if (skipped > 0) {
            body.append("，").append(skipped).append(" 张为不支持的格式或过大已跳过");
        }
        body.append(")</div><div class=\"images\">");
        for (String uri : inlined) {
            body.append("<img src=\"").append(uri).append("\" alt=\"内嵌图片\"/>");
        }
        body.append("</div></div>");
    }

    /**
     * 向页面追加「内嵌对象（附件）」区块。
     *
     * <p>列出 OLE2 复合文档 ObjectPool 下的内嵌对象。传统 .doc / .xls / .ppt
     * 中嵌入的其它文件（如文档里插入的 Excel 表、PDF 附件）保存在该存储下，
     * 用户在页面上看不到时会误以为文档内容缺失。</p>
     *
     * <p>不超过 {@link #MAX_INLINE_ATTACHMENT_BYTES} 的对象同时给出下载链接
     * （内联为 data URI），使其可直接取用而非仅"看得到名字"。</p>
     *
     * @param body    输出缓冲区
     * @param content 原文件字节
     */
    private void appendEmbeddedObjectsSection(StringBuilder body, byte[] content) {
        // 注意：内嵌对象的字节必须在 POIFSFileSystem 打开期间读取。
        // Entry 只是句柄，一旦文件系统关闭再读就会失败（曾因此导致下载链接静默丢失）。
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            List<Entry> objects = new ArrayList<>();
            collectEmbeddedObjects(fs.getRoot(), objects);
            if (objects.isEmpty()) {
                return;
            }
            body.append("<div class=\"sheet\"><div class=\"sheet-head\">内嵌对象 / 附件 (")
                    .append(objects.size()).append(")</div><div class=\"attach\">");
            for (Entry obj : objects) {
                long size = obj instanceof DocumentEntry de ? de.getSize() : 0L;
                body.append("<div class=\"attach-item\">").append(escape(obj.getName()))
                        .append(" <span class=\"attach-size\">").append(size).append(" 字节</span>");
                String link = buildAttachmentLink(obj, size);
                if (link != null) {
                    body.append(" ").append(link);
                }
                body.append("</div>");
            }
            body.append("</div></div>");
        } catch (Exception e) {
            log.debug("读取内嵌对象失败: {}", e.getMessage());
        }
    }

    /**
     * 为内嵌对象构建下载链接（小对象内联为 data URI）。
     *
     * @param obj  内嵌对象条目
     * @param size 字节数
     * @return 下载链接 HTML；对象过大或不可读时返回 {@code null}
     */
    private String buildAttachmentLink(Entry obj, long size) {
        if (!(obj instanceof DocumentEntry de) || size <= 0 || size > MAX_INLINE_ATTACHMENT_BYTES) {
            return null;
        }
        try (DocumentInputStream in = new DocumentInputStream(de)) {
            byte[] data = in.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(data);
            return "<a class=\"attach-dl\" download=\"" + escape(obj.getName())
                    + "\" href=\"data:application/octet-stream;base64," + base64 + "\">下载</a>";
        } catch (Exception e) {
            log.debug("读取内嵌对象 {} 失败: {}", obj.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 递归收集 ObjectPool 下的内嵌对象。
     *
     * @param dir     当前目录
     * @param objects 收集器
     * @throws Exception 遍历异常
     */
    private void collectEmbeddedObjects(DirectoryEntry dir, List<Entry> objects) throws Exception {
        java.util.Iterator<Entry> it = dir.getEntries();
        while (it.hasNext()) {
            Entry e = it.next();
            if (!(e instanceof DirectoryEntry sub)) {
                continue;
            }
            if (OBJECT_POOL_STREAM.equals(sub.getName())) {
                java.util.Iterator<Entry> objs = sub.getEntries();
                while (objs.hasNext()) {
                    objects.add(objs.next());
                }
            } else {
                collectEmbeddedObjects(sub, objects);
            }
        }
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
            List<byte[]> images = new ArrayList<>();
            for (org.apache.poi.hssf.usermodel.HSSFPictureData pic : wb.getAllPictures()) {
                images.add(pic.getData());
            }
            appendImagesSection(body, images, "工作簿");
        } catch (org.apache.poi.OldFileFormatException e) {
            return PreviewResult.builder()
                    .htmlContent(page(unavailableHtml("Excel 97-2003 (.xls) 解析失败: " + escape(e.getMessage())))).build();
        }
        appendEmbeddedObjectsSection(body, content);
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
        // 演示文稿内嵌图片与内嵌对象
        List<byte[]> pptImages = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(content);
             HSLFSlideShow show = new HSLFSlideShow(in)) {
            for (org.apache.poi.hslf.usermodel.HSLFPictureData pic : show.getPictureData()) {
                pptImages.add(pic.getData());
            }
        } catch (Exception e) {
            log.debug("读取 ppt 内嵌图片失败: {}", e.getMessage());
        }
        appendImagesSection(body, pptImages, "演示文稿");
        appendEmbeddedObjectsSection(body, content);
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
                // WordToHtmlConverter 输出的富文本排版：标题层级、列表、表格、内联图片
                + ".doc h1{font-size:24px;font-weight:700;margin:20px 0 12px}"
                + ".doc h2{font-size:20px;font-weight:700;margin:18px 0 10px}"
                + ".doc h3{font-size:17px;font-weight:600;margin:16px 0 8px}"
                + ".doc h4,.doc h5,.doc h6{font-size:15px;font-weight:600;margin:14px 0 6px}"
                + ".doc ul,.doc ol{margin:0 0 10px;padding-left:28px}"
                + ".doc img{max-width:100%;height:auto;border-radius:4px}"
                + ".doc blockquote{margin:0 0 10px;padding:6px 14px;border-left:3px solid #d1d5db;color:#4b5563;background:#f9fafb}"
                + ".doc a{color:#2563eb}"
                + ".doc table{border-collapse:collapse;margin:12px 0;font-size:13px}"
                + ".doc td,.doc th{border:1px solid #e5e7eb;padding:6px 10px;vertical-align:top}"
                + ".doc pre{background:#f3f4f6;border-radius:6px;padding:12px;overflow:auto}"
                + ".warn{background:#fffbeb;border:1px solid #fde68a;color:#92400e;border-radius:8px;padding:10px 14px;margin:0 0 14px;font-size:13px}"
                // Excel / PPT 内嵌图片与内嵌对象（附件）
                + ".images{padding:16px;display:flex;flex-wrap:wrap;gap:12px;align-items:flex-start}"
                + ".images img{max-width:220px;max-height:220px;border:1px solid #e5e7eb;border-radius:6px;background:#fff}"
                + ".attach{padding:8px 16px 14px}"
                + ".attach-item{padding:6px 0;border-bottom:1px dashed #f0f0f0;font-size:13px}"
                + ".attach-size{color:#9ca3af;font-size:12px;margin-left:6px}"
                + ".attach-dl{margin-left:8px;color:#2563eb;font-size:12px;text-decoration:underline;cursor:pointer}"
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
