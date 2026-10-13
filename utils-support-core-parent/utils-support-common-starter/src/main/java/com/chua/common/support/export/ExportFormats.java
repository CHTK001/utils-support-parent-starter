package com.chua.common.support.export;

import java.util.Map;

/**
 * 导出格式常量与推断工具。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ExportFormats {

    /**
     * Excel 2007+（{@code .xlsx}）。
     */
    public static final String XLSX = "xlsx";

    /**
     * Excel 97-2003（{@code .xls}）。
     */
    public static final String XLS = "xls";

    /**
     * Word 2007+（{@code .docx}）。
     */
    public static final String DOCX = "docx";

    /**
     * PDF（{@code .pdf}）。
     */
    public static final String PDF = "pdf";

    /**
     * CSV（{@code .csv}）。
     */
    public static final String CSV = "csv";

    /**
     * 旧版二进制格式 → 现代格式的替代建议。
     *
     * <p>Apache POI 只读写 OOXML（{@code .xlsx} / {@code .docx}），无法直接生成
     * Word 97-2003 的 {@code .doc} 与 Excel 97-2003 的 {@code .xls}。命中此表时
     * 异常信息会给出替代格式。</p>
     */
    private static final Map<String, String> LEGACY = Map.of(
            "doc", DOCX,
            "dot", DOCX,
            "xls", XLSX);

    private ExportFormats() {
    }

    /**
     * 取旧版二进制格式的替代格式。
     *
     * @param format 格式标识
     * @return 替代格式；不是旧版格式时返回 {@code null}
     */
    public static String replacementOf(String format) {
        return LEGACY.get(normalize(format));
    }

    /**
     * 由文件名推断格式（不含点号）。
     *
     * @param fileName 文件名或路径
     * @return 格式标识，推断失败返回 {@code null}
     */
    public static String of(String fileName) {
        if (fileName == null) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(idx + 1).trim().toLowerCase();
        return switch (ext) {
            case "xlsx", "xlsm" -> XLSX;
            case "xls" -> XLS;
            case "docx" -> DOCX;
            case "pdf" -> PDF;
            case "csv" -> CSV;
            default -> ext;
        };
    }

    /**
     * 规范化格式标识：去点号、去空白、转小写。
     *
     * @param format 格式标识
     * @return 规范化结果
     */
    public static String normalize(String format) {
        if (format == null) {
            return null;
        }
        String value = format.trim().toLowerCase();
        return value.startsWith(".") ? value.substring(1) : value;
    }
}