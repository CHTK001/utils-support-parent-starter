package com.chua.word.support.export;

import com.chua.common.support.export.ExportConfig;
import com.chua.common.support.export.ExportException;
import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.TemplateContext;
import com.chua.common.support.export.spi.TemplateFiller;
import com.chua.common.support.export.support.Placeholders;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POI Word 模板填充器 —— 接口二在 Word 侧的实现。
 *
 * <p>不依赖任何第三方模板库，直接在 POI 之上实现，语法与 Excel 侧完全一致：</p>
 * <ul>
 *     <li><b>标量</b>：段落与单元格中的 {@code {{key}}} 被替换，格式保持不变；
 *         一个段落内多个占位符同样支持</li>
 *     <li><b>嵌套</b>：{@code {{customer.address.city}}} 在循环行内直接读原始对象，
 *         未导出为列的字段用 {@code {{_row.field}}}</li>
 *     <li><b>循环</b>：表格中以 {@code {{#tag}}} 行起始、{@code {{/tag}}} 行结束，
 *         中间整行为模板，按数据条数复制；区块内的 {@code {{field}}} 逐条替换。
 *         两行结构性标记本身不产出内容，填充后被移除；块内无数据时整块消失</li>
 *     <li><b>元信息</b>：{@code {{@index}}} / {@code {{@first}}} / {@code {{@last}}}</li>
 * </ul>
 *
 * <pre>{@code
 * | 订单号      | 状态       |   ← 表头
 * | {{#rows}}   |            |   ← 标记行，填充后移除
 * | {{orderNo}} | 第{{@index}}条 | ← 模板行，复制 N 次
 * | {{/rows}}   |            |   ← 标记行，填充后移除
 * }</pre>
 *
 * <p>占位符由核心的 {@link Placeholders} 解析，与 Excel / PDF 侧语法完全一致。
 * 标签前后缀由 {@link ExportConfig#getTagPrefix()} / {@code getTagSuffix()} 决定。</p>
 *
 * <p>仅支持 OOXML（{@code .docx}）；旧版二进制 {@code .doc} 无法处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"poi-word", "word"}, order = 0)
public class PoiWordTemplateFiller implements TemplateFiller {

    @Override
    /**
     * 支持的格式
     * @return 格式 对象
     */
    public Set<String> formats() {
        return Set.of(ExportFormats.DOCX);
    }

    @Override
    /**
     * 引擎名
     * @return 引擎名 对象
     */
    public String engine() {
        return "poi-word";
    }

    @Override
    /**
     * 填充模板
     * @param context 模板上下文
     * @param template 模板输入流
     * @param out      输出流
     * @throws IOException 读写异常
     */
    public void fill(TemplateContext context, InputStream template, OutputStream out) throws IOException {
        Placeholders engine = Placeholders.of(context);
        // XWPFDocument 持有并关闭底层流；先读入内存，保证调用方的模板流不被框架关闭
        byte[] source = template.readAllBytes();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(source))) {
            for (XWPFTable table : document.getTables()) {
                expandTable(document, table, engine);
            }
            renderBody(document, engine);
            document.write(out);
        }
    }

    /**
     * 展开单个表格的循环区块，随后渲染标量占位符。
     *
     * <p>结构变更直接操作底层 {@link CTTbl}，因此每次变更后都重新构建
     * {@link XWPFTable} 视图，避免 POI 内部行缓存与实际结构不一致。</p>
     *
     * @param document 文档
     * @param table    表格
     * @param engine   占位符引擎
     */
    private void expandTable(XWPFDocument document, XWPFTable table, Placeholders engine) {
        List<Block> blocks = collectBlocks(table, engine);
        for (int i = blocks.size() - 1; i >= 0; i--) {
            applyBlock(document, table, engine, blocks.get(i));
        }
        for (XWPFTableRow row : freshView(document, table).getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    renderParagraph(paragraph, engine, null, 0, 0);
                }
            }
        }
    }

    /**
     * 渲染正文段落中的标量占位符。
     *
     * @param document 文档
     * @param engine   占位符引擎
     */
    private void renderBody(XWPFDocument document, Placeholders engine) {
        for (IBodyElement element : new ArrayList<>(document.getBodyElements())) {
            if (element instanceof XWPFParagraph paragraph) {
                renderParagraph(paragraph, engine, null, 0, 0);
            }
        }
    }

    /**
     * 收集表格中的全部循环区块。
     *
     * @param table  表格
     * @param engine 占位符引擎
     * @return 区块列表
     */
    private List<Block> collectBlocks(XWPFTable table, Placeholders engine) {
        List<Block> blocks = new ArrayList<>();
        List<Integer> opens = new ArrayList<>();
        List<XWPFTableRow> rows = table.getRows();
        for (int r = 0; r < rows.size(); r++) {
            String text = rowText(rows.get(r));
            if (engine.isLoopStart(text)) {
                opens.add(r);
            } else if (engine.isLoopEnd(text) && !opens.isEmpty()) {
                int start = opens.remove(opens.size() - 1);
                blocks.add(new Block(start, r, engine.loopTag(text, "/")));
            }
        }
        return blocks;
    }

    /**
     * 应用循环区块：把模板行按数据复制。
     *
     * @param document 文档
     * @param table    表格
     * @param engine   占位符引擎
     * @param block    区块
     */
    private void applyBlock(XWPFDocument document, XWPFTable table, Placeholders engine, Block block) {
        List<Map<String, Object>> data = engine.table(block.tag());
        CTTbl ctTable = table.getCTTbl();

        // 先快照为 XML 文本：removeTr 之后原 CTRow 会与文档断开连接
        List<String> source = new ArrayList<>();
        for (CTRow row : ctTable.getTrArray()) {
            source.add(row.xmlText());
        }

        int first = Math.min(block.startRow(), source.size());
        int last = Math.min(block.endRow(), source.size() - 1);
        if (last < first) {
            return;
        }
        int templateHeight = last - first - 1;
        List<String> templates = source.subList(first + 1, last);

        List<String> result = new ArrayList<>(source.subList(0, first));
        if (!templates.isEmpty() && !data.isEmpty()) {
            for (int i = 0; i < data.size(); i++) {
                result.addAll(templates);
            }
        }
        result.addAll(source.subList(last + 1, source.size()));

        while (ctTable.sizeOfTrArray() > 0) {
            ctTable.removeTr(0);
        }
        for (String xml : result) {
            ctTable.addNewTr().set(parse(xml));
        }

        if (templateHeight <= 0 || data.isEmpty()) {
            return;
        }
        XWPFTable view = freshView(document, table);
        int writeAt = first;
        for (int i = 0; i < data.size(); i++) {
            for (int k = 0; k < templateHeight; k++) {
                XWPFTableRow target = view.getRow(writeAt + k);
                if (target != null) {
                    renderRow(target, engine, data.get(i), i, data.size());
                }
            }
            writeAt += templateHeight;
        }
    }

    /**
     * 渲染整行的占位符。
     *
     * @param row    行
     * @param engine 占位符引擎
     * @param data   当前数据
     * @param index  数据序号
     * @param count  数据总数
     */
    private void renderRow(XWPFTableRow row, Placeholders engine, Map<String, Object> data, int index, int count) {
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph paragraph : cell.getParagraphs()) {
                renderParagraph(paragraph, engine, data, index, count);
            }
        }
    }

    /**
     * 渲染段落中的占位符，保留首个 run 的格式并丢弃其余 run。
     *
     * @param paragraph 段落
     * @param engine    占位符引擎
     * @param data      当前循环行，可为 {@code null}
     * @param index     数据序号
     * @param count     数据总数
     */
    private void renderParagraph(XWPFParagraph paragraph, Placeholders engine,
                                 Map<String, Object> data, int index, int count) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (XWPFRun run : runs) {
            text.append(run.text());
        }
        String source = text.toString();
        if (!engine.contains(source)) {
            return;
        }
        String rendered = engine.render(source, data, index, count);
        XWPFRun target = runs.get(0);
        for (int i = runs.size() - 1; i >= 1; i--) {
            paragraph.removeRun(i);
        }
        target.setText(rendered, 0);
    }

    /**
     * 读取整行文本。
     *
     * @param row 行
     * @return 拼接文本
     */
    private static String rowText(XWPFTableRow row) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) {
            sb.append(cell.getText()).append(' ');
        }
        return sb.toString();
    }

    /**
     * 解析一行 XML。
     *
     * @param xml 行 XML
     * @return 行对象
     */
    private static CTRow parse(String xml) {
        try {
            return CTRow.Factory.parse(xml);
        } catch (Exception e) {
            throw new ExportException("解析 Word 模板行失败", e);
        }
    }

    /**
     * 依据底层 XML 重建表格视图，使 POI 的行缓存与实际结构一致。
     *
     * @param document 文档
     * @param table    原表格
     * @return 新视图
     */
    private static XWPFTable freshView(XWPFDocument document, XWPFTable table) {
        return new XWPFTable(table.getCTTbl(), document);
    }

    /**
     * 循环区块。
     *
     * @param startRow 起始行下标（含）
     * @param endRow   结束行下标（含）
     * @param tag      标签名
     */
    private record Block(int startRow, int endRow, String tag) {
    }
}
