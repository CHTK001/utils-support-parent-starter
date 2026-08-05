package com.chua.common.support.lang.date.unit;

import lombok.Getter;

import java.time.temporal.ChronoUnit;


/**
 * 日期时间单位枚举
 *
 * @author CH
 */
@Getter
public enum DateUnit  {
    /**
     * 毫秒
     */
    MS(1),
    /**
     * 秒
     */
    SECOND(1000),
    /**
     * 分钟
     */
    MINUTE(SECOND.getMillis() * 60),
    /**
     * 小时
     */
    HOUR(MINUTE.getMillis() * 60),
    /**
     * 天
     */
    DAY(HOUR.getMillis() * 24),
    /**
     * 周
     */
    WEEK(DAY.getMillis() * 7),
    /**
     * 月
     */
    MONTH(DAY.getMillis() * 30),
    /**
     * 年
     */
    YEAR(MONTH.getMillis() * 12),
    /**
     * 自定义(默认1天)
     */
    CUSTOM(DAY.getMillis());


    /**
     * -- GETTER --
     *  获取该时间单位对应的毫秒数
     */
    private final long millis;

    DateUnit(long millis) {
        this.millis = millis;
    }

    /**
     * 将当前 DateUnit 转换为 {@link ChronoUnit}
     *
     * @return {@link ChronoUnit}，如果不支持则返回 null
     * @since 5.4.5
     */
    public ChronoUnit toChronosUnit() {
        return DateUnit.toChronosUnit(this);
    }


    /**
     * 将 {@link ChronoUnit} 转换为 DateUnit
     *
     * @param unit {@link ChronoUnit}
     * @return DateUnit，如果不支持则返回 null
     * @since 5.4.5
     */
    public static DateUnit of(ChronoUnit unit) {
        switch (unit) {
            case MICROS:
                return DateUnit.MS;
            case SECONDS:
                return DateUnit.SECOND;
            case MINUTES:
                return DateUnit.MINUTE;
            case HOURS:
                return DateUnit.HOUR;
            case DAYS:
                return DateUnit.DAY;
            case WEEKS:
                return DateUnit.WEEK;
            case MONTHS:
                return DateUnit.MONTH;
            case YEARS:
                return DateUnit.YEAR;
            default:
        }
        return null;
    }

    /**
     * 将指定的 DateUnit 转换为 {@link ChronoUnit}
     *
     * @param unit DateUnit
     * @return {@link ChronoUnit}，如果不支持则返回 null
     * @since 5.4.5
     */
    public static ChronoUnit toChronosUnit(DateUnit unit) {
        switch (unit) {
            case MS:
                return ChronoUnit.MICROS;
            case SECOND:
                return ChronoUnit.SECONDS;
            case MINUTE:
                return ChronoUnit.MINUTES;
            case HOUR:
                return ChronoUnit.HOURS;
            case DAY:
                return ChronoUnit.DAYS;
            case WEEK:
                return ChronoUnit.WEEKS;
            case MONTH:
                return ChronoUnit.MONTHS;
            case YEAR:
                return ChronoUnit.YEARS;
            default:
        }
        return null;
    }


}
