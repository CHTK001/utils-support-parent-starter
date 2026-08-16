package com.chua.spring.support.convert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;

import java.time.format.DateTimeFormatter;

/**
 * 日期时间转换器配置类
 * <p>
 * 自动注册以下转换器到 Spring 的 ConversionService：
 * <ul>
 *   <li>String ↔ LocalDate</li>
 *   <li>String ↔ LocalDateTime</li>
 *   <li>String ↔ LocalTime</li>
 *   <li>Date ↔ LocalDate</li>
 *   <li>Date ↔ LocalDateTime</li>
 * </ul>
 * <p>
 * 默认使用 ISO 格式，支持通过构造参数自定义日期时间格式。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Configuration
public class DateConvertConfiguration {

    /**
     * 日期格式，默认使用 ISO 本地日期格式（yyyy-MM-dd）
     */
    private final String datePattern;

    /**
     * 时间格式，默认使用 ISO 本地时间格式（HH:mm:ss）
     */
    private final String timePattern;

    /**
     * 日期时间格式，默认使用 ISO 本地日期时间格式（yyyy-MM-ddTHH:mm:ss）
     */
    private final String dateTimePattern;

    /**
     * 使用默认格式构造配置类
     */
    public DateConvertConfiguration() {
        this(null, null, null);
    }

    /**
     * 使用自定义格式构造配置类
     *
     * @param datePattern      日期格式，例如 "yyyy-MM-dd"
     * @param timePattern      时间格式，例如 "HH:mm:ss"
     * @param dateTimePattern  日期时间格式，例如 "yyyy-MM-dd HH:mm:ss"
     */
    public DateConvertConfiguration(String datePattern, String timePattern, String dateTimePattern) {
        this.datePattern = org.springframework.util.StringUtils.hasText(datePattern) ? datePattern : null;
        this.timePattern = org.springframework.util.StringUtils.hasText(timePattern) ? timePattern : null;
        this.dateTimePattern = org.springframework.util.StringUtils.hasText(dateTimePattern) ? dateTimePattern : null;
    }

    /**
     * 注册日期时间转换器到 ConversionService
     *
     * @return ConversionService 实例
     */
    @Bean
    public ConversionService conversionService() {
        DefaultConversionService conversionService = new DefaultConversionService();

        // 注册 LocalDate 转换器
        conversionService.addConverter(new LocalDateToStringConverter(datePattern));
        conversionService.addConverter(new StringToLocalDateConverter(datePattern));

        // 注册 LocalDateTime 转换器
        conversionService.addConverter(new LocalDateTimeToStringConverter(dateTimePattern));
        conversionService.addConverter(new StringToLocalDateTimeConverter(dateTimePattern));

        // 注册 LocalTime 转换器
        conversionService.addConverter(new LocalTimeToStringConverter(timePattern));
        conversionService.addConverter(new StringToLocalTimeConverter(timePattern));

        // 注册 Date 与 LocalDate 转换器
        conversionService.addConverter(new DateToLocalDateConverter());
        conversionService.addConverter(new LocalDateToDateConverter());

        // 注册 Date 与 LocalDateTime 转换器
        conversionService.addConverter(new DateToLocalDateTimeConverter());
        conversionService.addConverter(new LocalDateTimeToDateConverter());

        return conversionService;
    }
}
