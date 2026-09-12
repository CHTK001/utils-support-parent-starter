package com.chua.spring.support.convert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 日期时间格式化器配置类
 * <p>
 * 自动注册以下格式化器到 Spring MVC：
 * <ul>
 *   <li>LocalDateFormatter</li>
 *   <li>LocalDateTimeFormatter</li>
 *   <li>LocalTimeFormatter</li>
 * </ul>
 * <p>
 * 默认使用 ISO 格式，支持通过构造参数自定义日期时间格式。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Configuration
public class FormatterConfiguration implements WebMvcConfigurer {

    /**
     * 日期格式，默认使用 ISO 本地日期格式（yyyy-MM-dd）
     */
    private final String datePattern;

    /**
     * 时间格式，默认使用 ISO 本地时间格式（HH:mm:ss）
     */
    private final String timePattern;

    /**
      * 日期时间格式，默认使用 ISO 本地日期时间格式（yyyy-MM-ddthh:mm:ss）
     */
    private final String dateTimePattern;

    /**
     * 使用默认格式构造配置类
     */
    public FormatterConfiguration() {
        this(null, null, null);
    }

    /**
     * 使用自定义格式构造配置类
     *
     * @param datePattern      日期格式，例如 "yyyy-MM-dd"
     * @param timePattern      时间格式，例如 "HH:mm:ss"
     * @param dateTimePattern  日期时间格式，例如 "yyyy-MM-dd HH:mm:ss"
     */
    public FormatterConfiguration(String datePattern, String timePattern, String dateTimePattern) {
        this.datePattern = org.springframework.util.StringUtils.hasText(datePattern) ? datePattern : null;
        this.timePattern = org.springframework.util.StringUtils.hasText(timePattern) ? timePattern : null;
        this.dateTimePattern = org.springframework.util.StringUtils.hasText(dateTimePattern) ? dateTimePattern : null;
    }

    /**
     * 注册日期时间格式化器
     *
     * @param registry formatterregistry
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addFormatter(new LocalDateFormatter(datePattern));
        registry.addFormatter(new LocalDateTimeFormatter(dateTimePattern));
        registry.addFormatter(new LocalTimeFormatter(timePattern));
    }
}
