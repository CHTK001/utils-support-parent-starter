package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * String 转 LocalDate 转换器
 * 将字符串解析为 {@link LocalDate} 对象
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class StringToLocalDateConverter implements Converter<String, LocalDate> {

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
    public StringToLocalDateConverter() {
        this(null);
    }

    /**
     * 使用自定义格式构造转换器
     *
     * @param pattern 日期时间格式模式，例如 "yyyy-MM-dd"
     */
    public StringToLocalDateConverter(String pattern) {
        if (org.springframework.util.StringUtils.hasText(pattern)) {
            this.formatter = DateTimeFormatter.ofPattern(pattern);
        } else {
            this.formatter = DEFAULT_FORMATTER;
        }
    }

    /**
     * 将字符串转换为 LocalDate
     *
     * @param source 源字符串
     * @return LocalDate 对象，如果 source 为 blank 则返回 null
     */
    @Override
    public LocalDate convert(String source) {
        if (!org.springframework.util.StringUtils.hasText(source)) {
            return null;
        }
        try {
            return LocalDate.parse(source, formatter);
        } catch (DateTimeParseException e) {
            log.warn("[spring-convert] 无法将字符串 [{}] 解析为 LocalDate，使用格式: {}", source, formatter);
            throw new IllegalArgumentException("无法将字符串解析为 LocalDate: " + source, e);
        }
    }
}
