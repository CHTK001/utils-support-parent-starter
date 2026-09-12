package com.chua.aspose.support.converter;

import com.aspose.cells.SaveFormat;
import com.aspose.cells.Workbook;
import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Aspose.Cells 电子表格格式转换器。
 *
 * <p>支持 xls/xlsx/xlsm/xlsb 的相互转换，以及转换为 pdf/csv/html/json/markdown 等格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("aspose-excel")
public class AsposeExcelFileConvertSystem implements FileConvertSystem {

    /**
     * 支持的源文件格式列表
     */
    private static final List<String> SOURCES = List.of("xls", "xlsx", "xlsm", "xlsb", "csv");

    /**
     * 支持的目标文件格式列表
     */
    private static final List<String> TARGETS = List.of("pdf", "xls", "xlsx", "xlsm", "xlsb", "csv", "html", "htm", "json", "markdown", "md", "xml", "tsv");

    /**
      * 格式与 Aspose.Cells 保存格式化 常量映射表
     */
    private static final Map<String, Integer> FORMAT_MAP = Map.ofEntries(
        Map.entry("pdf", SaveFormat.PDF), Map.entry("xls", SaveFormat.EXCEL_97_TO_2003),
        Map.entry("xlsx", SaveFormat.XLSX), Map.entry("xlsm", SaveFormat.XLSM),
        Map.entry("xlsb", SaveFormat.XLSB), Map.entry("csv", SaveFormat.CSV),
        Map.entry("html", SaveFormat.HTML), Map.entry("htm", SaveFormat.HTML),
        Map.entry("json", SaveFormat.JSON), Map.entry("markdown", SaveFormat.MARKDOWN),
        Map.entry("md", SaveFormat.MARKDOWN), Map.entry("xml", SaveFormat.XML),
        Map.entry("tsv", SaveFormat.TSV)
    );

    @Override
    /** 是否支持 */
    public boolean isSupported(String source, String target) {
        if (!SOURCES.contains(source)) {
            return false;
        }
        return TARGETS.contains(target);
    }

    @Override
    /** 转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        try (InputStream in = source.isInputStream() ? source.getInputStream() : new FileInputStream(source.getPath());
             OutputStream out = target.isOutputStream() ? target.getOutputStream() : new FileOutputStream(target.getPath())) {
            Workbook wb = new Workbook(in);
            String ext = target.isPath() ? target.getPath().replaceAll(".*\\.", "") : target.getType();
            Integer fmt = FORMAT_MAP.get(ext);
            if (fmt == null) {
                throw new UnsupportedOperationException("不支持的目标格式: " + ext);
            }
            wb.save(out, fmt);
        } catch (Exception e) {
            throw new RuntimeException("Aspose Excel 转换失败", e);
        }
    }
}
