package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * String 转 LocalTime 转换器
 * 将字符串解析为 {@link LocalTime} 对象
 *
 * @author CH
 * @since 2026/7/19
 */
@Slf4j
public class StringToLocalTimeConverter implements Converter<String, LocalTime> {

    /**
     * 默认时间格式化器，使用 ISO 本地时间格式
     */
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ISO_LOCAL_TIME;

    /**
     * 自定义时间格式化器，如果用户指定了格式则使用该格式
     */
    private final DateTimeFormatter formatter;

    /**
     * 使用默认格式构造转换器
     */
    public StringToLocalTimeConverter() {
        this(null);
    }

    /**
     * 使用自定义格式构造转换器
     *
     * @param pattern 时间格式模式，例如 "HH:mm:ss"
     */
    public StringToLocalTimeConverter(String pattern) {
        if (org.springframework.util.StringUtils.hasText(pattern)) {
            this.formatter = DateTimeFormatter.ofPattern(pattern);
        } else {
            this.formatter = DEFAULT_FORMATTER;
        }
    }

    /**
     * 将字符串转换为 LocalTime
     *
     * @param source 源字符串
     * @return LocalTime 对象，如果 source 为 blank 则返回 null
     */
    @Override
    public LocalTime convert(String source) {
        if (!org.springframework.util.StringUtils.hasText(source)) {
            return null;
        }
        try {
            return LocalTime.parse(source, formatter);
        } catch (DateTimeParseException e) {
            log.warn("无法将字符串 [{}] 解析为 LocalTime，使用格式: {}", source, formatter);
            throw new IllegalArgumentException("无法将字符串解析为 LocalTime: " + source, e);
        }
    }
}
