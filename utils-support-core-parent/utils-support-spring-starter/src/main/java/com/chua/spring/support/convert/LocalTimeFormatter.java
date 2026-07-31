package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.format.Formatter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * LocalTime 格式化器
 * 用于 Spring MVC 参数绑定，支持 {@link LocalTime} 类型的自动格式化与解析
 *
 * @author CH
 * @since 2026/7/19
 */
@Slf4j
public class LocalTimeFormatter implements Formatter<LocalTime> {

    /**
     * 默认时间格式化器，使用 ISO 本地时间格式
     */
    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ISO_LOCAL_TIME;

    /**
     * 自定义时间格式化器
     */
    private final DateTimeFormatter formatter;

    /**
     * 使用默认格式构造格式化器
     */
    public LocalTimeFormatter() {
        this(null);
    }

    /**
     * 使用自定义格式构造格式化器
     *
     * @param pattern 时间格式模式，例如 "HH:mm:ss"
     */
    public LocalTimeFormatter(String pattern) {
        if (org.springframework.util.StringUtils.hasText(pattern)) {
            this.formatter = DateTimeFormatter.ofPattern(pattern);
        } else {
            this.formatter = DEFAULT_FORMATTER;
        }
    }

    /**
     * 将字符串解析为 LocalTime
     *
     * @param source 源字符串
     * @param locale  locale
     * @return LocalTime 对象
     */
    @Override
    public LocalTime parse(String source, Locale locale) {
        if (!org.springframework.util.StringUtils.hasText(source)) {
            return null;
        }
        try {
            return LocalTime.parse(source, formatter);
        } catch (Exception e) {
            log.warn("无法将字符串 [{}] 解析为 LocalTime，使用格式: {}", source, formatter);
            throw new IllegalArgumentException("无法将字符串解析为 LocalTime: " + source, e);
        }
    }

    /**
     * 将 LocalTime 格式化为字符串
     *
     * @param source 源 LocalTime 对象
     * @param locale  locale
     * @return 格式化后的时间字符串
     */
    @Override
    public String print(LocalTime source, Locale locale) {
        if (source == null) {
            return null;
        }
        return source.format(formatter);
    }
}
