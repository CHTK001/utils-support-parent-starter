package com.chua.excel.support.export;

import com.chua.common.support.export.ExportFormats;
import com.chua.common.support.export.TemplateContext;
import com.chua.common.support.export.spi.TemplateFiller;
import com.chua.common.support.export.support.Placeholders;
import com.chua.common.support.spi.annotations.Spi;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POI 模板填充器 —— 接口二在 Excel 侧的实现。
 *
 * <p>不依赖任何第三方模板库，直接在 POI 之上实现，占位符由核心的
 * {@link Placeholders} 解析，与 Word / PDF 侧语法完全一致：</p>
 * <ul>
 *     <li><b>标量</b>：单元格内的 {@code {{key}}} 被替换，单元格样式保持不变；
 *         整格恰好一个占位符时写入原生类型（数值 / 日期 / 布尔）</li>
 *     <li><b>嵌套</b>：{@code {{customer.address.city}}} 在循环行内直接读原始对象，
 *         未导出为列的字段用 {@code {{_row.field}}}</li>
 *     <li><b>循环</b>：以 {@code {{#tag}}} 行起始、{@code {{/tag}}} 行结束，
 *         中间整行为模板，按数据条数复制；块内的 {@code {{field}}} 逐条替换。
 *         两行结构性标记本身不产出内容，填充后被移除；块内无数据时整块消失</li>
 *     <li><b>元信息</b>：{@code {{@index}}} / {@code {{@first}}} / {@code {{@last}}}</li>
 * </ul>
 *
 * <pre>{@code
 * | {{title}}            |                          |   ← 标量
 * | {{#users}}           |                          |   ← 标记行，填充后移除
 * | {{name}} | {{age}}   | 第{{@index}}条          |   ← 模板行，复制 N 次
 * | {{/users}}           |                          |   ← 标记行，填充后移除
 * }</pre>
 *
 * <p>模板块内的合并区域会按数据条数平移重建。</p>
 *
 * <p>仅支持 OOXML（{@code .xlsx} / {@code .xlsm}）；旧版二进制 {@code .xls} 无法处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = {"poi", "excel"}, order = 0)
public class PoiTemplateFiller implements TemplateFiller {

    @Override
    /**
     * 支持的格式
     * @return 格式 对象
     */
    public Set<String> formats() {
        return Set.of(ExportFormats.XLSX);
    }

    @Override
    /**
     * 引擎名
     * @return 引擎名 对象
     */
    public String engine() {
        return "poi";
    }

    @Override
    /**
     * 填充模板
     * @param context  模板上下文
     * @param template 模板输入流
     * @param out      输出流
     * @throws IOException 读写异常
     */
    public void fill(TemplateContext context, InputStream template, OutputStream out) throws IOException {
        Placeholders engine = Placeholders.of(context);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template.readAllBytes()))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                fillSheet(workbook.getSheetAt(i), engine);
            }
            workbook.write(out);
        }
    }

    /**
     * 填充单个工作表。
     *
     * @param sheet  工作表
     * @param engine 模板引擎
     */
    private void fillSheet(Sheet sheet, Placeholders engine) {
        // 循环会插入/删除行，先把块收集出来，再自下而上处理，避免行号漂移
        List<Block> blocks = collectBlocks(sheet, engine);
        for (int i = blocks.size() - 1; i >= 0; i--) {
            applyBlock(sheet, engine, blocks.get(i));
        }
        renderScalars(sheet, engine);
    }

    /**
     * 循环区块。
     */
    private static final class Block {

        /**
         * 起始行下标（含）。
         */
        private final int startRow;

        /**
         * 结束行下标（含）。
         */
        private final int endRow;

        /**
         * 标签名。
         */
        private final String tag;

        /**
         * 构造区块。
         *
         * @param startRow 起始行
         * @param endRow   结束行
         * @param tag      标签
         */
        private Block(int startRow, int endRow, String tag) {
            this.startRow = startRow;
            this.endRow = endRow;
            this.tag = tag;
        }
    }

    /**
     * 收集工作表中的全部循环区块。
     *
     * @param sheet  工作表
     * @param engine 模板引擎
     * @return 区块列表
     */
    private List<Block> collectBlocks(Sheet sheet, Placeholders engine) {
        List<Block> blocks = new ArrayList<>();
        java.util.Deque<int[]> stack = new java.util.ArrayDeque<>();
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.STRING) {
                    continue;
                }
                String text = cell.getStringCellValue();
                if (engine.isLoopStart(text)) {
                    stack.push(new int[]{r, -1});
                } else if (engine.isLoopEnd(text) && !stack.isEmpty()) {
                    int[] open = stack.pop();
                    blocks.add(new Block(open[0], r, engine.loopTag(text, "/")));
                }
            }
        }
        return blocks;
    }

    /**
     * 应用循环区块：把模板行按数据复制。
     *
     * @param sheet  工作表
     * @param engine 模板引擎
     * @param block  区块
     */
    private void applyBlock(Sheet sheet, Placeholders engine, Block block) {
        List<Map<String, Object>> data = engine.table(block.tag);
        int templateRows = block.endRow - block.startRow - 1;

        // 收集模板行的完整快照，保证多次复制不受插入影响
        List<RowSnapshot> snapshots = new ArrayList<>(Math.max(templateRows, 0));
        for (int r = block.startRow + 1; r < block.endRow; r++) {
            snapshots.add(RowSnapshot.of(sheet.getRow(r)));
        }
        List<CellRangeAddress> merges = mergedRegions(sheet, block);

        // 结构性标记行（{{#tag}} / {{/tag}}）不产出内容，整体删除
        for (int r = block.endRow; r >= block.startRow; r--) {
            removeRow(sheet, r);
        }

        if (data.isEmpty() || snapshots.isEmpty()) {
            return;
        }

        int writeAt = block.startRow;
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            for (RowSnapshot snapshot : snapshots) {
                snapshot.paste(sheet, writeAt++, engine, row, i, data.size());
            }
        }
        // 模板行上的合并区域按数据条数平移重建
        int blockHeight = Math.max(templateRows, 1);
        for (int i = 0; i < data.size(); i++) {
            int base = block.startRow + i * blockHeight;
            for (CellRangeAddress region : merges) {
                sheet.addMergedRegion(new CellRangeAddress(
                        base + region.getFirstRow() - block.startRow - 1,
                        base + region.getLastRow() - block.startRow - 1,
                        region.getFirstColumn(), region.getLastColumn()));
            }
        }
    }

    /**
     * 收集模板行内部的合并区域。
     *
     * @param sheet 工作表
     * @param block 区块
     * @return 合并区域列表（行号为绝对下标，基准行 {@code block.startRow + 1}）
     */
    private static List<CellRangeAddress> mergedRegions(Sheet sheet, Block block) {
        List<CellRangeAddress> regions = new ArrayList<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            if (region.getFirstRow() > block.startRow && region.getLastRow() < block.endRow) {
                regions.add(region.copy());
            }
        }
        return regions;
    }

    /**
     * 删除行（含合并区域调整）。
     *
     * @param sheet 工作表
     * @param index 行下标
     */
    private void removeRow(Sheet sheet, int index) {
        Row row = sheet.getRow(index);
        if (row != null) {
            sheet.removeRow(row);
        }
        List<CellRangeAddress> regions = new ArrayList<>(sheet.getMergedRegions());
        for (CellRangeAddress region : regions) {
            if (region.getFirstRow() <= index && index <= region.getLastRow()) {
                sheet.removeMergedRegion(regions.indexOf(region));
            }
        }
    }

    /**
     * 渲染剩余的标量占位符。
     *
     * @param sheet  工作表
     * @param engine 模板引擎
     */
    private void renderScalars(Sheet sheet, Placeholders engine) {
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING) {
                    String rendered = engine.render(cell.getStringCellValue(), null, 0, 0);
                    if (!rendered.equals(cell.getStringCellValue())) {
                        cell.setCellValue(rendered);
                    }
                }
            }
        }
    }

    /**
     * 行快照 —— 深拷贝一行的单元格内容与样式，供循环复制使用。
     */
    private static final class RowSnapshot {

        /**
         * 单元格快照。
         */
        private final CellSnapshot[] cells;

        /**
         * 行高。
         */
        private final float height;

        /**
         * 构造行快照。
         *
         * @param cells  单元格快照
         * @param height 行高
         */
        private RowSnapshot(CellSnapshot[] cells, float height) {
            this.cells = cells;
            this.height = height;
        }

        /**
         * 采集行快照。
         *
         * @param row 行，可为 {@code null}
         * @return 快照
         */
        private static RowSnapshot of(Row row) {
            if (row == null) {
                return new RowSnapshot(new CellSnapshot[0], -1f);
            }
            int last = row.getLastCellNum();
            if (last < 0) {
                return new RowSnapshot(new CellSnapshot[0], row.getHeightInPoints());
            }
            CellSnapshot[] snapshots = new CellSnapshot[last];
            for (int c = 0; c < last; c++) {
                snapshots[c] = CellSnapshot.of(row.getCell(c));
            }
            return new RowSnapshot(snapshots, row.getHeightInPoints());
        }

        /**
         * 把快照写入指定行。
         *
         * @param sheet    工作表
         * @param rowIndex 行下标
         * @param engine   模板引擎
         * @param row      当前数据
         * @param index    数据序号
         * @param count    数据总数
         */
        private void paste(Sheet sheet, int rowIndex, Placeholders engine,
                           Map<String, Object> row, int index, int count) {
            Row target = sheet.getRow(rowIndex);
            if (target == null) {
                target = sheet.createRow(rowIndex);
            }
            if (height > 0) {
                target.setHeightInPoints(height);
            }
            for (int c = 0; c < cells.length; c++) {
                cells[c].paste(target, c, engine, row, index, count);
            }
        }
    }

    /**
     * 单元格快照。
     */
    private static final class CellSnapshot {

        /**
         * 单元格类型。
         */
        private final CellType type;

        /**
         * 文本内容。
         */
        private final String text;

        /**
         * 数值。
         */
        private final double number;

        /**
         * 布尔值。
         */
        private final boolean flag;

        /**
         * 样式。
         */
        private final org.apache.poi.ss.usermodel.CellStyle style;

        /**
         * 构造单元格快照。
         *
         * @param type   单元格类型
         * @param text   文本
         * @param number 数值
         * @param flag   布尔值
         * @param style  样式
         */
        private CellSnapshot(CellType type, String text, double number, boolean flag,
                             org.apache.poi.ss.usermodel.CellStyle style) {
            this.type = type;
            this.text = text;
            this.number = number;
            this.flag = flag;
            this.style = style;
        }

        /**
         * 采集单元格快照。
         *
         * @param cell 单元格，可为 {@code null}
         * @return 快照
         */
        private static CellSnapshot of(Cell cell) {
            if (cell == null) {
                return new CellSnapshot(CellType.BLANK, "", 0d, false, null);
            }
            return switch (cell.getCellType()) {
                case STRING -> new CellSnapshot(CellType.STRING, cell.getStringCellValue(), 0d, false, cell.getCellStyle());
                case NUMERIC -> new CellSnapshot(CellType.NUMERIC, null, cell.getNumericCellValue(), false, cell.getCellStyle());
                case BOOLEAN -> new CellSnapshot(CellType.BOOLEAN, null, 0d, cell.getBooleanCellValue(), cell.getCellStyle());
                case FORMULA -> new CellSnapshot(CellType.STRING, "=" + cell.getCellFormula(), 0d, false, cell.getCellStyle());
                default -> new CellSnapshot(CellType.BLANK, null, 0d, false, cell.getCellStyle());
            };
        }

        /**
         * 把快照写入目标单元格。
         *
         * @param row    目标行
         * @param column 列下标
         * @param engine 模板引擎
         * @param data   当前数据
         * @param index  数据序号
         * @param count  数据总数
         */
        private void paste(Row row, int column, Placeholders engine,
                           Map<String, Object> data, int index, int count) {
            Cell cell = row.getCell(column);
            if (cell == null) {
                cell = row.createCell(column);
            }
            if (style != null) {
                cell.setCellStyle(style);
            }
            switch (type) {
                case STRING -> {
                    Object resolved = engine.renderValue(text == null ? "" : text, data, index, count);
                    writeValue(cell, resolved);
                }
                case NUMERIC -> cell.setCellValue(number);
                case BOOLEAN -> cell.setCellValue(flag);
                default -> cell.setBlank();
            }
        }

        /**
         * 按值类型写入单元格。
         *
         * @param cell  单元格
         * @param value 值
         */
        private static void writeValue(Cell cell, Object value) {
            if (value == null) {
                cell.setBlank();
            } else if (value instanceof Boolean flag) {
                cell.setCellValue(flag);
            } else if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(String.valueOf(value));
            }
        }
    }
}
