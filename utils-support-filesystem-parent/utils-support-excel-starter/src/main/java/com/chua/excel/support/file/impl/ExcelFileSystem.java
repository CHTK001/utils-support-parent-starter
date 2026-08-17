package com.chua.excel.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.excel.support.file.config.CellStyleConfig;
import com.chua.excel.support.file.config.ColumnConfig;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Excel 文件系统 SPI 实现（基于 Apache POI）。
 *
 * <p>支持 .xlsx / .xls 格式，提供丰富的读写能力：</p>
 *
 * <h2>写入特性</h2>
 * <ul>
 *     <li><b>样式</b> — 通过 {@link CellStyleConfig} 自定义字体、颜色、边框、对齐等</li>
 *     <li><b>固定表头</b> — {@code freezeHeader()} 冻结首行，滚动时不消失</li>
 *     <li><b>自动过滤</b> — {@code withFilter()} 启用 Excel 筛选器</li>
 *     <li><b>列配置</b> — 通过 {@link ColumnConfig} 设置列宽、显示名、样式</li>
 *     <li><b>多 Sheet</b> — {@code sheetName()} 自定义工作表名</li>
 * </ul>
 *
 * <h2>读取特性</h2>
 * <ul>
 *     <li><b>列投影</b> — {@code selectColumns("id","name")} 仅读取指定列</li>
 *     <li><b>行过滤</b> — {@code filterRows(row -> ...)} 按条件过滤行</li>
 *     <li><b>合并单元格</b> — 自动解析合并区域</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // ===== 写入：样式 + 固定表头 + 自动过滤 =====
 * CellStyleConfig headerStyle = CellStyleConfig.create()
 *     .bold().fontSize(12).backgroundColor("4472C4").fontColor("FFFFFF")
 *     .horizontalCenter().verticalCenter().border(BorderStyle.THIN);
 *
 * CellStyleConfig bodyStyle = CellStyleConfig.create()
 *     .border(BorderStyle.THIN).horizontalCenter();
 *
 * FileSystem fs = FileSystem.create("excel");
 * fs.write(new File("report.xlsx"))
 *    .withHeaders(List.of("id", "name", "score"))
 *    .headerStyle(headerStyle)
 *    .style(bodyStyle)
 *    .columnConfig("score", ColumnConfig.of("score").width(10))
 *    .freezeHeader()
 *    .withFilter()
 *    .write(rows)
 *    .finish();
 *
 * // ===== 多 Sheet 写入 =====
 * fs.write(new File("multi.xlsx"))
 *    .writeSheet("汇总", summaryRows)        // Sheet 1
 *    .freezeHeader().withFilter()
 *
 *    .writeSheet("明细")                       // Sheet 2
 *    .withHeaders(List.of("id", "name"))
 *    .headerStyle(headerStyle)
 *    .style(bodyStyle)
 *    .write(detailRows)
 *    .finish();
 *
 * // ===== 读取：列投影 + 行过滤 =====
 * List<Map<String, Object>> result = fs.read(new File("report.xlsx"))
 *    .selectColumns("id", "name")
 *    .filterRows(row -> row.get("score") != null)
 *    .rows();
 * }</pre>
 *
 * @author CH
 * @since 2026-07-20
 */
@Spi("excel")
public class ExcelFileSystem implements FileSystem {

    @Override
    public String getType() {
        return "excel";
    }

    @Override
    public ReadBuilder read(File file) {
        return new ExcelReadBuilder(file);
    }

    @Override
    public WriteBuilder write(File file) {
        return new ExcelWriteBuilder(file);
    }

    // ==================== Read ====================

    public static class ExcelReadBuilder extends ReadBuilder {

        private String sheetName;
        private int sheetIndex;
        /** 列投影（null 表示全部列） */
        private Set<String> selectedColumns;

        ExcelReadBuilder(File file) {
            super(file);
        }

        // ==================== 链式配置 ====================

        public ExcelReadBuilder withSheetName(String sheetName) {
            this.sheetName = sheetName;
            return this;
        }

        public ExcelReadBuilder withSheetIndex(int sheetIndex) {
            this.sheetIndex = sheetIndex;
            return this;
        }

        @Override
        public ExcelReadBuilder filter(Predicate<Map<String, Object>> filter) {
            super.filter(filter);
            return this;
        }

        /**
         * 设置列投影，仅读取指定列。
         *
         * @param columns 要读取的列名
         * @return 当前构建器
         */
        public ExcelReadBuilder selectColumns(String... columns) {
            this.selectedColumns = columns != null
                    ? new LinkedHashSet<>(Arrays.asList(columns))
                    : null;
            return this;
        }

        /**
         * 设置列投影。
         *
         * @param columns 要读取的列名列表
         * @return 当前构建器
         */
        public ExcelReadBuilder selectColumns(Collection<String> columns) {
            this.selectedColumns = columns != null
                    ? new LinkedHashSet<>(columns)
                    : null;
            return this;
        }

        @Override
        public ExcelReadBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        // ==================== 读取方法 ====================

        /**
         * 读取全部行为 Map 列表。
         */
        public List<Map<String, Object>> rows() {
            List<Map<String, Object>> result = new ArrayList<>();
            try (FileInputStream fis = new FileInputStream(file);
                 Workbook wb = WorkbookFactory.create(fis)) {
                Sheet sheet = resolveSheet(wb);

                // 构建合并单元格缓存
                java.util.Map<String, Object> mergedCache = buildMergedCache(sheet);

                Iterator<Row> it = sheet.iterator();
                if (!it.hasNext()) {
                    return result;
                }

                Row headerRow = it.next();
                List<String> allHeaders = new ArrayList<>();
                for (Cell cell : headerRow) {
                    allHeaders.add(cell.toString());
                }

                // 列投影：确定实际读取的列索引
                List<Integer> projectedIndices;
                if (selectedColumns != null && !selectedColumns.isEmpty()) {
                    projectedIndices = new ArrayList<>();
                    for (int i = 0; i < allHeaders.size(); i++) {
                        if (selectedColumns.contains(allHeaders.get(i))) {
                            projectedIndices.add(i);
                        }
                    }
                } else {
                    projectedIndices = new ArrayList<>();
                    for (int i = 0; i < allHeaders.size(); i++) {
                        projectedIndices.add(i);
                    }
                }

                // 构建投影后的表头
                List<String> headers = projectedIndices.stream()
                        .map(allHeaders::get)
                        .collect(Collectors.toList());

                if (callback != null) {
                    callback.onHeader(headers);
                }

                while (it.hasNext()) {
                    Row row = it.next();
                    int rowNum = row.getRowNum();
                    Map<String, Object> map = new LinkedHashMap<>();

                    for (int i = 0; i < projectedIndices.size(); i++) {
                        int colIdx = projectedIndices.get(i);
                        String col = allHeaders.get(colIdx);
                        String key = (columnMapping != null)
                                ? columnMapping.getOrDefault(col, col)
                                : col;

                        String cacheKey = rowNum + "," + colIdx;
                        if (mergedCache.containsKey(cacheKey)) {
                            map.put(key, mergedCache.get(cacheKey));
                        } else {
                            Cell cell = row.getCell(colIdx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                            map.put(key, cell != null ? getCellValue(cell) : null);
                        }
                    }

                    result.add(map);
                    if (callback != null) {
                        callback.onBody(map);
                    }
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onComplete(0);
                }
                throw new RuntimeException("读取 Excel 失败: " + file, e);
            }
            // 应用行过滤 + 行数据转换（使用基类统一方法）
            result = applyFilter(result);
            result = applyRowMapping(result);
            if (callback != null) {
                callback.onComplete(result.size());
            }
            return result;
        }

        /**
         * 获取工作表名称列表。
         */
        public List<String> sheetNames() {
            try (FileInputStream fis = new FileInputStream(file);
                 Workbook wb = WorkbookFactory.create(fis)) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    names.add(wb.getSheetName(i));
                }
                return names;
            } catch (Exception e) {
                return List.of();
            }
        }

        @Override
        public Object read() {
            return rows();
        }

        // ==================== 内部方法 ====================

        private Sheet resolveSheet(Workbook wb) {
            if (sheetName != null && !sheetName.isEmpty()) {
                Sheet sheet = wb.getSheet(sheetName);
                if (sheet == null) {
                    throw new IllegalArgumentException("Sheet not found: " + sheetName);
                }
                return sheet;
            }
            Sheet sheet = wb.getSheetAt(sheetIndex);
            if (sheet == null) {
                throw new IllegalArgumentException("Sheet index not found: " + sheetIndex);
            }
            return sheet;
        }

        private java.util.Map<String, Object> buildMergedCache(Sheet sheet) {
            java.util.Map<String, Object> cache = new java.util.HashMap<>();
            for (CellRangeAddress region : sheet.getMergedRegions()) {
                Row firstRow = sheet.getRow(region.getFirstRow());
                if (firstRow == null) {
                    continue;
                }
                Cell firstCell = firstRow.getCell(region.getFirstColumn());
                Object val = firstCell != null ? getCellValue(firstCell) : null;
                for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                    for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                        cache.put(r + "," + c, val);
                    }
                }
            }
            return cache;
        }

        private Object getCellValue(Cell cell) {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        yield cell.getDateCellValue();
                    }
                    yield cell.getNumericCellValue();
                }
                case BOOLEAN -> cell.getBooleanCellValue();
                case FORMULA -> cell.getCellFormula();
                case BLANK -> null;
                default -> null;
            };
        }
    }

    // ==================== Write ====================

    public static class ExcelWriteBuilder extends WriteBuilder {

        // ==================== 全局默认配置 ====================

        /** CellStyle 缓存（按 CellStyleConfig 引用复用） */
        private final java.util.Map<CellStyleConfig, CellStyle> cellStyleCache = new java.util.IdentityHashMap<>();

        /** 是否创建新工作簿 */
        private boolean newWorkbook = true;

        // ==================== Sheet 上下文管理 ====================

        /**
         * 单 Sheet 上下文 — 保存每个工作表的独立配置与数据。
         */
        private static class SheetContext {
            final String name;
            final List<Map<String, Object>> rows = new ArrayList<>();
            List<String> headerColumns;        // null 则自动推断
            boolean withHeader = true;
            CellStyleConfig defaultStyle;
            CellStyleConfig headerStyle;
            java.util.Map<String, ColumnConfig> columnConfigs = new LinkedHashMap<>();
            boolean freezeHeader;
            boolean autoFilter;
            Integer defaultColumnWidth;

            SheetContext(String name) {
                this.name = name;
            }

            /** 解析列名（优先固定表头，否则从首行推断） */
            List<String> resolveHeaders() {
                if (headerColumns != null) {
                    return headerColumns;
                }
                if (!rows.isEmpty()) {
                    return new ArrayList<>(rows.get(0).keySet());
                }
                return List.of();
            }
        }

        /** 所有 Sheet（按添加顺序），key 为 sheet 名 */
        private final LinkedHashMap<String, SheetContext> sheets = new LinkedHashMap<>();

        /** 当前活跃的 Sheet 上下文 */
        private SheetContext activeSheet;

        ExcelWriteBuilder(File file) {
            super(file);
            // 默认活跃 Sheet = "Sheet1"
            this.activeSheet = getOrCreateSheet("Sheet1");
        }

        /** 获取或创建指定名称的 Sheet 上下文 */
        private SheetContext getOrCreateSheet(String name) {
            return sheets.computeIfAbsent(name, SheetContext::new);
        }

        // ==================== Sheet 切换与数据写入 ====================

        /**
         * 切换到指定工作表（不存在则创建）。
         * 后续的 {@link #withHeader}, {@link #style}, {@link #freezeHeader},
         * {@link #write(Object)} 等配置/数据均写入此工作表。
         *
         * @param name 工作表名
         * @return 当前构建器
         */
        public ExcelWriteBuilder writeSheet(String name) {
            this.activeSheet = getOrCreateSheet(name);
            return this;
        }

        /**
         * 写入数据到指定工作表并切换到该工作表。
         *
         * @param name 工作表名
         * @param rows 数据行（List of Map）
         * @return 当前构建器
         */
        public ExcelWriteBuilder writeSheet(String name, List<Map<String, Object>> rows) {
            this.activeSheet = getOrCreateSheet(name);
            this.activeSheet.rows.addAll(rows);
            return this;
        }

        /**
         * 设置当前活跃的工作表名称（同 {@link #writeSheet(String)}）。
         *
         * @param sheetName 工作表名
         * @return 当前构建器
         */
        public ExcelWriteBuilder sheetName(String sheetName) {
            this.activeSheet = getOrCreateSheet(sheetName);
            return this;
        }

        @Override
        public ExcelWriteBuilder write(Object data) {
            if (data instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) m;
                activeSheet.rows.add(row);
            } else if (data instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) m;
                        activeSheet.rows.add(row);
                    } else if (item != null) {
                        activeSheet.rows.add(toMap(item));
                    }
                }
            } else {
                activeSheet.rows.add(toMap(data));
            }
            return this;
        }

        // ==================== 当前 Sheet 配置 ====================

        @Override
        public ExcelWriteBuilder withHeader(boolean withHeader) {
            activeSheet.withHeader = withHeader;
            return this;
        }

        @Override
        public ExcelWriteBuilder withHeaders(List<String> headerColumns) {
            activeSheet.headerColumns = headerColumns;
            return this;
        }

        /**
         * 设置当前 Sheet 的默认单元格样式。
         */
        public ExcelWriteBuilder style(CellStyleConfig style) {
            activeSheet.defaultStyle = style;
            return this;
        }

        /**
         * 设置当前 Sheet 的表头行样式。
         */
        public ExcelWriteBuilder headerStyle(CellStyleConfig style) {
            activeSheet.headerStyle = style;
            return this;
        }

        /**
         * 设置当前 Sheet 指定列的配置。
         */
        public ExcelWriteBuilder columnConfig(String fieldName, ColumnConfig config) {
            activeSheet.columnConfigs.put(fieldName, config);
            return this;
        }

        /**
         * 批量设置当前 Sheet 的列配置。
         */
        public ExcelWriteBuilder columnConfigs(java.util.Map<String, ColumnConfig> configs) {
            if (configs != null) {
                activeSheet.columnConfigs.putAll(configs);
            }
            return this;
        }

        /**
         * 设置当前 Sheet 指定列的宽度。
         */
        public ExcelWriteBuilder columnWidth(String fieldName, int width) {
            ColumnConfig existing = activeSheet.columnConfigs.get(fieldName);
            if (existing != null) {
                activeSheet.columnConfigs.put(fieldName, ColumnConfig.of(fieldName).width(width)
                        .style(existing.getStyle())
                        .displayName(existing.getDisplayName())
                        .headerStyle(existing.getHeaderStyle()));
            } else {
                activeSheet.columnConfigs.put(fieldName, ColumnConfig.of(fieldName).width(width));
            }
            return this;
        }

        /**
         * 设置当前 Sheet 的默认列宽。
         */
        public ExcelWriteBuilder defaultColumnWidth(int width) {
            activeSheet.defaultColumnWidth = width;
            return this;
        }

        /**
         * 冻结当前 Sheet 的表头行。
         */
        public ExcelWriteBuilder freezeHeader() {
            activeSheet.freezeHeader = true;
            return this;
        }

        /**
         * 启用当前 Sheet 的自动过滤。
         */
        public ExcelWriteBuilder withFilter() {
            activeSheet.autoFilter = true;
            return this;
        }

        /**
         * 设置当前 Sheet 是否写入表头行。
         *
         * @param rows 数据行
         * @return 当前构建器
         */
        public ExcelWriteBuilder writeRows(List<Map<String, Object>> rows) {
            activeSheet.rows.addAll(rows);
            return this;
        }

        // ==================== 全局配置 ====================

        /**
         * 设置是否创建新工作簿（默认 true）。
         */
        public ExcelWriteBuilder newWorkbook(boolean newWorkbook) {
            this.newWorkbook = newWorkbook;
            return this;
        }

        @Override
        public ExcelWriteBuilder withCharset(String charset) {
            super.withCharset(charset);
            return this;
        }

        // ==================== 写入执行 ====================

        /**
         * 立即写入单一 Sheet（不入队列）。
         *
         * @param rows 数据行
         */
        public void writeAndFlush(List<Map<String, Object>> rows) {
            callback.onStart();
            callback.onBeginWrite();
            int totalRows = rows.size();
            try (Workbook wb = createWorkbook();
                 FileOutputStream fos = new FileOutputStream(file)) {
                SheetContext ctx = activeSheet;
                ctx.rows.addAll(rows);
                writeSingleSheet(wb, ctx);
                wb.write(fos);
                callback.onComplete(true);
            } catch (Exception e) {
                callback.onComplete(false);
                throw new RuntimeException("写入 Excel 失败: " + file, e);
            }
        }

        @Override
        public void finish() {
            if (sheets.isEmpty()) {
                return;
            }

            callback.onStart();
            callback.onBeginWrite();
            int totalSheets = sheets.size();

            try (Workbook wb = createWorkbook();
                 FileOutputStream fos = new FileOutputStream(file)) {

                int processed = 0;
                for (SheetContext ctx : sheets.values()) {
                    // 跳过无数据的 Sheet
                    if (ctx.rows.isEmpty() && !ctx.withHeader) {
                        continue;
                    }

                    writeSingleSheet(wb, ctx);
                    callback.onProgress(++processed, totalSheets);
                }

                wb.write(fos);
                callback.onComplete(true);
            } catch (Exception e) {
                callback.onComplete(false);
                throw new RuntimeException("写入 Excel 失败: " + file, e);
            }
        }

        // ==================== 内部方法 ====================

        /** 创建工作簿 */
        private Workbook createWorkbook() {
            return new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        }

        /**
         * 将单个 SheetContext 写入 Workbook。
         */
        private void writeSingleSheet(Workbook wb, SheetContext ctx) {
            Sheet sheet = wb.createSheet(ctx.name);
            List<String> headers = ctx.resolveHeaders();
            applySheetSettings(sheet, headers, ctx);

            int rowIdx = 0;

            // 写表头
            if (ctx.withHeader && !headers.isEmpty()) {
                Row headerRow = sheet.createRow(rowIdx++);
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(resolveDisplayName(headers.get(i), ctx));
                    applyHeaderCellStyle(wb, cell, headers.get(i), ctx);
                }
            }

            // 写数据行（带行过滤）
            for (Map<String, Object> map : ctx.rows) {
                if (testRow(map)) {
                    Row row = sheet.createRow(rowIdx++);
                    for (int c = 0; c < headers.size(); c++) {
                        Object val = map.get(headers.get(c));
                        Cell cell = row.createCell(c);
                        setCellValue(cell, val);
                        applyCellStyle(wb, cell, headers.get(c), ctx);
                    }
                }
            }
        }

        /** 解析显示名（优先取 ColumnConfig） */
        private String resolveDisplayName(String fieldName, SheetContext ctx) {
            ColumnConfig cc = ctx.columnConfigs.get(fieldName);
            return cc != null ? cc.getDisplayName() : fieldName;
        }

        /** 应用 Sheet 级别设置 */
        private void applySheetSettings(Sheet sheet, List<String> headers, SheetContext ctx) {
            int headerRowIndex = ctx.withHeader ? 0 : -1;

            if (ctx.freezeHeader && headerRowIndex >= 0) {
                sheet.createFreezePane(0, headerRowIndex + 1);
            }
            if (ctx.autoFilter && headerRowIndex >= 0 && !headers.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(
                        headerRowIndex, headerRowIndex,
                        0, headers.size() - 1));
            }
            if (ctx.defaultColumnWidth != null) {
                sheet.setDefaultColumnWidth(ctx.defaultColumnWidth);
            }
            for (int i = 0; i < headers.size(); i++) {
                ColumnConfig cc = ctx.columnConfigs.get(headers.get(i));
                if (cc != null && cc.getWidth() != null) {
                    sheet.setColumnWidth(i, cc.getWidth() * 256);
                }
            }
            for (int i = 0; i < headers.size(); i++) {
                ColumnConfig cc = ctx.columnConfigs.get(headers.get(i));
                if (cc != null && cc.isHidden()) {
                    sheet.setColumnHidden(i, true);
                }
            }
        }

        /** 应用表头单元格样式 */
        private void applyHeaderCellStyle(Workbook wb, Cell cell, String fieldName, SheetContext ctx) {
            ColumnConfig cc = ctx.columnConfigs.get(fieldName);
            CellStyleConfig styleConfig = null;
            if (cc != null && cc.getHeaderStyle() != null) {
                styleConfig = cc.getHeaderStyle();
            } else if (ctx.headerStyle != null) {
                styleConfig = ctx.headerStyle;
            }
            if (styleConfig != null) {
                CellStyle style = cellStyleCache.computeIfAbsent(styleConfig, k -> k.applyTo(wb));
                cell.setCellStyle(style);
                if (styleConfig.getRowHeight() != null) {
                    cell.getRow().setHeightInPoints(styleConfig.getRowHeight());
                }
            }
        }

        /** 应用数据单元格样式 */
        private void applyCellStyle(Workbook wb, Cell cell, String fieldName, SheetContext ctx) {
            ColumnConfig cc = ctx.columnConfigs.get(fieldName);
            CellStyleConfig styleConfig = null;
            if (cc != null && cc.getStyle() != null) {
                styleConfig = cc.getStyle();
            } else if (ctx.defaultStyle != null) {
                styleConfig = ctx.defaultStyle;
            }
            if (styleConfig != null) {
                CellStyle style = cellStyleCache.computeIfAbsent(styleConfig, k -> k.applyTo(wb));
                cell.setCellStyle(style);
                if (styleConfig.getRowHeight() != null) {
                    cell.getRow().setHeightInPoints(styleConfig.getRowHeight());
                }
            }
        }

        /** 设置单元格值 */
        private void setCellValue(Cell cell, Object val) {
            if (val == null) {
                cell.setBlank();
            } else if (val instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else if (val instanceof Boolean b) {
                cell.setCellValue(b);
            } else if (val instanceof Date d) {
                cell.setCellValue(d);
            } else {
                cell.setCellValue(String.valueOf(val));
            }
        }
    }
}
