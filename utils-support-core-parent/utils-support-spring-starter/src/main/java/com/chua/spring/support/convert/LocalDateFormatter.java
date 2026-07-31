package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.format.Formatter;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * LocalDate 格式化器
 * 用于 Spring MVC 参数绑定，支持 {@link LocalDate} 类型的自动格式化与解析
 *
 * @author CH
 * @since 2026/7/19
 */
@Slf4j
public class LocalDateFormatter implements Formatter<LocalDate> {

    /**
     * 默认日期格式化器，使用 ISO 本地日期格式
     */
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * 自定义日期格式化器
     */
    private final DateTimeFormatter formatter;

    /**
     * 使用默认格式构造格式化器
     */
    public LocalDateFormatter() {
        this(null);
    }

    /**
     * 使用自定义格式构造格式化器
     *
     * @param pattern 日期格式模式，例如 "yyyy-MM-dd"
     */
    public LocalDateFormatter(String pattern) {
        if (org.springframework.util.StringUtils.hasText(pattern)) {
            this.formatter = DateTimeFormatter.ofPattern(pattern);
        } else {
            this.formatter = DEFAULT_FORMATTER;
        }
    }

    /**
     * 将字符串解析为 LocalDate
     *
     * @param source 源字符串
     * @param locale  locale
     * @return LocalDate 对象
     */
    @Override
    public LocalDate parse(String source, Locale locale) {
        if (!org.springframework.util.StringUtils.hasText(source)) {
            return null;
        }
        try {
            return LocalDate.parse(source, formatter);
        } catch (Exception e) {
            log.warn("无法将字符串 [{}] 解析为 LocalDate，使用格式: {}", source, formatter);
            throw new IllegalArgumentException("无法将字符串解析为 LocalDate: " + source, e);
        }
    }

    /**
     * 将 LocalDate 格式化为字符串
     *
     * @param source 源 LocalDate 对象
     * @param locale  locale
     * @return 格式化后的日期字符串
     */
    @Override
    public String print(LocalDate source, Locale locale) {
        if (source == null) {
            return null;
        }
        return source.format(formatter);
    }
}
