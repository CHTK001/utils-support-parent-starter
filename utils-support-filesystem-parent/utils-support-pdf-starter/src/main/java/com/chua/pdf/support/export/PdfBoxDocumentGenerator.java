package com.chua.pdf.support.export;

import com.chua.common.support.export.ExportContext;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.DocumentGenerator;
import com.chua.common.support.export.support.Rows;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/**
 * PDFBox 文档生成器 —— 接口一在 PDF 侧的实现。
 *
 * <p>输出 A4 横向表格 PDF：可选标题、表头（深蓝底白字）、网格线、自动换行与分页。
 * 字体由 {@link PdfFonts} 解析，默认自动挑选系统中文字体。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"pdfbox", "pdf"}, order = 0)
public class PdfBoxDocumentGenerator implements DocumentGenerator {

    /**
     * 页面边距（点）。
     */
    private static final float MARGIN = 36f;

    /**
     * 标题字号。
     */
    private static final float TITLE_SIZE = 14f;

    /**
     * 表头字号。
     */
    private static final float HEADER_SIZE = 9f;

    /**
     * 正文字号。
     */
    private static final float BODY_SIZE = 9f;

    /**
     * 行距系数。
     */
    private static final float LINE_HEIGHT = 1.35f;

    /**
     * 单元格内边距。
     */
    private static final float PADDING = 3f;

    /**
     * 表头底色 RGB。
     */
    private static final float[] HEADER_RGB = {0.12f, 0.31f, 0.47f};

    /**
     * 网格线灰度。
     */
    private static final float GRID = 0.75f;

    @Override
    /**
     * 支持的格式
     * @return 格式 对象
     */
    public Set<String> formats() {
        return Set.of(ExportFormats.PDF);
    }

    @Override
    /**
     * 引擎名
     * @return 引擎名 对象
     */
    public String engine() {
        return "pdfbox";
    }

    @Override
    /**
     * 生成文档
     * @param context 导出上下文
     * @param out     输出流
     * @throws IOException 写出异常
     */
    public void generate(ExportContext context, OutputStream out) throws IOException {
        List<ColumnMeta> columns = context.columns();
        if (columns.isEmpty()) {
            throw new ExportException("没有可导出的列，请检查 @ExportColumn 或列限定参数");
        }
        DocumentMeta meta = context.meta();
        try (PDDocument document = new PDDocument()) {
            PDFont font = PdfFonts.load(document);
            Canvas canvas = new Canvas(document, font);
            float[] widths = widths(columns);
            String title = meta.getTitle().isEmpty() ? meta.resolveSheetName() : meta.getTitle();
            canvas.title(title, widths);

            if (meta.isHeader()) {
                canvas.header(columns, widths);
            }
            int index = 0;
            for (Object row : context.rows()) {
                String[] values = new String[columns.size()];
                for (int c = 0; c < columns.size(); c++) {
                    Object value = Rows.display(row, columns.get(c));
                    values[c] = value == null ? "" : String.valueOf(value);
                }
                canvas.body(values, widths);
                index++;
                if (meta.getRowsPerSheet() > 0 && index % meta.getRowsPerSheet() == 0) {
                    canvas.newPage();
                }
            }
            canvas.flush();
            document.save(out);
        }
    }

    /**
     * 按标题长度分配列宽。
     *
     * @param columns 列
     * @return 每列宽度（点）
     */
    private float[] widths(List<ColumnMeta> columns) {
        float usable = PDRectangle.A4.getWidth() - 2 * MARGIN;
        float[] widths = new float[columns.size()];
        int total = 0;
        for (int c = 0; c < columns.size(); c++) {
            widths[c] = Math.max(60f, Math.min(240f, columns.get(c).getName().length() * 11f + PADDING * 2));
            total += (int) widths[c];
        }
        if (total > usable) {
            float ratio = usable / total;
            for (int c = 0; c < widths.length; c++) {
                widths[c] *= ratio;
            }
        }
        return widths;
    }

    /**
     * 简易排版画布。
     */
    private static final class Canvas {

        /**
         * 文档。
         */
        private final PDDocument document;

        /**
         * 字体。
         */
        private final PDFont font;

        /**
         * 当前页。
         */
        private PDPage page;

        /**
         * 当前内容流。
         */
        private PDPageContentStream stream;

        /**
         * 当前纵坐标。
         */
        private float cursor;

        /**
         * 构造画布。
         *
         * @param document 文档
         * @param font     字体
         * @throws IOException 创建首页失败
         */
        private Canvas(PDDocument document, PDFont font) throws IOException {
            this.document = document;
            this.font = font;
            newPage();
        }

        /**
         * 新建页面。
         *
         * @throws IOException 写出失败
         */
        private void newPage() throws IOException {
            closeStream();
            page = new PDPage(PDRectangle.A4);
            page.setRotation(0);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            cursor = page.getMediaBox().getHeight() - MARGIN;
        }

        /**
         * 关闭内容流。
         *
         * @throws IOException 写出失败
         */
        private void closeStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        /**
         * 冲刷内容流。
         *
         * @throws IOException 写出失败
         */
        private void flush() throws IOException {
            closeStream();
        }

        /**
         * 绘制标题。
         *
         * @param text   标题
         * @param widths 列宽
         * @throws IOException 写出失败
         */
        private void title(String text, float[] widths) throws IOException {
            if (text == null || text.isEmpty()) {
                return;
            }
            ensure(TITLE_SIZE * LINE_HEIGHT * 2f);
            stream.beginText();
            stream.setFont(font, TITLE_SIZE);
            stream.newLineAtOffset(MARGIN, cursor - TITLE_SIZE);
            stream.showText(sanitize(text));
            stream.endText();
            cursor -= TITLE_SIZE * LINE_HEIGHT * 1.8f;
        }

        /**
         * 绘制表头。
         *
         * @param columns 列
         * @param widths  列宽
         * @throws IOException 写出失败
         */
        private void header(List<ColumnMeta> columns, float[] widths) throws IOException {
            String[] headers = new String[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                headers[c] = columns.get(c).getName();
            }
            row(headers, widths, HEADER_SIZE, true);
        }

        /**
         * 绘制数据行。
         *
         * @param values 值
         * @param widths 列宽
         * @throws IOException 写出失败
         */
        private void body(String[] values, float[] widths) throws IOException {
            row(values, widths, BODY_SIZE, false);
        }

        /**
         * 绘制一行（自动换行、自动分页）。
         *
         * @param values    值
         * @param widths    列宽
         * @param size      字号
         * @param header    是否表头
         * @throws IOException 写出失败
         */
        private void row(String[] values, float[] widths, float size, boolean header) throws IOException {
            String[][] wrapped = new String[values.length][];
            int lines = 1;
            for (int c = 0; c < values.length; c++) {
                wrapped[c] = wrap(values[c], widths[c] - 2 * PADDING, size);
                lines = Math.max(lines, wrapped[c].length);
            }
            float lineHeight = size * LINE_HEIGHT;
            float rowHeight = lines * lineHeight + 2 * PADDING;
            if (cursor - rowHeight < MARGIN) {
                newPage();
            }
            float top = cursor;
            for (int c = 0; c < values.length; c++) {
                float x = MARGIN + offset(widths, c);
                if (header) {
                    stream.setNonStrokingColor(HEADER_RGB[0], HEADER_RGB[1], HEADER_RGB[2]);
                    stream.addRect(x, top - rowHeight, widths[c], rowHeight);
                    stream.fill();
                }
                stream.setNonStrokingColor(header ? 1f : 0f, header ? 1f : 0f, header ? 1f : 0f);
                stream.beginText();
                stream.setFont(font, size);
                float baseline = top - PADDING - size;
                for (String line : wrapped[c]) {
                    // 必须用绝对文本矩阵：newLineAtOffset 是相对偏移，多行会累计漂移
                    stream.setTextMatrix(Matrix.getTranslateInstance(x + PADDING, baseline));
                    stream.showText(sanitize(line));
                    baseline -= lineHeight;
                }
                stream.endText();
                grid(x, top - rowHeight, widths[c], rowHeight);
            }
            cursor -= rowHeight;
        }

        /**
         * 绘制网格线。
         *
         * @param x      左
         * @param y      下
         * @param width  宽
         * @param height 高
         * @throws IOException 写出失败
         */
        private void grid(float x, float y, float width, float height) throws IOException {
            stream.setNonStrokingColor(GRID, GRID, GRID);
            stream.addRect(x, y, width, height);
            stream.stroke();
        }

        /**
         * 保证剩余纵向空间足够，否则分页。
         *
         * @param needed 需要的空间
         * @throws IOException 写出失败
         */
        private void ensure(float needed) throws IOException {
            if (cursor - needed < MARGIN) {
                newPage();
            }
        }

        /**
         * 计算列偏移。
         *
         * @param widths 列宽
         * @param index  列序号
         * @return 偏移量
         */
        private float offset(float[] widths, int index) {
            float sum = 0f;
            for (int i = 0; i < index; i++) {
                sum += widths[i];
            }
            return sum;
        }

        /**
         * 按像素宽度折行。
         *
         * @param text   文本
         * @param maxWidth 最大宽度（点）
         * @param size   字号
         * @return 折行结果
         * @throws IOException 度量失败
         */
        private String[] wrap(String text, float maxWidth, float size) throws IOException {
            if (text == null || text.isEmpty()) {
                return new String[]{""};
            }
            StringBuilder line = new StringBuilder();
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    lines.add(line.toString());
                    line.setLength(0);
                    continue;
                }
                line.append(ch);
                if (font.getStringWidth(line.toString()) / 1000f * size > maxWidth && line.length() > 1) {
                    line.deleteCharAt(line.length() - 1);
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(ch);
                }
            }
            if (line.length() > 0 || lines.isEmpty()) {
                lines.add(line.toString());
            }
            return lines.toArray(new String[0]);
        }

        /**
         * 清洗不可渲染字符。
         *
         * @param text 文本
         * @return 可渲染文本
         */
        private String sanitize(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                sb.append(ch == '\t' ? ' ' : ch);
            }
            return sb.toString();
        }
    }
}