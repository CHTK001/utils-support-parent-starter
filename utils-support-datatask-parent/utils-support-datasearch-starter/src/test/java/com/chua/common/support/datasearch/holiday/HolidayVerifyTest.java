package com.chua.common.support.datasearch.holiday;

import com.chua.common.support.datasearch.holiday.model.HolidayInfo;
import com.chua.common.support.datasearch.holiday.spi.HolidayProvider;
import com.chua.common.support.datasearch.holiday.spi.impl.OnlineHolidayProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.time.LocalDate;
import java.util.List;

/**
 * 节假日真实数据验证:SPI 发现 + holiday-cn 在线数据 + 2026 内置兜底。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HolidayVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        HolidayProvider provider = ServiceProvider.of(HolidayProvider.class)
                .getExtension("online");
        System.out.println("SPI 发现 online: " + (provider != null));
        if (provider == null) {
            provider = new OnlineHolidayProvider();
        }

        // 法定节假日判断
        System.out.println("2026-01-01(元旦)   isHoliday: " + provider.isHoliday(LocalDate.of(2026, 1, 1)));
        System.out.println("2026-10-01(国庆)   getHoliday: " + provider.getHoliday(LocalDate.of(2026, 10, 1)));
        System.out.println("2026-06-15(普通周一) isHoliday: " + provider.isHoliday(LocalDate.of(2026, 6, 15)));

        // 调休补班判断
        System.out.println("2026-01-04(元旦调休上班) isWorkday: " + provider.isWorkday(LocalDate.of(2026, 1, 4)));
        System.out.println("2026-10-03(国庆假期周六) isWorkday: " + provider.isWorkday(LocalDate.of(2026, 10, 3)));

        // 全年汇总
        List<HolidayInfo> all = provider.getHolidays(2026);
        long holidays = all.stream().filter(h -> "holiday".equals(h.getType())).count();
        long works = all.stream().filter(h -> "work".equals(h.getType())).count();
        System.out.println("2026 全年记录数: " + all.size() + " (放假 " + holidays + " 天 / 调休上班 " + works + " 天)");
    }
}
