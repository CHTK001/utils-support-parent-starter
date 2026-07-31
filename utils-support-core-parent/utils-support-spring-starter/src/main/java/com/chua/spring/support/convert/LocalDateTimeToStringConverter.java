package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * LocalDateTime 转 String 转换器
 * 将 {@link LocalDateTime} 转换为 ISO 格式字符串（yyyy-MM-ddTHH:mm:ss）
 *
 * @author CH
 * @since 2026/7/19
 */
@Slf4j
public class LocalDateTimeToStringConverter implements Converter<LocalDateTime, String> {

    /**
     * 默认日期时间格式化器，使用 ISO 本地日期时间格式
     */
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 自定义日期时间格式化器，如果用户指定了格式则使用该格式
     */
    private final DateTimeFormatter formatter;

    /**
     * 使用默认格式构造转换器
     */
    public LocalDateTimeToStringConverter() {
        this(null);
    }

    /**
     * 使用自定义格式构造转换器
     *
     * @param pattern 日期时间格式模式，例如 "yyyy-MM-dd HH:mm:ss"
     */
    public LocalDateTimeToStringConverter(String pattern) {
        if (org.springframework.util.StringUtils.hasText(pattern)) {
            this.formatter = DateTimeFormatter.ofPattern(pattern);
        } else {
            this.formatter = DEFAULT_FORMATTER;
        }
    }

    /**
     * 将 LocalDateTime 转换为字符串
     *
     * @param source 源 LocalDateTime 对象
     * @return 格式化后的日期时间字符串，如果 source 为 null 则返回 null
     */
    @Override
    public String convert(LocalDateTime source) {
        if (source == null) {
            return null;
        }
        return source.format(formatter);
    }
}
