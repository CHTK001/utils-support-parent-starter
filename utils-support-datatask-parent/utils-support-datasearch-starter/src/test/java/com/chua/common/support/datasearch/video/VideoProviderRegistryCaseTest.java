package com.chua.common.support.datasearch.video;

import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.datasearch.video.spi.VideoProviderRegistry;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Locale;
import java.util.Set;

/**
 * 视频提供者封禁注册表大小写不敏感回归测试。
 *
 * <p>回归点：SPI 通过 {@code @Spi} 注册的名称被统一转为大写（如 {@code MUOU}），
 * 而 {@code blocked-providers.json} 与各提供者的自检使用小写（如 {@code muou}）。
 * {@link VideoProviderRegistry#isBlocked(String)} 曾直接 {@code containsKey}，
 * 导致 {@code VideoSearchService} 用 SPI key 查询时永远匹配不上，聚合层的
 * "跳过已封禁数据源" 形同虚设（被封站点仍会被实际请求直至超时）。</p>
 *
 * <p>修复后本注册表在存入/查询时统一归一为小写，本测试锁定该行为。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoProviderRegistryCaseTest {

    /**
     * 通过数
     */
    private static int pass = 0;

    /**
     * 失败数
     */
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        Set<String> blocked = VideoProviderRegistry.getBlockedNames();
        Set<String> spiKeys = ServiceProvider.of(ResourceProvider.class).list().keySet();
        System.out.println("      blocked=" + blocked);
        System.out.println("      spiKeys=" + spiKeys);

        check("封禁名单已从 blocked-providers.json 加载", !blocked.isEmpty());
        check("SPI 注册表非空", !spiKeys.isEmpty());

        // 1) 名单里的名字，任意大小写都能命中（修复前为大小写敏感）
        for (String name : blocked) {
            check("名单名[" + name + "]原样命中", VideoProviderRegistry.isBlocked(name));
            check("名单名[" + name + "]大写命中", VideoProviderRegistry.isBlocked(name.toUpperCase(Locale.ROOT)));
            check("名单名[" + name + "]首字母大写命中",
                    VideoProviderRegistry.isBlocked(capitalize(name)));
        }

        // 2) SPI key（大写）与名单（小写）应能正确对应——这是聚合层跳过逻辑生效的前提
        int matched = 0;
        for (String key : spiKeys) {
            boolean expected = blocked.contains(key.toLowerCase(Locale.ROOT));
            check("SPI key[" + key + "]封禁判定一致", VideoProviderRegistry.isBlocked(key) == expected);
            if (expected) {
                matched++;
                check("已封禁源[" + key + "]能取到原因",
                        VideoProviderRegistry.getBlockReason(key) != null);
            }
        }
        check("存在被封禁的已注册源（否则本回归点无覆盖）", matched > 0);

        // 3) block/unblock/getBlockReason 大小写不敏感（使用合成名，不影响真实名单）
        String synthetic = "CaseSensitiveProbe";
        VideoProviderRegistry.block(synthetic, VideoProviderRegistry.BlockReason.API_CHANGED);
        check("block 后大写查询命中", VideoProviderRegistry.isBlocked("CASESENSITIVEPROBE"));
        check("block 后小写查询命中", VideoProviderRegistry.isBlocked("casesensitiveprobe"));
        check("block 后混合大小写查询命中", VideoProviderRegistry.isBlocked("cAsEsEnSiTiVePrObE"));
        check("getBlockReason 大小写不敏感",
                VideoProviderRegistry.BlockReason.API_CHANGED
                        == VideoProviderRegistry.getBlockReason("CASESENSITIVEPROBE"));
        VideoProviderRegistry.unblock("CASESENSITIVEPROBE");
        check("unblock 大小写不敏感", !VideoProviderRegistry.isBlocked("casesensitiveprobe"));

        check("空名不误判为已封禁", !VideoProviderRegistry.isBlocked(null));

        System.out.println("[VideoProviderRegistryCaseTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 首字母大写（其余小写）。
     *
     * @param value 原值
     * @return 首字母大写后的值
     */
    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * 断言。
     *
     * @param name 用例名
     * @param ok   是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
