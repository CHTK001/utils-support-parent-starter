package com.chua.common.support.lang.date;

/**
 * 日期时间解析 SPI 契约。
 *
 * <p>所有日期时间解析实现都需要实现此接口并通过 SPI 机制注册。
 * {@link DateTime#parse(String)} 会遍历全部实现，
 * 从头处理、有一个成功即解析成功。</p>
 *
 * <p>实现类通过 {@code @Spi("名称")} + {@code @AutoSpi} 注册，例如：</p>
 * <pre>{@code
 * @Spi("time-nlp")
 * @AutoSpi(value = "com.chua.common.support.lang.date.DateTimeParser")
 * public class TimeNlpDateTimeParser implements DateTimeParser {
 *     @Override
 *     public LocalDateTime parse(String timeStr) {
 *         return ...;
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DateTimeParser {

    /**
     * 解析时间字符串为日期时间。
     *
     * <p>无法解析时返回 {@code null}（而不是抛异常），
     * 以便调用方继续尝试下一个 SPI 实现。有一个成功即解析成功。</p>
     *
     * @param timeStr 时间字符串
     * @return 解析结果；无法解析时返回 {@code null}
     */
    java.time.LocalDateTime parse(String timeStr);
}
