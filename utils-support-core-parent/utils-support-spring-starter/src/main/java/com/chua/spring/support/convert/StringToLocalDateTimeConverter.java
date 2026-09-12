package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
   * 字符串 转 本地日期时间 转换器
 * 将字符串解析为 {@link LocalDateTime} 对象
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class StringToLocalDateTimeConverter implements Converter<String, LocalDateTime> {

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
    public StringToLocalDateTimeConverter() {
        this(null);
    }

    /**
     * 使用自定义格式构造转换器
     *
     * @param pattern 日期时间格式模式，例如 "yyyy-MM-dd HH:mm:ss"
     */
    public StringToLocalDateTimeConverter(String pattern) {
        if (org.springframework.util.StringUtils.hasText(pattern)) {
            this.formatter = DateTimeFormatter.ofPattern(pattern);
        } else {
            this.formatter = DEFAULT_FORMATTER;
        }
    }

    /**
      * 将字符串转换为 本地日期时间
     *
     * @param source 源字符串
     * @return LocalDateTime 对象，如果 源 为 blank 则返回 空
     */
    @Override
    public LocalDateTime convert(String source) {
        if (!org.springframework.util.StringUtils.hasText(source)) {
            return null;
        }
        try {
            return LocalDateTime.parse(source, formatter);
        } catch (DateTimeParseException e) {
            log.warn("[spring-convert] 无法将字符串 [{}] 解析为 LocalDateTime，使用格式: {}", source, formatter);
            throw new IllegalArgumentException("无法将字符串解析为 LocalDateTime: " + source, e);
        }
    }
}
