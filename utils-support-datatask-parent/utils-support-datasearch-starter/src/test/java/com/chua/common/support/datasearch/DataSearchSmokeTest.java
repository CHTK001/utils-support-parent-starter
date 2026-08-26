package com.chua.common.support.datasearch;

import com.chua.common.support.datasearch.express.spi.ExpressProvider;
import com.chua.common.support.datasearch.holiday.spi.HolidayProvider;
import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.datasearch.region.spi.RegionProvider;
import com.chua.common.support.datasearch.software.spi.SoftwareProvider;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * datasearch 全量数据获取 SPI 冒烟测试。
 *
 * <p>逐个解析器真实调用一次，输出 PASS / FAIL / EMPTY 状态表；
 * 退出码 0 = 无 FAIL。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DataSearchSmokeTest {

    private static int failures = 0;

    /**
     * 执行全量冒烟验证。
     *
     * @param args 可选：仅测试包含该关键字的实现名
     */
    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0].toLowerCase() : "";

        forEach(ExpressProvider.class, filter, (name, p) ->
                report(name, () -> size(p.query("SF0000000000001"))));
        forEach(HolidayProvider.class, filter, (name, p) ->
                report(name, () -> size(p.getHolidays(2026))));
        forEach(RegionProvider.class, filter, (name, p) ->
                report(name, () -> size(p.getRegions())));
        forEach(SoftwareProvider.class, filter, (name, p) ->
                report(name, () -> size(p.search("spring"))));
        forEach(MusicSourceProvider.class, filter, (name, p) ->
                report(name, () -> {
                    var r = p.searchPlaylists("周杰伦", 1, 10);
                    return r == null ? 0 : 1;
                }));
        forEach(ResourceProvider.class, filter, (name, p) ->
                report(name, () -> {
                    var r = p.searchResource(new VideoSearch("流浪地球"));
                    if (r == null || r.getData() == null || r.getData().getData() == null) {
                        return 0;
                    }
                    return r.getData().getData().size();
                }));

        System.out.println(failures == 0 ? "[DONE] no FAIL"
                : "[DONE] " + failures + " FAIL(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * 遍历某 SPI 类型的全部具名实现并执行动作。
     */
    private static <T> void forEach(Class<T> type, String filter, BiConsumer<String, T> action) {
        Set<String> names;
        try {
            names = ServiceProvider.of(type).getExtensions();
        } catch (Exception e) {
            System.out.println("[FAIL] " + type.getSimpleName() + " SPI init: " + e.getMessage());
            failures++;
            return;
        }
        for (String name : names) {
            if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) {
                continue;
            }
            try {
                T impl = ServiceProvider.of(type).getExtension(name);
                if (impl == null) {
                    continue;
                }
                action.accept(name, impl);
            } catch (Exception e) {
                System.out.printf("[FAIL] %-12s exception: %.90s%n",
                        name, e.getMessage());
                failures++;
            }
        }
    }

    /**
     * 输出单个实现的调用结果并统计失败。
     */
    private static void report(String name, SizeSupplier supplier) {
        try {
            int n = supplier.get();
            String status = n > 0 ? "[PASS]" : "[EMPTY]";
            if (n <= 0) {
                failures++;
            }
            System.out.printf("%s %-14s rows=%d%n", status, name, n);
        } catch (Exception e) {
            System.out.printf("[FAIL] %-14s %s%n", name,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage());
            failures++;
        }
    }

    private interface SizeSupplier {
        int get() throws Exception;
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
