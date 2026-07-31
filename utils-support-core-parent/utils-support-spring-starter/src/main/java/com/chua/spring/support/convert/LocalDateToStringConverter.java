package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * LocalDate 转 String 转换器
 * 将 {@link LocalDate} 转换为 ISO 格式字符串（yyyy-MM-dd）
 *
 * @author CH
 * @since 2026/7/19
 */
@Slf4j
public class LocalDateToStringConverter implements Converter<LocalDate, String> {

    /**
     * 默认日期格式化器，使用 ISO 本地日期格式
     */
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * 自定义日期格式化器，如果用户指定了格式则使用该格式
     */
    private final DateTimeFormatter formatter;

    /**
     * 使用默认格式构造转换器
     */
    public LocalDateToStringConverter() {
        this(null);
    }

    /**
     * 使用自定义格式构造转换器
     *
     * @param pattern 日期时间格式模式，例如 "yyyy-MM-dd"
     */
    public LocalDateToStringConverter(String pattern) {
        if (org.springframework.util.StringUtils.hasText(pattern)) {
            this.formatter = DateTimeFormatter.ofPattern(pattern);
        } else {
            this.formatter = DEFAULT_FORMATTER;
        }
    }

    /**
     * 将 LocalDate 转换为字符串
     *
     * @param source 源 LocalDate 对象
     * @return 格式化后的日期字符串，如果 source 为 null 则返回 null
     */
    @Override
    public String convert(LocalDate source) {
        if (source == null) {
            return null;
        }
        return source.format(formatter);
    }
}
