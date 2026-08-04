package com.chua.common.support.constant;

import java.text.DecimalFormat;
import org.jspecify.annotations.NullUnmarked;

/**
 * 格式常量接口，集中定义项目中使用的各类格式化模式。
 *
 * <p>该接口包含以下类别的格式常量：
 * <ul>
 *   <li><b>日期时间格式</b> — 常用日期、时间、日期时间组合模式</li>
 *   <li><b>数字格式</b> — 整数、小数、百分比、金额等模式</li>
 *   <li><b>HTTP 日期格式</b> — RFC 1123 等 HTTP 协议中使用的日期格式</li>
 *   <li><b>预编译格式化对象</b> — DecimalFormat 实例</li>
 *   <li><b>国际化消息基名</b> — i18n 资源文件路径</li>
 * </ul>
 *
 * <p>这些常量可用于 {@link java.text.SimpleDateFormat}、{@link java.time.format.DateTimeFormatter}、
 * {@link java.text.DecimalFormat} 等格式化工具类，避免魔法字符串散落在代码中。</p>
 *
 * @author CH
 * @since 2024-01-01
 */
@NullUnmarked
public interface FormatConstant {

    // ========================== 日期格式 ==========================

    /**
     * 日期格式：{@code yyyy-MM-dd}，示例：2024-01-15。
     */
    String yyyy_MM_dd = "yyyy-MM-dd";
    /**
     * 日期格式（大写别名，兼容旧版引用）。
     */
    String YYYY_MM_DD = yyyy_MM_dd;
    /**
     * 日期格式：{@code yyyy/MM/dd}，示例：2024/01/15。
     */
    String yyyy_MM_dd_SLASH = "yyyy/MM/dd";
    /**
     * 日期格式：{@code yyyyMMdd}，示例：20240115。
     */
    String yyyyMMdd = "yyyyMMdd";
    /**
     * 日期格式：{@code yyyy年MM月dd日}，示例：2024年01月15日。
     */
    String yyyy_MM_dd_CN = "yyyy年MM月dd日";
    /**
     * 日期格式：{@code yyMMdd}，示例：240115。
     */
    String yyMMdd = "yyMMdd";

    // ========================== 时间格式 ==========================

    /**
     * 时间格式：{@code HH:mm:ss}，示例：14:30:00。
     */
    String HH_mm_ss = "HH:mm:ss";
    /**
     * 时间格式（大写别名，兼容旧版引用）。
     */
    String HH_MM_SS = HH_mm_ss;
    /**
     * 时间格式：{@code HH:mm}，示例：14:30。
     */
    String HH_mm = "HH:mm";
    /**
     * 时间格式：{@code HHmmss}，示例：143000。
     */
    String HHmmss = "HHmmss";

    // ========================== 日期时间格式 ==========================

    /**
     * 日期时间格式：{@code yyyy-MM-dd HH:mm:ss}，示例：2024-01-15 14:30:00。
     */
    String yyyy_MM_dd_HH_mm_ss = "yyyy-MM-dd HH:mm:ss";
    /**
     * 日期时间格式（大写别名，兼容旧版引用）。
     */
    String YYYY_MM_DD_HH_MM_SS = yyyy_MM_dd_HH_mm_ss;
    /**
     * 日期时间格式（毫秒，逗号分隔）：{@code yyyy-MM-dd HH:mm:ss,SSS}，示例：2024-01-15 14:30:00,123。
     */
    String yyyy_MM_dd_HH_mm_ss_SSS_COMMA = "yyyy-MM-dd HH:mm:ss,SSS";
    /**
     * 日期时间格式（毫秒，点分隔）：{@code yyyy-MM-dd HH:mm:ss.SSS}，示例：2024-01-15 14:30:00.123。
     */
    String yyyy_MM_dd_HH_mm_ss_SSS_DOT = "yyyy-MM-dd HH:mm:ss.SSS";
    /**
     * 日期时间格式（无分隔符）：{@code yyyyMMddHHmmss}，示例：20240115143000。
     */
    String yyyyMMddHHmmss = "yyyyMMddHHmmss";
    /**
     * 日期时间格式（含毫秒无分隔符）：{@code yyyyMMddHHmmssSSS}，示例：20240115143000123。
     */
    String yyyyMMddHHmmssSSS = "yyyyMMddHHmmssSSS";

    // ========================== HTTP / 协议日期格式 ==========================

    /**
     * HTTP 日期格式（RFC 1123）：{@code EEE, dd MMM yyyy HH:mm:ss zzz}，示例：Mon, 15 Jan 2024 14:30:00 GMT。
     */
    String RFC_1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";
    /**
     * Java 默认日期格式（CTT）：{@code EEE MMM dd HH:mm:ss z yyyy}，示例：Mon Jan 15 14:30:00 CST 2024。
     */
    String CTT_DATE = "EEE MMM dd HH:mm:ss z yyyy";

    // ========================== 数字格式 ==========================

    /**
     * 整数格式（至少 1 位）：{@code #}。
     */
    String INTEGER = "#";
    /**
     * 整数格式（至少 1 位，补零至 2 位）：{@code 00}。
     */
    String INTEGER_PADDING_2 = "00";
    /**
     * 整数格式（至少 1 位，补零至 3 位）：{@code 000}。
     */
    String INTEGER_PADDING_3 = "000";
    /**
     * 整数格式（至少 1 位，补零至 4 位）：{@code 0000}。
     */
    String INTEGER_PADDING_4 = "0000";

    /**
     * 小数格式（1 位整数 + 2 位小数）：{@code #.##}。
     */
    String DECIMAL_1_2 = "#.##";
    /**
     * 小数格式（1 位整数 + 3 位小数）：{@code #.###}。
     */
    String DECIMAL_1_3 = "#.###";
    /**
     * 小数格式（至少 1 位整数 + 3 位小数，不足补零）：{@code ##0.000}。
     */
    String DECIMAL_AT_LEAST_1_3 = "##0.000";
    /**
     * 小数格式（至少 1 位整数 + 3 位小数，不足补零）：{@code ###.000}。
     */
    String DECIMAL_AT_LEAST_0_3 = "###.000";

    /**
     * 百分比格式：{@code #.##%}。
     */
    String PERCENT = "#.##%";

    /**
     * 金额格式（千分位 + 2 位小数）：{@code #,##0.00}。
     */
    String MONEY = "#,##0.00";

    // ========================== 国际化 ==========================

    /**
     * 国际化资源文件基名 {@code language/message}。
     */
    String RESOURCE_MESSAGE = "language/message";

    // ========================== 预编译格式化对象 ==========================

    /**
     * DecimalFormat 实例（格式：{@value DECIMAL_AT_LEAST_1_3}）。
     */
    DecimalFormat DECIMAL_FORMAT = new DecimalFormat(DECIMAL_AT_LEAST_1_3);

}
