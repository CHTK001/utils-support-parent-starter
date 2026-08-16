package com.chua.common.support.datasearch.holiday.spi;

import com.chua.common.support.datasearch.holiday.model.HolidayInfo;

import java.time.LocalDate;
import java.util.List;

/**
 * 法定节假日数据提供者 SPI 接口。
 *
 * <p>封装中国法定节假日 / 调休补班的判断与查询能力。
 * 各实现通过 SPI 机制注册，例如基于公开节假日 JSON 的在线数据源。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface HolidayProvider {

    /**
     * 获取数据源名称
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 判断指定日期是否为法定节假日（放假）
     *
     * @param date 日期
     * @return 是否法定节假日
     */
    boolean isHoliday(LocalDate date);

    /**
     * 判断指定日期是否需要上班。
     *
     * <p>包含两种情形：调休补班日（type=work）与普通工作日（周一至周五且非节假日）。
     *
     * @param date 日期
     * @return 是否上班日
     */
    boolean isWorkday(LocalDate date);

    /**
     * 获取指定日期的节假日信息（非节假日返回 null）
     *
     * @param date 日期
     * @return 节假日信息，非节假日为 null
     */
    HolidayInfo getHoliday(LocalDate date);

    /**
     * 获取某一年的全部节假日安排
     *
     * @param year 年份，如 2026
     * @return 节假日信息列表（含法定节假日与调休补班）
     */
    List<HolidayInfo> getHolidays(int year);
}
